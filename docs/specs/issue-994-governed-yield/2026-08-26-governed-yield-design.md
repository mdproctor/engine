# Governed Yield — Design Spec

**Issue:** casehubio/engine#994
**Branch:** issue-994-governed-yield
**Date:** 2026-08-26

---

## Problem

The engine dispatches work to agents, workers, and humans. Some work is mechanical — execute and return. Some requires judgment — a decision that needs context, evidence, and accountability. Today the engine handles judgment through two separate mechanisms:

1. **HumanTaskTarget** — a binding fires, the engine creates a WorkItem, a human responds via an inbox. The response is applied to the case context. No verification. No evidence requirements. Human-only.

2. **ActionRiskClassifier gate** — a worker returns a `PlannedAction`, the engine classifies risk, and if `GateRequired` fires, creates a WorkItem for human approval. The response approves or rejects the deferred output. Human-only.

Both mechanisms yield to an external caller, wait for a response, and process the result. Both use WorkItems as transport. Both assume human callers. They share no infrastructure — separate SPIs (`HumanTaskScheduler`, `ActionGateScheduler`), separate request types, separate completion paths (`PlanItemCompletionApplier`, `GateCompletionApplier`), separate routing strategies. Additionally, `OversightGateService` (engine-api) provides a third oversight mechanism with `openGate()`/`fulfill()` lifecycle methods and M-of-N approval via `QuorumConfig`.

**Governed yield unifies them.** One yield mechanism. One scheduler SPI. One completion path. Any caller — human, LLM, A2A agent, webhook. Every response verified before acceptance. Every exchange recorded in the ledger.

**Lifecycle differences acknowledged:** The binding-declared path and the gate path have genuinely different trigger timing (context change vs worker completion), data models (binding config vs `PlannedAction` + deferred output), and state management (PlanItem vs `PendingActionGate`). Unification targets the scheduling and completion infrastructure — the common mechanism for "yield to a caller and process the response." The trigger paths remain separate handlers that construct `JudgmentRequest` from their respective contexts.

---

## Architecture Overview

Three layers, each with clear ownership:

| Layer | Owns | Provides |
|-------|------|----------|
| **Engine** | Yield mechanics, verification gate, escalation | JudgmentTarget, JudgmentScheduler, JudgmentVerifier, JudgmentEscalator SPIs |
| **Blocks** | Agentic pattern integration, LLM caller | LLM JudgmentScheduler, verification strategies, yield-aware patterns |
| **Qhorus** | Trust scoring, commitment lifecycle, compliance | JUDGMENT commitment type, E4 trust routing, E5 evidence export, E7 formal verification |

The engine doesn't care who the caller is. It cares that the response is verified, evidenced, and traceable.

---

## Engine Layer

### 1. JudgmentTarget — Unified Yield Target

`JudgmentTarget` replaces `HumanTaskTarget` as a sealed permit of `BindingTarget`. `HumanTaskTarget` is deleted.

```java
// api/src/main/java/io/casehub/api/model/JudgmentTarget.java
public final class JudgmentTarget implements BindingTarget {

    // Universal fields — every judgment has these
    private final String prompt;
    private final ExpressionEvaluator inputMapping;
    private final ExpressionEvaluator outputMapping;
    private final Class<?> resolutionType;
    private final Duration expiresIn;
    private final ExpressionEvaluator expiresInExpression;
    private final ExpressionEvaluator expiresAtExpression;
    private final VerificationMode verificationMode; // SYNCHRONOUS (v1 only)
    private final String verifierStrategy;            // NamedStrategy ID
    private final String escalatorStrategy;           // NamedStrategy ID
    private final String trustPolicy;             // TrustRoutingPolicy name (reuses existing model)
    private final List<EvidenceRequirement> evidenceRequirements;

    // Caller-specific config — opaque to the engine handler
    private final CallerConfig callerConfig;

    // Builder convenience methods
    public static Builder builder() { ... }
    public static Builder forHuman() { ... } // pre-sets CallerConfig.Human
    public static Builder forLlm() { ... }   // pre-sets CallerConfig.Llm
    public static Builder forAny() { ... }   // pre-sets CallerConfig.Any
}
```

**CallerConfig** — sealed hierarchy for caller-specific fields:

```java
// api/src/main/java/io/casehub/api/model/CallerConfig.java
public sealed interface CallerConfig
    permits CallerConfig.Human, CallerConfig.Llm, CallerConfig.A2A, CallerConfig.Any {

    record Human(
        CandidateSetSpec candidateGroups,
        CandidateSetSpec candidateUsers,
        String title,
        ExpressionEvaluator titleExpression,
        Set<String> outcomes,
        Integer claimDeadlineHours,
        String scope,
        ExpressionEvaluator scopeExpression,
        String priority,
        String templateRef,
        Class<?> payloadType,
        @Nullable QuorumConfig quorum  // M-of-N approval (subsumes OversightGateService)
    ) implements CallerConfig {}

    record Llm(
        String model,
        String modelName,
        String systemPrompt
    ) implements CallerConfig {}

    record A2A(
        String endpoint,
        String skill,
        boolean streaming
    ) implements CallerConfig {}

    record Any() implements CallerConfig {}
}
```

**VerificationMode:**

```java
public enum VerificationMode {
    SYNCHRONOUS
    // Future: ASYNC — accept immediately, verify in background
}
```

**EvidenceRequirement:**

```java
public record EvidenceRequirement(
    String name,
    EvidenceType type,
    boolean required
) {}

public enum EvidenceType {
    REASONING,    // Verbal justification
    DOCUMENT,     // File/document reference
    REFERENCE,    // Citation or link
    ATTESTATION   // Signed statement or credential
}
```

### 2. JudgmentResponse — What the Caller Returns

```java
// api/src/main/java/io/casehub/api/model/JudgmentResponse.java
public record JudgmentResponse(
    Object decision,               // Typed via resolutionType + ContextBridge (same protocol as HumanTaskTarget.resolutionType)
    List<Evidence> evidence,       // Matched by name to EvidenceRequirements
    CallerIdentity callerIdentity, // Who responded
    Instant responseTime
) {}
// Note: decision IS the ContextBridge-typed payload. JudgmentResponse wraps it
// with evidence and caller identity. The existing ContextBridge protocol handles
// serialization/deserialization of the decision field — same as today's WorkItem
// resolution. Evidence is additional metadata alongside the typed decision.

public record Evidence(
    String name,          // Matches EvidenceRequirement.name
    EvidenceType type,
    String content,       // Text content or serialized reference
    @Nullable String ref  // URI for DOCUMENT/REFERENCE types
) {}

public record CallerIdentity(
    String callerId,      // Worker name, user ID, agent ID
    String callerType,    // "human", "llm", "a2a", "system"
    @Nullable Double trustScore
) {}
```

### 3. JudgmentScheduler — Unified Routing SPI

Replaces both `HumanTaskScheduler` and `ActionGateScheduler`.

```java
// common/src/main/java/io/casehub/engine/common/spi/JudgmentScheduler.java
public interface JudgmentScheduler {
    void schedule(JudgmentRequest request);
}
```

**JudgmentRequest** — common header + sealed origin-specific payload. Avoids a half-null union type (review finding R1-02):

```java
// common/src/main/java/io/casehub/engine/common/spi/JudgmentRequest.java
public record JudgmentRequest(
    UUID caseId,
    String tenancyId,
    String bindingName,
    JudgmentTarget target,
    JudgmentPayload payload
) {}

// Sealed payload — origin-specific fields without nullable union
public sealed interface JudgmentPayload
    permits JudgmentPayload.BindingPayload, JudgmentPayload.GatePayload {

    record BindingPayload(
        Map<String, Object> inputData,
        @Nullable String payloadTypeName,
        @Nullable String resolutionTypeName,
        @Nullable Set<String> resolvedCandidateGroups,
        @Nullable Set<String> resolvedCandidateUsers,
        @Nullable Instant caseBudgetDeadline,   // from PropagationContext (case-level)
        @Nullable Instant expiresAtDeadline,     // from binding's expiresAt expression (judgment-level SLA)
        @Nullable String resolvedTitle,
        @Nullable String resolvedScope,
        List<RetrievedExperience> experiences,
        Map<String, Double> candidateScores
    ) implements JudgmentPayload {}

    record GatePayload(
        long gateId,
        PlannedAction plannedAction,
        RiskDecision.GateRequired gateRequired,
        Set<String> resolvedCandidateGroups,
        @Nullable String resolutionTypeName,
        Map<String, Object> deferredOutput
    ) implements JudgmentPayload {}
}
```

**NoOpJudgmentScheduler** — `@DefaultBean @ApplicationScoped` no-op default in `runtime/internal/worker/`. Note: this changes the CDI discovery pattern from optional `Instance<>` injection (current HumanTaskScheduler) to `@DefaultBean` — consistent with the engine's established SPI pattern (PP-20260514-engine-spi-noops-defaultbean).

**EngineStrategyResolver** — gains explicit `@Any Instance<JudgmentVerifier>` and `@Any Instance<JudgmentEscalator>` constructor parameters for Quarkus ARC build-time discovery (per GE-20260704-d6aacc — `Instance<SuperInterface>` does not discover sub-interface beans without explicit injection).

**Implementations:**
- `CloudEventJudgmentScheduler` (in `work-cloudevent` module) — replaces both `CloudEventJudgmentScheduler` and `CloudEventActionGateScheduler`
- Work-engine-adapter implementation (in `casehub-work` repo) — replaces both `HumanTaskScheduleHandler` and `ActionGateWorkItemHandler`

### 4. JudgmentVerifier — Post-Response Verification

```java
// api/src/main/java/io/casehub/api/spi/JudgmentVerifier.java
public interface JudgmentVerifier extends NamedStrategy {
    VerificationResult verify(JudgmentResponse response, VerificationContext context);
}
```

**VerificationResult** — sealed:

```java
public sealed interface VerificationResult
    permits VerificationResult.Accepted,
            VerificationResult.InsufficientEvidence,
            VerificationResult.TrustTooLow,
            VerificationResult.Rejected {

    record Accepted() implements VerificationResult {}

    record InsufficientEvidence(
        String feedback,
        List<String> missingRequirements
    ) implements VerificationResult {}

    record TrustTooLow(
        double required,
        double actual
    ) implements VerificationResult {}

    record Rejected(String reason) implements VerificationResult {}
}
```

**VerificationContext:**

```java
public record VerificationContext(
    UUID caseId,
    String tenancyId,
    String bindingName,
    JudgmentTarget target,
    CaseContext caseContext,
    JudgmentPayload payload  // Sealed type — discriminate via pattern match, not separate enum
) {}
```

**Built-in verifiers (engine):**
- `EvidencePresenceVerifier` (id=`"evidence-presence"`) — checks all required evidence slots are filled
- `NoOpJudgmentVerifier` (`@DefaultBean`, id=`"none"`) — accepts all responses

**Blocks-provided verifiers:**
- `SchemaValidationVerifier` (id=`"schema-validation"`) — validates decision against a JSON Schema
- `ConsensusVerifier` (id=`"consensus"`) — M-of-N agreement across multiple callers
- `LlmEvaluationVerifier` (id=`"llm-evaluation"`) — LLM evaluates the response quality

### 5. JudgmentEscalator — Verification Failure Handling

```java
// api/src/main/java/io/casehub/api/spi/JudgmentEscalator.java
public interface JudgmentEscalator extends NamedStrategy {
    EscalationDecision escalate(EscalationContext context);
}
```

**EscalationDecision** — sealed:

```java
public sealed interface EscalationDecision
    permits EscalationDecision.ReYield,
            EscalationDecision.Escalate,
            EscalationDecision.Fault {

    record ReYield(
        String feedback  // Sent back to the same caller with context
    ) implements EscalationDecision {}

    record Escalate(
        @Nullable CallerConfig newCallerConfig, // Override caller selection
        String reason
    ) implements EscalationDecision {}

    record Fault(String reason) implements EscalationDecision {}
}
```

**EscalationContext:**

```java
public record EscalationContext(
    UUID caseId,
    String tenancyId,
    String bindingName,
    JudgmentTarget target,
    JudgmentResponse response,
    VerificationResult verificationResult,
    int attemptCount,
    int maxAttempts
) {}
```

**Built-in escalators:**
- `DefaultJudgmentEscalator` (`@DefaultBean`, id=`"default"`) — re-yield once with feedback, then fault
- `TrustEscalationStrategy` (id=`"trust-escalation"`) — escalate to higher-trust caller on verification failure

### 6. Engine Handler Integration

`CaseContextChangedEventHandler.publishByTarget()` switch gains:

```java
case JudgmentTarget jt ->
    publishJudgment(caseInstance, caseDefinition, binding, jt, experiences, activationSnapshot);
```

The `publishJudgment` method:
1. Evaluates `inputMapping` against the working layer
2. Resolves candidate sets (human callers only — delegates to CallerConfig type)
3. Applies `HumanTaskRoutingStrategy` when CallerConfig is Human (reuse existing routing)
4. Builds `JudgmentRequest` with `origin=BINDING`
5. Submits to `JudgmentScheduler`

**ActionRiskClassifier gate path** (`WorkflowExecutionCompletedHandler.handleWithPlannedAction`):
1. Classifier returns `GateRequired` (unchanged)
2. Handler builds `JudgmentRequest` with `origin=GATE`, `gateAction`, `deferredOutput`
3. Submits to `JudgmentScheduler` (replaces direct `ActionGateScheduler.schedule()`)

**Pending judgment state** (new `PendingJudgment`):

Between `JudgmentScheduler.schedule()` and response arrival, state is stored in `PendingJudgment` on `CaseInstance` (in-memory, same pattern as `PendingActionGate`):

```java
public record PendingJudgment(
    long judgmentId,                       // Unique ID for correlation
    String bindingName,
    JudgmentPayload payload,              // Carries origin-specific state
    @Nullable String workerId,            // Gate path: originating worker
    @Nullable String idempotency,         // Gate path: for re-fire
    @Nullable Map<String, Object> deferredOutput,  // Gate path: held worker output
    Instant yieldedAt
) {}
```

**Concurrency constraint (v1):** One pending judgment per case per origin type. Multiple binding-declared judgments can coexist (different bindings), but only one gate judgment at a time (same constraint as current `PendingActionGate`). `CaseInstance` stores `Map<String, PendingJudgment>` keyed by bindingName (binding path) or `"__gate__"` (gate path).

**Judgment response path** (new `JudgmentResponseHandler`):
1. Receives `JudgmentResponseEvent` (from scheduler/work-adapter/CloudEvent consumer)
2. Correlates to `PendingJudgment` by caseId + judgmentId
3. Runs `JudgmentVerifier.verify()` synchronously
4. **On `Accepted` — origin-dependent data flow:**
   - **Binding origin:** apply `outputMapping(response.decision)` to context → publish `CONTEXT_CHANGED`
   - **Gate origin:** write `judgmentApproved` signal to context, clear `PendingJudgment`, re-fire `WorkflowExecutionCompleted.approved()` with `deferredOutput` (same as current `ActionGateApprovedHandler`)
5. **On non-accepted:** runs `JudgmentEscalator.escalate()`
   - `ReYield` → re-submit to `JudgmentScheduler` with feedback
   - `Escalate` → re-submit with overridden CallerConfig
   - `Fault`:
     - **Binding origin:** fault the PlanItem
     - **Gate origin:** write `judgmentRejected`/`judgmentExpired` signal to context, clear `PendingJudgment`, call `workerStatusListener.onWorkerCompleted()` with faulted status, record routing outcome via `RoutingOutcomeRecorder`, trigger `RecoveryCoordinator` for the originating worker
6. Writes EventLog entries at each step

### 7. Ledger Events

New `CaseHubEventType` entries:

| Event | When | Metadata | Replaces |
|-------|------|----------|----------|
| `JUDGMENT_YIELDED` | Request submitted to scheduler | prompt, evidenceRequirements, callerConfig type, trustPolicy, selectedCaller | ACTION_GATE_PENDING (gate path) |
| `JUDGMENT_RESPONDED` | Response received from caller | callerIdentity, evidence provided, responseTime | ACTION_GATE_APPROVED (gate path) |
| `JUDGMENT_VERIFIED` | Verifier ran | verifierStrategy, result type, details | (new) |
| `JUDGMENT_ESCALATED` | Escalator fired | reason, fromCaller, toCaller, attemptCount | (new) |
| `JUDGMENT_REJECTED` | Verification failed and escalator chose Fault | reason, verificationResult | ACTION_GATE_REJECTED (gate path) |
| `JUDGMENT_CANCELLED` | Case reached terminal state before response | caseStatus | ACTION_GATE_CANCELLED (gate path) |
| `JUDGMENT_EXPIRED` | Deadline elapsed with no response | deadline, escalatorDecision | ACTION_GATE_EXPIRED (gate path) |

All written via existing `EventLogRepository`. Metadata JSON matches existing patterns.

**Migration:** The 5 existing `ACTION_GATE_*` event types are replaced by the corresponding `JUDGMENT_*` events above. Existing EventLog entries with `ACTION_GATE_*` types remain readable — the event type column is a string, not an enum FK. Queries should accept both old and new names during the transition period.

### 8. Deleted Types

| Type | Replacement |
|------|-------------|
| `HumanTaskTarget` | `JudgmentTarget` with `CallerConfig.Human` |
| `HumanTaskScheduler` | `JudgmentScheduler` |
| `HumanTaskScheduleRequest` | `JudgmentRequest` with `BindingPayload` |
| `ActionGateScheduler` | `JudgmentScheduler` |
| `ActionGateScheduleRequest` | `JudgmentRequest` with `GatePayload` |
| `Instance<HumanTaskScheduler>` optional injection | `NoOpJudgmentScheduler` `@DefaultBean` (CDI pattern change: optional → default) |
| `NoOpActionGateScheduler` | (absorbed into `NoOpJudgmentScheduler`) |
| `OversightGateService` | `JudgmentScheduler` + `QuorumConfig` on `CallerConfig.Human` |
| `GateCompletionApplier` | (merged into `PlanItemCompletionApplier`) |
| `ACTION_GATE_*` event types | `JUDGMENT_*` event types (see §7) |

`ActionRiskClassifier`, `RiskDecision`, `PlannedAction`, `ClassificationContext`, `@RiskClassifier` qualifier, `ChainedActionRiskClassifier` — all **retained unchanged**. The classifier still decides IF a gate is needed; JudgmentScheduler replaces WHERE it goes.

`HumanTaskRoutingStrategy` — **retained**. Invoked by the engine handler when `CallerConfig` is `Human`. The SPI works on candidate sets, which are a human-caller concern.

`PlanItemCompletionApplier` — **evolved** to handle all judgment completions (replaces dual completion paths). `GateCompletionApplier` deleted.

---

## Blocks Layer

### 9. Yield Step in DagPlan

`DagNode<T>` gains an optional `JudgmentTarget` for yield nodes:

```java
// engine-api: io.casehub.engine.plan.DagNode
public record DagNode<T>(
    String id,
    T task,
    Set<String> dependsOn,
    JoinType joinType,
    @Nullable DagPlan<T> contingency,
    @Nullable JudgmentTarget judgment  // NEW — yield at this node
) { ... }
```

**Execution model constraint:** Blocks patterns currently run within a single `WorkerFunction` invocation — `PatternWorkerFunctionHandler` submits to a virtual thread and `future.get()` blocks until completion. There is no persistence boundary within a pattern execution. Mid-pattern yield requires the worker function to return and later resume, which is a new execution lifecycle.

**v1 approach:** Judgment steps in patterns are modeled as **separate case bindings** triggered between pattern nodes, not as mid-pattern pauses. The pattern completes its step, writes output to context, and a judgment binding fires on that context change. After the judgment completes, the next pattern step fires. This reuses the existing choreography model.

**DagDriver yield (future):** `DagDriver` (`engine-common`) has the infrastructure for node-level pause/resume (it already tracks per-node state via `NodeState` sealed type). Adding a `Yielded` state to `NodeState` and having the driver pause on judgment nodes is architecturally clean. But two blockers exist: (1) `DagNode.task` enforces `Objects.requireNonNull(task)` — judgment-only nodes require either making `task` nullable with a sealed discriminator, or wrapping both in `sealed interface NodePayload permits TaskPayload, JudgmentPayload`. (2) Blocks' `PatternWorkerFunctionHandler` runs within a single worker function invocation — mid-pattern yield requires a new `WorkerFunction` lifecycle variant where the function can checkpoint and resume. Both tracked as future work.

**LeafTask with JudgmentTarget** — blocks' `PrimitiveTask` and `PlannedTask` gain nullable `JudgmentTarget`. In v1, when present, the pattern decomposes the judgment step into a separate case binding (not mid-pattern yield). The "execute then review" pattern is achieved via binding sequencing.

### 10. LLM JudgmentScheduler Implementation

```java
// blocks: io.casehub.blocks.judgment.LlmJudgmentScheduler
@ApplicationScoped
public class LlmJudgmentScheduler implements JudgmentScheduler { ... }
```

When `CallerConfig` is `Llm`:
1. Builds a prompt from `JudgmentRequest.target().prompt()` + input data + evidence requirements
2. Calls `ChatModel` via `ChatModelProvider`
3. Parses response into `JudgmentResponse` with `CallerIdentity(modelName, "llm", null)`
4. Publishes `JudgmentResponseEvent`

Falls through to `NoOpJudgmentScheduler` when `ChatModelProvider` is absent.

### 11. Verification Strategies

**SchemaValidationVerifier** — validates `JudgmentResponse.decision` against a JSON Schema declared on the `JudgmentTarget`.

**ConsensusVerifier** — M-of-N verification. Submits the same judgment to N callers, requires M agreeing responses. Uses `QuorumConfig` (existing type from ActionRiskClassifier).

**LlmEvaluationVerifier** — an LLM evaluates the quality of another caller's response. Uses a dedicated evaluation prompt.

### 12. Yield-Aware Pattern Variants

**SUPERVISOR with oversight yield:**
```yaml
pattern:
  type: SUPERVISOR
  iterations: 3
  judgment:
    prompt: "Review the analysis output"
    caller:
      type: llm
      model: anthropic
      modelName: claude-sonnet-4-20250514
    verifier: schema-validation
    evidence:
      - name: assessment
        type: REASONING
        required: true
```

**PIPELINE with quality gates:**
```yaml
pattern:
  type: PIPELINE
  judgment:
    afterStep: true  # yield after each step
    caller:
      type: human
      candidateGroups: [qa-team]
    verifier: evidence-presence
```

---

## Qhorus Layer

### 13. JUDGMENT Commitment Type

New speech act type in qhorus's taxonomy:

```java
// qhorus-api: io.casehub.qhorus.api.model.SpeechActType
JUDGMENT  // Engine yields, caller commits to respond
```

Commitment lifecycle:
- **Created** when `JudgmentScheduler.schedule()` fires
- **Fulfilled** when `JudgmentResponse` is accepted (after verification)
- **Violated** when `expiresIn` elapses with no response
- **Cancelled** when the case reaches terminal state before response

### 14. E4 Integration — Trust-Scored Caller Selection

`JudgmentScheduler` implementations query trust scores from qhorus's `CapabilityRouter` when selecting callers. The `trustPolicy` on `JudgmentTarget` references a `TrustRoutingPolicy` name — reusing the existing phase-based trust maturity model (QUALIFIED, BOOTSTRAP, BORDERLINE, EXCLUDED) rather than introducing a separate binary threshold. Trust scores are recorded in `JUDGMENT_YIELDED` EventLog metadata.

### 15. E5 Integration — Compliance Evidence Export

Judgment EventLog entries (YIELDED, RESPONDED, VERIFIED, ESCALATED) are included in E5 compliance reports. The evidence chain — what was asked, who responded, what evidence was provided, how it was verified — forms an attribution chain for audit.

### 16. E7 Integration — Formal Verification

Temporal properties validated on JUDGMENT commitments:
- **Liveness:** every yield eventually resolves (response, timeout, or escalation)
- **Safety:** no unverified judgment advances the case (synchronous verification)
- **Fairness:** trust-weighted distribution across callers (no single caller monopolizes)

---

## YAML Schema

### Binding-Level Judgment

```yaml
bindings:
  - name: dosage-review
    judgment:
      prompt: "Review the proposed dosage modification for safety"
      caller:
        type: human
        candidateGroups: [physicians, pharmacists]
        title: "Dosage Safety Review"
        outcomes: [approve, reject, modify]
        claimDeadlineHours: 4
      inputMapping: "{ drug: .drug, currentDose: .currentDose, proposedDose: .proposedDose }"
      outputMapping: "{ reviewOutcome: .decision, conditions: .conditions }"
      resolutionType: io.casehub.clinical.DosageReviewDecision
      evidence:
        - name: clinical-rationale
          type: REASONING
          required: true
        - name: reference-guideline
          type: REFERENCE
          required: false
      verifier: evidence-presence
      trustPolicy: clinical-high-trust
      expiresIn: PT24H
      escalator: trust-escalation
    on:
      contextChange:
        condition: ".proposedDose != null and .reviewOutcome == null"
```

### LLM Caller

```yaml
bindings:
  - name: analysis-review
    judgment:
      prompt: "Evaluate the analysis quality and completeness"
      caller:
        type: llm
        model: anthropic
        modelName: claude-sonnet-4-20250514
      evidence:
        - name: assessment
          type: REASONING
          required: true
      verifier: schema-validation
      expiresIn: PT5M
    on:
      contextChange:
        condition: ".analysisOutput != null"
```

### Any Caller (scheduler routes)

```yaml
bindings:
  - name: compliance-check
    judgment:
      prompt: "Verify transaction compliance with AML regulations"
      caller:
        type: any
      evidence:
        - name: rationale
          type: REASONING
          required: true
        - name: regulation-reference
          type: REFERENCE
          required: true
      verifier: evidence-presence
      trustPolicy: compliance-strict
      expiresIn: PT48H
    on:
      contextChange:
        condition: ".transaction != null and .complianceResult == null"
```

---

## Migration

### Consumer YAML

Mechanical find-replace:

| Before | After |
|--------|-------|
| `humanTask:` | `judgment:` |
| `templateRef:` | `caller: { type: human, templateRef: ... }` |
| `candidateGroups:` | Nest under `caller:` |
| `title:` | Nest under `caller:` |
| `outcomes:` | Nest under `caller:` |

Fields that stay top-level: `inputMapping`, `outputMapping`, `expiresIn`, `resolutionType`.

New fields to add (optional): `evidence`, `verifier`, `trustPolicy`, `escalator`.

### Java DSL

```java
// Before
Binding.builder()
    .humanTask(HumanTaskTarget.inline()
        .candidateGroups(Set.of("reviewers"))
        .title("Review")
        .build())

// After
Binding.builder()
    .judgment(JudgmentTarget.forHuman()
        .candidateGroups(Set.of("reviewers"))
        .title("Review")
        .build())
```

### Work-Engine-Adapter

The adapter's `HumanTaskScheduleHandler` and `ActionGateWorkItemHandler` merge into a single `JudgmentWorkItemHandler` implementing `JudgmentScheduler`. WorkItem creation logic is preserved — the handler creates WorkItems for `CallerConfig.Human`, LLM calls for `CallerConfig.Llm`, etc.

### CloudEvent Module

`CloudEventJudgmentScheduler` and `CloudEventActionGateScheduler` merge into `CloudEventJudgmentScheduler`. CloudEvent type changes from `io.casehub.work.workitem.create` to `io.casehub.judgment.request`.

---

## Implementation Batches

### Batch 1: Engine Foundation (engine#995-997)

1. **JudgmentTarget type** (#995) — `JudgmentTarget`, `CallerConfig`, `EvidenceRequirement`, `EvidenceType`, `VerificationMode`, `JudgmentResponse`, `Evidence`, `CallerIdentity` in `engine-api`
2. **JudgmentScheduler SPI** (#996) — `JudgmentScheduler`, `JudgmentRequest`, `JudgmentOrigin`, `NoOpJudgmentScheduler` in `engine-common` and `runtime`
3. **JudgmentVerifier SPI** (#997) — `JudgmentVerifier`, `VerificationResult`, `VerificationContext`, `EvidencePresenceVerifier`, `NoOpJudgmentVerifier` in `engine-api` and `runtime`
4. **Minimal YAML example** — a simple case definition using `judgment:` YAML syntax

### Batch 2: Engine+Qhorus Integration (engine#998-999, qhorus#411-412)

5. **Judgment ledger events** (#998) — `JUDGMENT_YIELDED`, `JUDGMENT_RESPONDED`, `JUDGMENT_VERIFIED`, `JUDGMENT_ESCALATED` in `CaseHubEventType`, EventLog metadata schema
6. **JudgmentEscalator SPI** (#999) — `JudgmentEscalator`, `EscalationDecision`, `EscalationContext`, `DefaultJudgmentEscalator` in `engine-api` and `runtime`
7. **JUDGMENT commitment type** (qhorus#411) — new speech act in qhorus taxonomy
8. **E4 trust routing** (qhorus#412) — `CapabilityRouter` integration for judgment caller selection

### Batch 3: Blocks (blocks#171-173)

9. **LLM JudgmentScheduler** (blocks#171) — `LlmJudgmentScheduler` implementation
10. **Verification strategies** (blocks#172) — `SchemaValidationVerifier`, `ConsensusVerifier`, `LlmEvaluationVerifier`
11. **Yield-aware patterns** (blocks#173) — SUPERVISOR, PIPELINE, DEBATE with judgment steps

### Batch 4: Integration (engine#1000, qhorus#413-414)

12. **DagNode judgment integration** (engine#1000) — yield step in DagDriver, SWF `call: casehub:judgment`
13. **E5 compliance evidence** (qhorus#413) — judgment events in compliance reports
14. **E7 formal verification** (qhorus#414) — temporal invariants for judgment commitments

### Batch 5: Migration + Cleanup

15. Delete `HumanTaskTarget`, `HumanTaskScheduler`, `HumanTaskScheduleRequest`, `ActionGateScheduler`, `ActionGateScheduleRequest`
16. Delete `CloudEventJudgmentScheduler`, `CloudEventActionGateScheduler`
17. Merge `GateCompletionApplier` into `PlanItemCompletionApplier`
18. Update all consumer YAML (`humanTask:` → `judgment:`)
19. Update all consumer Java DSL
20. Update examples across engine, blocks, qhorus

---

## References

- [BindingTarget.java](api/src/main/java/io/casehub/api/model/BindingTarget.java) — sealed hierarchy being extended
- [HumanTaskTarget.java](api/src/main/java/io/casehub/api/model/HumanTaskTarget.java) — type being replaced
- [ActionRiskClassifier.java](api/src/main/java/io/casehub/api/spi/ActionRiskClassifier.java) — gate classification SPI (retained)
- [RiskDecision.java](api/src/main/java/io/casehub/api/spi/RiskDecision.java) — gate decision type (retained)
- [CaseContextChangedEventHandler.java](runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java) — dispatch switch being updated
- [WorkflowExecutionCompletedHandler.java](runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java) — gate path being updated
- [HumanTaskScheduler.java](common/src/main/java/io/casehub/engine/common/spi/HumanTaskScheduler.java) — SPI being replaced
- [ActionGateScheduler.java](common/src/main/java/io/casehub/engine/common/spi/ActionGateScheduler.java) — SPI being replaced
- [DagNode.java](api/src/main/java/io/casehub/engine/plan/DagNode.java) — gaining judgment field
- [GE-20260729-172d18] — Evolve existing SPIs, don't create parallel ones
- [GE-20260521-a0f5a6] — HumanTaskScheduleHandler PlanItem timing gotcha
- [GE-20260704-d6aacc] — Quarkus ARC Instance<SuperInterface> discovery (EngineStrategyResolver)
- [GE-20260607-3b6711] — @RiskClassifier CDI qualifier pattern
- [PP-20260601-81b9e5] — SPI evolution via default methods
- [PP-20260612-a2ef10] — ActionRiskClassifier @RiskClassifier qualifier requirement
- [PP-20260722-60e519] — Cross-repo source verification for worker-api types
- Epic #994 (engine), #170 (blocks), #410 (qhorus) — governed yield epics
- Qhorus E4 (#401), E5 (#402), E7 (#404) — governance roadmap integration points
