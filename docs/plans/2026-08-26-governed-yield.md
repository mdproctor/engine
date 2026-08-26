# Governed Yield Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #994 — governed yield — caller-agnostic judgment infrastructure
**Issue group:** engine#995, #996, #997, #998, #999, #1000; blocks#171, #172, #173; qhorus#411, #412, #413, #414

**Goal:** Replace HumanTaskTarget and ActionGateScheduler with a unified JudgmentTarget yield mechanism that supports any caller type (human, LLM, A2A), post-response verification, evidence requirements, trust-weighted routing, and ledger provenance.

**Architecture:** JudgmentTarget is a new sealed permit of BindingTarget, replacing HumanTaskTarget. CallerConfig (sealed: Human, Llm, A2A, Any) encapsulates caller-specific fields. JudgmentScheduler replaces both HumanTaskScheduler and ActionGateScheduler. JudgmentVerifier validates responses synchronously before acceptance. JudgmentEscalator handles verification failures. The ActionRiskClassifier gate path produces JudgmentRequests via GatePayload, sharing the same scheduling infrastructure.

**Tech Stack:** Java 21, Quarkus 3.32.2, Vert.x event bus, Jackson, jackson-jq

## Global Constraints

- Pre-release platform — breaking changes cost nothing. Fix the design, never protect callers.
- All commits reference an issue: `Refs #N` or `Closes #N`
- IntelliJ MCP required for all .java edits — never use bash Edit/Write on source files
- SPI evolution via default methods (PP-20260601-81b9e5) for optional capabilities
- `@DefaultBean @ApplicationScoped` for no-op SPI defaults (PP-20260514)
- EngineStrategyResolver needs explicit `@Any Instance<>` per strategy type (GE-20260704-d6aacc)
- Worker-api types (WorkerFunction, WorkerResult) live in casehubio/worker repo (PP-20260722-60e519)
- Tests: `@QuarkusTest`, `*Test.java` naming, `casehub-persistence-memory` for in-memory SPIs
- Test port: `quarkus.http.test-port=0`

---

## Batch 1: Engine Foundation Types

### Task 1: JudgmentTarget, CallerConfig, and evidence types (engine#995)

**Files:**
- Create: `api/src/main/java/io/casehub/api/model/JudgmentTarget.java`
- Create: `api/src/main/java/io/casehub/api/model/CallerConfig.java`
- Create: `api/src/main/java/io/casehub/api/model/EvidenceRequirement.java`
- Create: `api/src/main/java/io/casehub/api/model/EvidenceType.java`
- Create: `api/src/main/java/io/casehub/api/model/VerificationMode.java`
- Create: `api/src/main/java/io/casehub/api/model/JudgmentResponse.java`
- Create: `api/src/main/java/io/casehub/api/model/Evidence.java`
- Create: `api/src/main/java/io/casehub/api/model/CallerIdentity.java`
- Modify: `api/src/main/java/io/casehub/api/model/BindingTarget.java` — add `JudgmentTarget` to sealed permits
- Modify: `api/src/main/java/io/casehub/api/model/Binding.java` — add `.judgment(JudgmentTarget)` builder method
- Test: `api/src/test/java/io/casehub/api/model/JudgmentTargetTest.java`
- Test: `api/src/test/java/io/casehub/api/model/CallerConfigTest.java`

**Interfaces:**
- Produces: `JudgmentTarget` class with builder pattern, `CallerConfig` sealed hierarchy, `EvidenceRequirement` record, `JudgmentResponse` record, `Evidence` record, `CallerIdentity` record
- Produces: `Binding.judgment(JudgmentTarget)` builder method (consumed by YAML mapper and DSL users)

- [ ] **Step 1: Write JudgmentTarget builder test**

```java
@Test
void humanJudgmentBuilderCreatesTarget() {
    var target = JudgmentTarget.forHuman()
        .prompt("Review the dosage modification")
        .candidateGroups(Set.of("physicians"))
        .title("Dosage Review")
        .outcomes(Set.of("approve", "reject"))
        .evidence("rationale", EvidenceType.REASONING, true)
        .verifier("evidence-presence")
        .expiresIn(Duration.ofHours(24))
        .build();

    assertNotNull(target);
    assertEquals("Review the dosage modification", target.prompt());
    assertInstanceOf(CallerConfig.Human.class, target.callerConfig());
    var human = (CallerConfig.Human) target.callerConfig();
    assertEquals(Set.of("approve", "reject"), human.outcomes());
    assertEquals(1, target.evidenceRequirements().size());
    assertEquals("rationale", target.evidenceRequirements().get(0).name());
    assertTrue(target.evidenceRequirements().get(0).required());
    assertEquals(VerificationMode.SYNCHRONOUS, target.verificationMode());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl api -Dtest=JudgmentTargetTest#humanJudgmentBuilderCreatesTarget -DfailIfNoTests=false`
Expected: FAIL — class not found

- [ ] **Step 3: Create the foundation types**

Create `EvidenceType` enum, `EvidenceRequirement` record, `VerificationMode` enum, `CallerIdentity` record, `Evidence` record, `JudgmentResponse` record, `CallerConfig` sealed interface with Human/Llm/A2A/Any permits.

Key implementation details:
- `CallerConfig.Human` carries `@Nullable QuorumConfig quorum` for M-of-N approval
- `JudgmentResponse.decision` is `Object` — typed via `resolutionType` + ContextBridge at runtime
- `VerificationMode` starts with only `SYNCHRONOUS`

- [ ] **Step 4: Create JudgmentTarget with builder**

Create `JudgmentTarget implements BindingTarget` with private constructor and `Builder` inner class. Builder convenience methods: `forHuman()`, `forLlm()`, `forA2A()`, `forAny()` pre-set the CallerConfig variant. Builder `.evidence(name, type, required)` accumulates `List<EvidenceRequirement>`. Human-specific builder methods (`.candidateGroups()`, `.title()`, `.outcomes()`, `.quorum()`) delegate to CallerConfig.Human fields. Follow the same builder pattern as `HumanTaskTarget.java` — study its structure via `ide_file_structure`.

- [ ] **Step 5: Add JudgmentTarget to BindingTarget sealed permits**

Use `ide_edit_member` to add `JudgmentTarget` to the `permits` clause of `BindingTarget`:
```java
public sealed interface BindingTarget
    permits CapabilityTarget, SubCaseTarget, HumanTaskTarget, JudgmentTarget, SignalTarget, ExtensionTarget {}
```
Note: `HumanTaskTarget` stays in the permits list during this batch — removal is Batch 5.

- [ ] **Step 6: Add Binding.judgment() builder method**

Use `ide_insert_member` to add to `Binding.Builder`:
```java
public Builder judgment(JudgmentTarget target) {
    this.target = target;
    return this;
}
```

- [ ] **Step 7: Write CallerConfig sealed hierarchy tests**

```java
@Test
void humanCallerConfigCarriesAllFields() {
    var human = new CallerConfig.Human(
        CandidateSetSpec.inline(StaticSetStrategy.of(Set.of("group-a"))),
        null, "Review Title", null, Set.of("approve", "reject"),
        4, "case-scope", null, "HIGH", null, null, null);
    assertInstanceOf(CallerConfig.class, human);
    assertEquals("Review Title", human.title());
    assertEquals(Set.of("approve", "reject"), human.outcomes());
}

@Test
void llmCallerConfigCarriesModelFields() {
    var llm = new CallerConfig.Llm("anthropic", "claude-sonnet-4-20250514", "You are a reviewer");
    assertInstanceOf(CallerConfig.class, llm);
    assertEquals("anthropic", llm.model());
}

@Test
void anyCallerConfigIsEmpty() {
    var any = new CallerConfig.Any();
    assertInstanceOf(CallerConfig.class, any);
}
```

- [ ] **Step 8: Run all tests**

Run: `mvn test -pl api -Dtest=JudgmentTargetTest,CallerConfigTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 9: Verify with ide_diagnostics**

Run `ide_diagnostics` on `api/` to check for compilation issues.

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/JudgmentTarget.java \
       api/src/main/java/io/casehub/api/model/CallerConfig.java \
       api/src/main/java/io/casehub/api/model/EvidenceRequirement.java \
       api/src/main/java/io/casehub/api/model/EvidenceType.java \
       api/src/main/java/io/casehub/api/model/VerificationMode.java \
       api/src/main/java/io/casehub/api/model/JudgmentResponse.java \
       api/src/main/java/io/casehub/api/model/Evidence.java \
       api/src/main/java/io/casehub/api/model/CallerIdentity.java \
       api/src/main/java/io/casehub/api/model/BindingTarget.java \
       api/src/main/java/io/casehub/api/model/Binding.java \
       api/src/test/java/io/casehub/api/model/JudgmentTargetTest.java \
       api/src/test/java/io/casehub/api/model/CallerConfigTest.java
git commit -m "feat(#995): JudgmentTarget type — unified yield target with CallerConfig, evidence, verification

Refs #995"
```

### Task 2: JudgmentScheduler SPI and request types (engine#996)

**Files:**
- Create: `common/src/main/java/io/casehub/engine/common/spi/JudgmentScheduler.java`
- Create: `common/src/main/java/io/casehub/engine/common/spi/JudgmentRequest.java`
- Create: `common/src/main/java/io/casehub/engine/common/spi/JudgmentPayload.java`
- Create: `common/src/main/java/io/casehub/engine/common/spi/PendingJudgment.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/worker/NoOpJudgmentScheduler.java`
- Test: `common/src/test/java/io/casehub/engine/common/spi/JudgmentRequestTest.java`
- Test: `runtime/src/test/java/io/casehub/engine/internal/worker/DefaultWorkerSpiImplementationsTest.java` (extend)

**Interfaces:**
- Consumes: `JudgmentTarget`, `CallerConfig`, `EvidenceRequirement` from Task 1
- Produces: `JudgmentScheduler.schedule(JudgmentRequest)`, `JudgmentRequest` record, `JudgmentPayload` sealed (BindingPayload | GatePayload), `PendingJudgment` record

- [ ] **Step 1: Write JudgmentRequest construction test**

```java
@Test
void bindingPayloadRequestCarriesAllFields() {
    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var payload = new JudgmentPayload.BindingPayload(
        Map.of("key", "value"), null, null, Set.of("group-a"), Set.of(),
        null, null, "Review Title", "case-scope", List.of(), Map.of());
    var request = new JudgmentRequest(
        UUID.randomUUID(), "tenant-1", "review-binding", target, payload);
    assertNotNull(request);
    assertEquals("tenant-1", request.tenancyId());
    assertInstanceOf(JudgmentPayload.BindingPayload.class, request.payload());
}

@Test
void gatePayloadRequestCarriesPlannedAction() {
    var target = JudgmentTarget.forHuman().prompt("Approve action").build();
    var action = PlannedAction.of("Cancel subscription", "sub.cancel", Map.of());
    var gateRequired = new RiskDecision.GateRequired("Review needed", true, null, null, null, null, null);
    var payload = new JudgmentPayload.GatePayload(
        1L, action, gateRequired, Set.of("approvers"), null, Map.of("output", "value"));
    var request = new JudgmentRequest(
        UUID.randomUUID(), "tenant-1", "__gate__", target, payload);
    assertInstanceOf(JudgmentPayload.GatePayload.class, request.payload());
    assertEquals("sub.cancel", ((JudgmentPayload.GatePayload) request.payload()).plannedAction().actionType());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl common -Dtest=JudgmentRequestTest -DfailIfNoTests=false`
Expected: FAIL — classes not found

- [ ] **Step 3: Create JudgmentPayload sealed interface**

```java
public sealed interface JudgmentPayload
    permits JudgmentPayload.BindingPayload, JudgmentPayload.GatePayload {

    record BindingPayload(
        Map<String, Object> inputData,
        @Nullable String payloadTypeName,
        @Nullable String resolutionTypeName,
        @Nullable Set<String> resolvedCandidateGroups,
        @Nullable Set<String> resolvedCandidateUsers,
        @Nullable Instant caseBudgetDeadline,
        @Nullable Instant expiresAtDeadline,
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

- [ ] **Step 4: Create JudgmentRequest, JudgmentScheduler, PendingJudgment, NoOpJudgmentScheduler**

- [ ] **Step 5: Run tests**

Run: `mvn test -pl common -Dtest=JudgmentRequestTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Extend DefaultWorkerSpiImplementationsTest**

Add test verifying `NoOpJudgmentScheduler` is `@DefaultBean @ApplicationScoped`.

- [ ] **Step 7: Verify with ide_diagnostics, commit**

```bash
git commit -m "feat(#996): JudgmentScheduler SPI — unified scheduling with sealed JudgmentPayload

Refs #996"
```

### Task 3: JudgmentVerifier SPI and EvidencePresenceVerifier (engine#997)

**Files:**
- Create: `api/src/main/java/io/casehub/api/spi/JudgmentVerifier.java`
- Create: `api/src/main/java/io/casehub/api/spi/VerificationResult.java`
- Create: `api/src/main/java/io/casehub/api/spi/VerificationContext.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/worker/NoOpJudgmentVerifier.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/worker/EvidencePresenceVerifier.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/routing/EngineStrategyResolver.java` — add `Instance<JudgmentVerifier>`
- Test: `api/src/test/java/io/casehub/api/spi/VerificationResultTest.java`
- Test: `runtime/src/test/java/io/casehub/engine/internal/worker/EvidencePresenceVerifierTest.java`

**Interfaces:**
- Consumes: `JudgmentResponse`, `Evidence`, `EvidenceRequirement` from Task 1; `JudgmentPayload` from Task 2
- Produces: `JudgmentVerifier.verify(JudgmentResponse, VerificationContext) → VerificationResult`

- [ ] **Step 1: Write EvidencePresenceVerifier test**

```java
@Test
void acceptsWhenAllRequiredEvidencePresent() {
    var requirements = List.of(
        new EvidenceRequirement("rationale", EvidenceType.REASONING, true),
        new EvidenceRequirement("ref", EvidenceType.REFERENCE, false));
    var response = new JudgmentResponse(
        Map.of("decision", "approve"),
        List.of(new Evidence("rationale", EvidenceType.REASONING, "Because...", null)),
        new CallerIdentity("user-1", "human", null),
        Instant.now());
    var target = JudgmentTarget.forHuman().prompt("Review")
        .evidence("rationale", EvidenceType.REASONING, true)
        .evidence("ref", EvidenceType.REFERENCE, false)
        .build();
    var ctx = new VerificationContext(UUID.randomUUID(), "t1", "b1", target, null, null);

    var result = new EvidencePresenceVerifier().verify(response, ctx);
    assertInstanceOf(VerificationResult.Accepted.class, result);
}

@Test
void rejectsWhenRequiredEvidenceMissing() {
    var response = new JudgmentResponse(
        Map.of("decision", "approve"),
        List.of(),  // no evidence
        new CallerIdentity("user-1", "human", null),
        Instant.now());
    var target = JudgmentTarget.forHuman().prompt("Review")
        .evidence("rationale", EvidenceType.REASONING, true)
        .build();
    var ctx = new VerificationContext(UUID.randomUUID(), "t1", "b1", target, null, null);

    var result = new EvidencePresenceVerifier().verify(response, ctx);
    assertInstanceOf(VerificationResult.InsufficientEvidence.class, result);
    var ie = (VerificationResult.InsufficientEvidence) result;
    assertTrue(ie.missingRequirements().contains("rationale"));
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Create JudgmentVerifier, VerificationResult, VerificationContext**

- [ ] **Step 4: Create EvidencePresenceVerifier and NoOpJudgmentVerifier**

`EvidencePresenceVerifier` (id=`"evidence-presence"`, `@ApplicationScoped`): iterates `target.evidenceRequirements()`, checks `required` ones have matching `response.evidence()` by name. Returns `Accepted` or `InsufficientEvidence(feedback, missingNames)`.

`NoOpJudgmentVerifier` (`@DefaultBean`, id=`"none"`): returns `Accepted` always.

- [ ] **Step 5: Add Instance<JudgmentVerifier> to EngineStrategyResolver**

Use `ide_edit_member` on the constructor to add `@Any Instance<JudgmentVerifier> judgmentVerifiers` parameter. Register in the constructor body with `registerStrategies(judgmentVerifiers)`.

- [ ] **Step 6: Run tests, verify with ide_diagnostics, commit**

```bash
git commit -m "feat(#997): JudgmentVerifier SPI — post-response verification with EvidencePresenceVerifier

Refs #997"
```

### Task 4: YAML mapper and minimal example

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/yaml/CaseDefinitionYamlMapper.java` — add `judgment:` block parsing
- Create: `examples/judgment-basic/` — minimal example with `judgment:` YAML
- Test: `runtime/src/test/java/io/casehub/engine/internal/yaml/JudgmentYamlMappingTest.java`

**Interfaces:**
- Consumes: `JudgmentTarget`, `CallerConfig`, `EvidenceRequirement`, `EvidenceType` from Task 1
- Produces: YAML `judgment:` block parsing in `CaseDefinitionYamlMapper`

- [ ] **Step 1: Write YAML mapping test**

```java
@Test
void parsesJudgmentYamlBlock() {
    String yaml = """
        bindings:
          - name: review
            judgment:
              prompt: "Review the analysis"
              caller:
                type: human
                candidateGroups: [reviewers]
                title: "Analysis Review"
                outcomes: [approve, reject]
              evidence:
                - name: rationale
                  type: REASONING
                  required: true
              verifier: evidence-presence
              expiresIn: PT24H
            on:
              contextChange:
                condition: ".analysis != null"
        """;
    // Parse and assert JudgmentTarget is created with correct CallerConfig.Human
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Add judgment block parsing to CaseDefinitionYamlMapper**

Study `convertHumanTaskTarget()` method in the existing mapper via `ide_file_structure`. Follow the same pattern for `convertJudgmentTarget()`: parse `caller.type` to select CallerConfig variant, parse `evidence` array to `List<EvidenceRequirement>`, parse `verifier`/`escalator`/`trustPolicy` as strategy IDs.

- [ ] **Step 4: Create minimal example**

Create `examples/judgment-basic/src/main/resources/case-definition.yaml` with a simple case using one `judgment:` binding with human caller.

- [ ] **Step 5: Run tests, commit**

```bash
git commit -m "feat(#995): YAML judgment: block parsing and basic example

Refs #995"
```

---

## Batch 2: Engine Handler Wiring + Ledger Events

### Task 5: Judgment ledger events (engine#998)

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/internal/history/CaseHubEventType.java` — add JUDGMENT_* event types
- Test: `common/src/test/java/io/casehub/engine/internal/history/CaseHubEventTypeTest.java` (extend)

**Interfaces:**
- Produces: `JUDGMENT_YIELDED`, `JUDGMENT_RESPONDED`, `JUDGMENT_VERIFIED`, `JUDGMENT_ESCALATED`, `JUDGMENT_REJECTED`, `JUDGMENT_CANCELLED`, `JUDGMENT_EXPIRED` event type constants

- [ ] **Step 1: Add event types to CaseHubEventType**

Use `ide_insert_member` to add the 7 new event type constants. Follow existing naming convention.

- [ ] **Step 2: Write test verifying all judgment event types exist**

- [ ] **Step 3: Run tests, commit**

```bash
git commit -m "feat(#998): judgment ledger event types — YIELDED, RESPONDED, VERIFIED, ESCALATED, REJECTED, CANCELLED, EXPIRED

Refs #998"
```

### Task 6: JudgmentEscalator SPI (engine#999)

**Files:**
- Create: `api/src/main/java/io/casehub/api/spi/JudgmentEscalator.java`
- Create: `api/src/main/java/io/casehub/api/spi/EscalationDecision.java`
- Create: `api/src/main/java/io/casehub/api/spi/EscalationContext.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/worker/DefaultJudgmentEscalator.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/routing/EngineStrategyResolver.java` — add `Instance<JudgmentEscalator>`
- Test: `runtime/src/test/java/io/casehub/engine/internal/worker/DefaultJudgmentEscalatorTest.java`

**Interfaces:**
- Consumes: `JudgmentResponse`, `VerificationResult`, `JudgmentTarget`, `CallerConfig` from Tasks 1-3
- Produces: `JudgmentEscalator.escalate(EscalationContext) → EscalationDecision`

- [ ] **Step 1: Write DefaultJudgmentEscalator test**

```java
@Test
void reYieldsOnFirstAttempt() {
    var ctx = new EscalationContext(UUID.randomUUID(), "t1", "b1",
        JudgmentTarget.forHuman().prompt("Review").build(),
        someResponse, someInsufficientEvidence, 1, 3);
    var result = new DefaultJudgmentEscalator().escalate(ctx);
    assertInstanceOf(EscalationDecision.ReYield.class, result);
}

@Test
void faultsWhenMaxAttemptsReached() {
    var ctx = new EscalationContext(UUID.randomUUID(), "t1", "b1",
        JudgmentTarget.forHuman().prompt("Review").build(),
        someResponse, someRejection, 3, 3);
    var result = new DefaultJudgmentEscalator().escalate(ctx);
    assertInstanceOf(EscalationDecision.Fault.class, result);
}
```

- [ ] **Step 2-5: Implement, test, commit**

```bash
git commit -m "feat(#999): JudgmentEscalator SPI — verification failure handling with DefaultJudgmentEscalator

Refs #999"
```

### Task 7: CaseContextChangedEventHandler judgment dispatch

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java` — add `case JudgmentTarget` arm, `publishJudgment()` method
- Create: `common/src/main/java/io/casehub/engine/common/internal/event/JudgmentScheduleEvent.java`
- Test: `runtime/src/test/java/io/casehub/engine/internal/engine/handler/JudgmentDispatchTest.java`

**Interfaces:**
- Consumes: `JudgmentTarget`, `JudgmentScheduler`, `JudgmentRequest`, `JudgmentPayload.BindingPayload` from Tasks 1-2
- Produces: Judgment dispatch via `JudgmentScheduler.schedule()` when a JudgmentTarget binding fires

- [ ] **Step 1: Write test for judgment binding dispatch**

Create a `@QuarkusTest` with a `RecordingJudgmentScheduler` (`@Alternative @Priority(1)`) that captures `JudgmentRequest`. Define a case with a `judgment:` binding, start the case, signal context to trigger the binding, assert the scheduler received the request with correct payload.

- [ ] **Step 2-6: Implement dispatch, test, commit**

The `publishJudgment()` method follows the same structure as `publishHumanTaskSchedule()`:
1. Evaluate `inputMapping` against working layer
2. If `CallerConfig.Human`: resolve candidate sets, apply `HumanTaskRoutingStrategy`
3. Build `JudgmentRequest` with `BindingPayload`
4. Call `judgmentScheduler.schedule(request)`

```bash
git commit -m "feat(#996): wire JudgmentTarget dispatch in CaseContextChangedEventHandler

Refs #996"
```

### Task 8: JudgmentResponseHandler and WorkflowExecutionCompletedHandler gate wiring

**Files:**
- Create: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/JudgmentResponseHandler.java`
- Create: `common/src/main/java/io/casehub/engine/common/internal/event/JudgmentResponseEvent.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java` — update `handleGate()` to use `JudgmentScheduler`
- Test: `runtime/src/test/java/io/casehub/engine/internal/engine/handler/JudgmentResponseHandlerTest.java`

**Interfaces:**
- Consumes: `JudgmentVerifier`, `JudgmentEscalator`, `JudgmentScheduler`, `PendingJudgment` from Tasks 2-3, 6
- Produces: `JudgmentResponseHandler` — processes responses with verification, origin-specific data flows, escalation

- [ ] **Step 1: Write tests for binding-origin and gate-origin response flows**

Two test scenarios:
1. Binding origin: response verified → accepted → outputMapping applied → CONTEXT_CHANGED published
2. Gate origin: response verified → accepted → deferred output re-fired via `WorkflowExecutionCompleted.approved()`

- [ ] **Step 2-6: Implement response handler with origin-specific flows, commit**

```bash
git commit -m "feat(#996): JudgmentResponseHandler — unified response processing with origin-specific flows

Refs #996, Refs #998"
```

---

## Batch 3: Qhorus Integration

### Task 9: JUDGMENT commitment type (qhorus#411)

**Files:**
- Modify: `qhorus/api/src/main/java/.../SpeechActType.java` — add JUDGMENT
- Modify: `qhorus/runtime/src/main/java/.../CommitmentFactory.java` — JUDGMENT lifecycle
- Test: `qhorus/runtime/src/test/java/.../JudgmentCommitmentTest.java`

**Interfaces:**
- Produces: JUDGMENT speech act type, commitment create/fulfill/violate lifecycle

- [ ] **Step 1-5: Add JUDGMENT type, lifecycle tests, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/qhorus commit -m "feat(qhorus#411): JUDGMENT commitment type in speech act taxonomy

Refs qhorus#411"
```

### Task 10: E4 trust routing for judgment callers (qhorus#412)

**Files:**
- Modify: qhorus `CapabilityRouter` — trust-scored caller selection for judgment requests
- Test: judgment caller selection integration test

**Interfaces:**
- Consumes: `JudgmentRequest.target().trustPolicy()` from Task 1
- Produces: Trust-filtered caller selection for judgment scheduling

- [ ] **Step 1-5: Implement trust routing, tests, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/qhorus commit -m "feat(qhorus#412): E4 trust-scored routing for judgment callers

Refs qhorus#412"
```

---

## Batch 4: Blocks Integration

### Task 11: LLM JudgmentScheduler (blocks#171)

**Files:**
- Create: `blocks/engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/LlmJudgmentScheduler.java`
- Test: `blocks/engine-adapter/src/test/java/.../LlmJudgmentSchedulerTest.java`

**Interfaces:**
- Consumes: `JudgmentScheduler` SPI, `CallerConfig.Llm`, `ChatModelProvider`
- Produces: LLM-backed judgment scheduling — prompts LLM, parses response to JudgmentResponse

- [ ] **Step 1-5: Implement LLM scheduler, tests, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(blocks#171): LlmJudgmentScheduler — LLM-as-caller for judgment yields

Refs blocks#171"
```

### Task 12: Verification strategies (blocks#172)

**Files:**
- Create: `blocks/engine-adapter/src/main/java/.../SchemaValidationVerifier.java`
- Create: `blocks/engine-adapter/src/main/java/.../ConsensusVerifier.java`
- Create: `blocks/engine-adapter/src/main/java/.../LlmEvaluationVerifier.java`
- Tests for each verifier

**Interfaces:**
- Consumes: `JudgmentVerifier` SPI from Task 3
- Produces: Three verification strategies (schema, consensus, LLM evaluation)

- [ ] **Step 1-8: Implement three verifiers, tests, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(blocks#172): verification strategies — schema, consensus, LLM evaluation

Refs blocks#172"
```

### Task 13: Yield-aware pattern variants (blocks#173)

**Files:**
- Modify: blocks pattern definitions — SUPERVISOR, PIPELINE, DEBATE with judgment configuration
- Test: pattern integration tests with judgment steps

**Interfaces:**
- Consumes: `JudgmentTarget` from Task 1, pattern infrastructure from blocks
- Produces: Pattern YAML `judgment:` block support, separate binding decomposition for v1

- [ ] **Step 1-5: Implement pattern judgment support, tests, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(blocks#173): yield-aware patterns — SUPERVISOR, PIPELINE, DEBATE with judgment steps

Refs blocks#173"
```

---

## Batch 5: DagNode Integration + Compliance

### Task 14: DagNode judgment integration (engine#1000)

**Files:**
- Modify: `api/src/main/java/io/casehub/engine/plan/DagNode.java` — add `@Nullable JudgmentTarget judgment` field
- Modify: `common/src/main/java/io/casehub/engine/plan/DagDriver.java` — dispatch judgment on yield nodes (v1: separate binding decomposition)
- Test: `common/src/test/java/io/casehub/engine/plan/DagNodeJudgmentTest.java`

- [ ] **Step 1-5: Add judgment field to DagNode, backward-compatible constructor, tests, commit**

```bash
git commit -m "feat(#1000): DagNode judgment field — yield step support in DAG execution

Refs #1000"
```

### Task 15: E5 compliance evidence (qhorus#413)

**Files:**
- Modify: qhorus compliance report generator — include JUDGMENT_* EventLog entries
- Test: compliance report with judgment events

- [ ] **Step 1-5: Add judgment events to compliance reports, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/qhorus commit -m "feat(qhorus#413): judgment events in E5 compliance evidence reports

Refs qhorus#413"
```

### Task 16: E7 formal verification (qhorus#414)

**Files:**
- Modify: qhorus formal verification — temporal invariants for JUDGMENT commitments
- Test: liveness/safety/fairness property tests

- [ ] **Step 1-5: Add judgment temporal invariants, commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/qhorus commit -m "feat(qhorus#414): formal verification invariants for judgment commitments

Refs qhorus#414"
```

---

## Batch 6: Migration and Cleanup

### Task 17: Delete HumanTaskTarget and old scheduling infrastructure

**Files:**
- Delete: `api/src/main/java/io/casehub/api/model/HumanTaskTarget.java` (use `ide_refactor_safe_delete`)
- Delete: `common/src/main/java/io/casehub/engine/common/spi/HumanTaskScheduler.java`
- Delete: `common/src/main/java/io/casehub/engine/common/spi/HumanTaskScheduleRequest.java`
- Delete: `common/src/main/java/io/casehub/engine/common/spi/ActionGateScheduler.java`
- Delete: `common/src/main/java/io/casehub/engine/common/spi/ActionGateScheduleRequest.java`
- Delete: `runtime/src/main/java/io/casehub/engine/internal/worker/NoOpActionGateScheduler.java`
- Delete: `api/src/main/java/io/casehub/api/spi/OversightGateService.java`
- Modify: `api/src/main/java/io/casehub/api/model/BindingTarget.java` — remove HumanTaskTarget from permits
- Modify: All references to deleted types (use `ide_find_references` before deletion)

- [ ] **Step 1: Use ide_find_references on each type to enumerate all usages**
- [ ] **Step 2: Update all usages to use JudgmentTarget equivalents**
- [ ] **Step 3: Run ide_refactor_safe_delete on each deleted type**
- [ ] **Step 4: Run full test suite**

```bash
mvn clean test -DfailIfNoTests=false
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(#994): delete HumanTaskTarget, HumanTaskScheduler, ActionGateScheduler — replaced by JudgmentTarget

Closes #994"
```

### Task 18: Update CloudEvent module

**Files:**
- Create: `work-cloudevent/src/main/java/io/casehub/engine/work/cloudevent/CloudEventJudgmentScheduler.java`
- Delete: `work-cloudevent/src/main/java/io/casehub/engine/work/cloudevent/CloudEventHumanTaskScheduler.java`
- Delete: `work-cloudevent/src/main/java/io/casehub/engine/work/cloudevent/CloudEventActionGateScheduler.java`
- Modify: `work-cloudevent/src/main/java/io/casehub/engine/work/cloudevent/WorkItemLifecycleCloudEventConsumer.java`
- Test: `work-cloudevent/src/test/java/.../CloudEventJudgmentSchedulerTest.java`

- [ ] **Step 1-5: Create unified CloudEvent scheduler, delete old, test, commit**

```bash
git commit -m "feat(#996): CloudEventJudgmentScheduler — unified CloudEvent judgment scheduling

Refs #996"
```

### Task 19: Update consumer examples

**Files:**
- Modify: All example YAML files with `humanTask:` → `judgment:` syntax
- Modify: All example Java DSL with `.humanTask(HumanTaskTarget...)` → `.judgment(JudgmentTarget...)`

- [ ] **Step 1: Find all humanTask references across examples**
- [ ] **Step 2: Update YAML and Java DSL**
- [ ] **Step 3: Verify examples compile**
- [ ] **Step 4: Commit**

```bash
git commit -m "chore(#994): migrate all examples from humanTask to judgment syntax

Refs #994"
```

---

## References

- [2026-08-26-governed-yield-design.md](../specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md) — design spec
- [BindingTarget.java](../../api/src/main/java/io/casehub/api/model/BindingTarget.java) — sealed hierarchy
- [HumanTaskTarget.java](../../api/src/main/java/io/casehub/api/model/HumanTaskTarget.java) — type being replaced (study builder pattern)
- [CaseContextChangedEventHandler.java](../../runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java) — dispatch switch
- [WorkflowExecutionCompletedHandler.java](../../runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java) — gate path
- [CaseDefinitionYamlMapper.java](../../runtime/src/main/java/io/casehub/engine/internal/yaml/CaseDefinitionYamlMapper.java) — YAML parsing
- [GE-20260729-172d18] — Evolve existing SPIs
- [GE-20260521-a0f5a6] — HumanTaskScheduleHandler PlanItem timing gotcha
- [GE-20260704-d6aacc] — EngineStrategyResolver Instance<> discovery
- [PP-20260601-81b9e5] — SPI evolution default methods
- [PP-20260612-a2ef10] — @RiskClassifier CDI qualifier pattern
- [PP-20260722-60e519] — Cross-repo source verification
- GitHub: engine#994, #995, #996, #997, #998, #999, #1000; blocks#171, #172, #173; qhorus#411, #412, #413, #414
