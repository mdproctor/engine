# Yield-Aware Pattern Variants — Design Spec

**Issue:** casehubio/blocks#173
**Branch:** issue-994-governed-yield
**Date:** 2026-08-28
**Depends on:** casehubio/engine#994 (governed yield infrastructure)

---

## Problem

Blocks patterns (SUPERVISOR, DEBATE, PIPELINE) run agent orchestration loops via `OrchestratedDriver`. Agents execute, results are aggregated, and the loop terminates based on a condition. None of this is governed — there are no evidence requirements, no verification, no audit trail for the decisions made within the loop.

The governed yield infrastructure (engine#994) provides `JudgmentTarget`, `JudgmentVerifier`, `Evidence`, and `CallerConfig` — but these are designed for case-level bindings that yield to external callers. Patterns run within a single `PatternWorkerFunctionHandler` invocation and can't yield mid-execution to case bindings.

**Yield-aware patterns formalize the judgment step within the pattern execution loop.** The supervisor's review becomes a governed judgment. The debate judge's convergence decision becomes a governed judgment. The pipeline quality gate becomes a governed judgment. Same loop, but now with evidence, verification, and auditability.

---

## Architecture

### Judgment Phase in the Five-Phase Loop

The existing loop is: **route → activate/dispatch → aggregate → terminate**.

Yield-aware patterns add a judgment phase: **route → activate/dispatch → aggregate → judge → terminate**.

`ExecutionModel<T>` gains a 12th component:

```java
@Nullable JudgmentPhase<T> judgment
```

When null (default), the loop behaves exactly as today. When present, `AbstractExecutionDriver.executeIteration()` calls it after aggregation and before termination.

### JudgmentPhase SPI

```java
// blocks/src/main/java/io/casehub/blocks/agentic/judgment/JudgmentPhase.java
@FunctionalInterface
public interface JudgmentPhase<T> {
    JudgmentDecision evaluate(JudgmentContext<T> context);
}
```

**JudgmentContext** carries everything the judgment caller needs:

```java
public record JudgmentContext<T>(
    T executionContext,
    List<AgentResult> iterationResults,
    AggregationResult aggregationResult,
    int iteration,
    @Nullable String previousFeedback  // from prior rejection
) {}
```

**JudgmentDecision** — sealed result:

```java
public sealed interface JudgmentDecision
    permits JudgmentDecision.Approved,
            JudgmentDecision.Rejected,
            JudgmentDecision.Escalated {

    record Approved(
        Object result,
        List<Evidence> evidence,
        CallerIdentity caller
    ) implements JudgmentDecision {}

    record Rejected(
        String feedback,
        List<Evidence> evidence,
        CallerIdentity caller
    ) implements JudgmentDecision {}

    record Escalated(
        String reason,
        CallerIdentity caller
    ) implements JudgmentDecision {}
}
```

### Loop Integration

In `AbstractExecutionDriver.executeIteration()`, after the aggregation phase and before termination:

```java
// Phase 3.5: Judgment (if configured)
if (model.judgment() != null) {
    var judgmentCtx = new JudgmentContext<>(
        context, results, aggregated, iteration, lastJudgmentFeedback);
    var decision = model.judgment().evaluate(judgmentCtx);

    notifyJudgment(model, decision);

    switch (decision) {
        case JudgmentDecision.Approved approved -> {
            lastJudgmentFeedback = null;
            // Continue to termination evaluation
        }
        case JudgmentDecision.Rejected rejected -> {
            lastJudgmentFeedback = rejected.feedback();
            return null; // Re-iterate (null = continue loop)
        }
        case JudgmentDecision.Escalated escalated -> {
            return new ExecutionResult.Escalated(escalated.reason());
        }
    }
}
```

`lastJudgmentFeedback` is a new field on `AbstractExecutionDriver` — injected into the next iteration's `JudgmentContext` so agents can receive feedback from the judgment.

**Rejection re-iterates** — bounded by the existing `maxIterations` termination condition. No infinite loops. When maxIterations is reached, termination fires regardless of judgment state.

### Built-in JudgmentPhase Implementations

**LlmJudgmentPhase** — calls ChatModel inline within the pattern loop:

```java
// blocks engine-adapter: io.casehub.engine.agentic.judgment.LlmJudgmentPhase
public class LlmJudgmentPhase<T> implements JudgmentPhase<T> {
    private final ChatModelProvider chatModelProvider;
    private final PatternJudgmentConfig config;
    private final JudgmentVerifier verifier;  // nullable

    @Override
    public JudgmentDecision evaluate(JudgmentContext<T> context) {
        // 1. Build prompt from config + aggregation results + previous feedback
        // 2. Call ChatModel
        // 3. Parse response into JudgmentResponse
        // 4. If verifier configured, verify
        // 5. Return Approved/Rejected/Escalated
    }
}
```

**A2AJudgmentPhase** — calls an A2A endpoint inline:

```java
// blocks engine-adapter: io.casehub.engine.agentic.judgment.A2AJudgmentPhase
public class A2AJudgmentPhase<T> implements JudgmentPhase<T> {
    private final A2AClient client;
    private final PatternJudgmentConfig config;
    private final JudgmentVerifier verifier;

    @Override
    public JudgmentDecision evaluate(JudgmentContext<T> context) {
        // 1. Build A2A message from aggregation results
        // 2. Call A2AClient.send() synchronously
        // 3. Parse response
        // 4. Verify if configured
        // 5. Return decision
    }
}
```

### Pattern-Type Defaults

Each pattern type has a default judgment mode. The `mode` field on the judgment config overrides when explicitly set.

| Pattern Type | Default Mode | Judgment Replaces |
|-------------|-------------|-------------------|
| SUPERVISOR | `integrated` | Supervisor's review — the supervisor agent IS the judgment caller |
| DEBATE | `integrated` | Judge's convergence — the judge IS the judgment caller |
| PIPELINE/SEQUENCE | `post-step` | Quality gate after each step |
| PARALLEL | `post-step` | Gate after all agents complete |
| LOOP | `post-step` | Gate after each iteration |
| VOTING | `post-step` | Gate after vote aggregation |

**`integrated` mode:** The judgment phase replaces the pattern-specific review mechanism. For SUPERVISOR, this means the judgment caller IS the supervisor — no separate supervisor agent dispatch. For DEBATE, the judgment caller replaces `JudgeConvergence` as the termination mechanism.

**`post-step` mode:** The judgment phase runs after agents execute and results aggregate. Agents and judgment are separate — agents do work, judgment evaluates the work.

### PatternJudgmentConfig

Configuration record parsed from YAML, carried on `PatternWorkerFunction`:

```java
// blocks engine-adapter
public record PatternJudgmentConfig(
    String prompt,
    CallerConfig callerConfig,
    String verifierStrategy,        // NamedStrategy ID for JudgmentVerifier
    List<EvidenceRequirement> evidenceRequirements,
    JudgmentMode mode,              // nullable — defaults per PatternType
    boolean afterStep               // PIPELINE: yield after each step (default true)
) {
    public enum JudgmentMode {
        INTEGRATED,  // judgment replaces pattern review
        POST_STEP    // judgment after agent execution
    }
}
```

### YAML Schema

**SUPERVISOR with judgment:**
```yaml
workers:
  - name: analysis-team
    capabilities: [analysis]
    pattern:
      type: SUPERVISOR
      iterations: 3
      judgment:
        prompt: "Review the analysis output for completeness and accuracy"
        caller:
          type: llm
          modelName: claude-sonnet-4-20250514
        verifier: schema-validation
        evidence:
          - name: assessment
            type: REASONING
            required: true
```

**PIPELINE with quality gates:**
```yaml
workers:
  - name: data-pipeline
    capabilities: [process]
    pattern:
      type: SEQUENCE
      judgment:
        afterStep: true
        prompt: "Verify step output meets quality standards"
        caller:
          type: llm
        verifier: evidence-presence
```

**DEBATE with judgment:**
```yaml
workers:
  - name: design-review
    capabilities: [review]
    pattern:
      type: DEBATE
      judgment:
        prompt: "Evaluate positions and determine consensus"
        caller:
          type: llm
        evidence:
          - name: rationale
            type: REASONING
            required: true
```

**Override mode:**
```yaml
pattern:
  type: SUPERVISOR
  judgment:
    mode: post-step  # override: separate judgment after supervisor review
    prompt: "Validate the supervisor's assessment"
    caller:
      type: a2a
      endpoint: https://validator.example.com
```

### Wiring

**PatternWorkerFunctionProvider** — parses `judgment:` block, creates `PatternJudgmentConfig`, stores on `PatternWorkerFunction`.

**PatternWorkerFunctionHandler** — before executing the pattern, resolves `PatternJudgmentConfig` into a concrete `JudgmentPhase`:
1. Read `callerConfig` type
2. LLM → create `LlmJudgmentPhase` (needs `ChatModelProvider`)
3. A2A → create `A2AJudgmentPhase` (needs `A2AClientRegistry`)
4. Human → log warning, skip (not supported in v1)
5. Resolve `JudgmentVerifier` by strategy ID via `EngineStrategyResolver`
6. Inject `JudgmentPhase` into `ExecutionModel`

**AbstractPatternBuilder** — gains `.judgment(PatternJudgmentConfig)` for the Java DSL path.

### ExecutionEventListener

New callback:

```java
default void onJudgment(JudgmentDecision decision) {}
```

Called by `AbstractExecutionDriver.notifyJudgment()` after the judgment phase. Listeners can observe but not influence the decision.

### Audit Trail

Pattern-level judgment is audited via the existing `ExecutionEventListener` mechanism and `PatternWorkerFunctionHandler`'s metadata. The handler adds judgment metadata to `HandlerResult.protocolMetadata()`:

- `patternJudgmentCount` — total judgment calls in the execution
- `patternJudgmentApproved` — count of Approved decisions
- `patternJudgmentRejected` — count of Rejected decisions (with feedback)
- `patternJudgmentEscalated` — count of Escalated decisions

These flow into the `WORKER_EXECUTION_COMPLETED` EventLog metadata via the existing `WorkflowExecutionCompletedHandler` pipeline.

### Scope Boundaries

**In scope:**
- `JudgmentPhase<T>` SPI in blocks core
- `JudgmentDecision` sealed type in blocks core
- `JudgmentContext<T>` context record in blocks core
- `LlmJudgmentPhase` in engine-adapter
- `A2AJudgmentPhase` in engine-adapter
- `PatternJudgmentConfig` in engine-adapter
- YAML parsing in `PatternWorkerFunctionProvider`
- `AbstractPatternBuilder.judgment()` method
- `ExecutionModel` 12th component
- `AbstractExecutionDriver` loop integration
- `ExecutionEventListener.onJudgment()` callback
- Tests for SUPERVISOR, DEBATE, SEQUENCE patterns with judgment

**Out of scope (deferred):**
- Human callers in patterns (requires mid-pattern yield)
- Mid-DagDriver yield (separate from pattern loop — engine#1000 handles DagNode metadata)
- Consensus verification (multiple callers agreeing)
- Pattern-level EventLog emission (patterns run inside a worker — EventLog is at the case level)

---

## References

- `docs/specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md` — parent spec, §9 (yield step in DagPlan), §12 (yield-aware pattern variants)
- `blocks/src/main/java/io/casehub/blocks/agentic/model/AbstractExecutionDriver.java` — five-phase loop
- `blocks/src/main/java/io/casehub/blocks/agentic/model/ExecutionModel.java` — 11-component record
- `blocks/src/main/java/io/casehub/blocks/agentic/pattern/SupervisorBuilder.java` — supervisor defaults
- `blocks/src/main/java/io/casehub/blocks/agentic/pattern/DebateBuilder.java` — JudgeConvergence
- `engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/LlmJudgmentScheduler.java` — LLM caller pattern
- `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionHandler.java` — handler pipeline
- `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionProvider.java` — YAML parsing
- `api/src/main/java/io/casehub/api/model/JudgmentTarget.java` — judgment type system
- `api/src/main/java/io/casehub/api/spi/JudgmentVerifier.java` — verification SPI
- `docs/protocols/casehub/plan-type-module-boundary.md` (PP-20260727-5267d2) — plan-def types in api, execution types in common
