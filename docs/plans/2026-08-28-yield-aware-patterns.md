# Yield-Aware Pattern Variants Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** blocks#173 — yield-aware pattern variants
**Issue group:** engine#994 (parent), blocks#173

**Goal:** Add a judgment phase to the blocks pattern execution loop so SUPERVISOR, DEBATE, and PIPELINE patterns gain evidence requirements, verification, and auditability.

**Architecture:** `ExecutionModel<T>` gains a nullable `JudgmentPhase<T>` as its 12th component. `AbstractExecutionDriver.executeIteration()` calls it between aggregation and termination. `LlmJudgmentPhase` calls ChatModel inline. `PatternWorkerFunctionProvider` parses `judgment:` YAML blocks. Rejection re-iterates with feedback.

**Tech Stack:** Java 21, blocks core (`io.casehub.blocks.agentic`), engine-adapter (`io.casehub.engine.agentic`), engine-api judgment types (`JudgmentTarget`, `JudgmentVerifier`, `Evidence`, `CallerConfig`), LangChain4j ChatModel.

## Global Constraints

- All new types in blocks core use `io.casehub.blocks.agentic.judgment` package
- Engine-adapter implementations use `io.casehub.engine.agentic.judgment` package (existing)
- `JudgmentPhase<T>` is a blocks-core SPI — no engine dependency
- `LlmJudgmentPhase` and `A2AJudgmentPhase` are engine-adapter implementations — they depend on engine-api types
- Backward compatibility: all existing constructors pass null for the judgment field
- Tests use Mockito for ChatModel/A2AClient mocking — same pattern as existing tests

---

## Batch 1: Foundation — JudgmentPhase SPI and ExecutionModel Integration

### Task 1: JudgmentPhase SPI, JudgmentDecision, JudgmentContext in blocks core

**Files:**
- Create: `blocks/src/main/java/io/casehub/blocks/agentic/judgment/JudgmentPhase.java`
- Create: `blocks/src/main/java/io/casehub/blocks/agentic/judgment/JudgmentDecision.java`
- Create: `blocks/src/main/java/io/casehub/blocks/agentic/judgment/JudgmentContext.java`
- Test: `blocks/src/test/java/io/casehub/blocks/agentic/judgment/JudgmentDecisionTest.java`

**Interfaces:**
- Produces: `JudgmentPhase<T>.evaluate(JudgmentContext<T>) → JudgmentDecision`
- Produces: `JudgmentDecision` sealed: `Approved(Object result, List<Evidence> evidence, CallerIdentity caller)`, `Rejected(String feedback, List<Evidence> evidence, CallerIdentity caller)`, `Escalated(String reason, CallerIdentity caller)`
- Produces: `JudgmentContext<T>(T executionContext, List<AgentResult> iterationResults, AggregationResult aggregationResult, int iteration, @Nullable String previousFeedback)`

**Note:** `JudgmentDecision` references `Evidence` and `CallerIdentity` from engine-api. These are simple records — blocks core already depends on engine-api (via `DecompositionStrategy`, `TaskNode`). Check `blocks/pom.xml` for existing `casehub-engine-api` dependency.

- [ ] **Step 1: Write tests for JudgmentDecision sealed type**

```java
package io.casehub.blocks.agentic.judgment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgmentDecisionTest {

    @Test
    void approved_carriesResultAndEvidence() {
        var evidence = List.of(new Evidence("rationale", io.casehub.api.model.EvidenceType.REASONING, "looks good", null));
        var caller = new CallerIdentity("claude", "llm", null);
        var decision = new JudgmentDecision.Approved("output", evidence, caller);

        assertThat(decision.result()).isEqualTo("output");
        assertThat(decision.evidence()).hasSize(1);
        assertThat(decision.caller().callerId()).isEqualTo("claude");
    }

    @Test
    void rejected_carriesFeedback() {
        var decision = new JudgmentDecision.Rejected("needs more detail", List.of(), new CallerIdentity("claude", "llm", null));
        assertThat(decision.feedback()).isEqualTo("needs more detail");
    }

    @Test
    void escalated_carriesReason() {
        var decision = new JudgmentDecision.Escalated("beyond my expertise", new CallerIdentity("claude", "llm", null));
        assertThat(decision.reason()).isEqualTo("beyond my expertise");
    }

    @Test
    void sealedPatternMatch_coversAllCases() {
        JudgmentDecision decision = new JudgmentDecision.Approved("ok", List.of(), new CallerIdentity("test", "llm", null));
        String result = switch (decision) {
            case JudgmentDecision.Approved a -> "approved: " + a.result();
            case JudgmentDecision.Rejected r -> "rejected: " + r.feedback();
            case JudgmentDecision.Escalated e -> "escalated: " + e.reason();
        };
        assertThat(result).isEqualTo("approved: ok");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl blocks -Dtest=JudgmentDecisionTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create JudgmentDecision sealed type**

```java
package io.casehub.blocks.agentic.judgment;

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.Evidence;
import java.util.List;

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

- [ ] **Step 4: Create JudgmentContext record**

```java
package io.casehub.blocks.agentic.judgment;

import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.aggregation.AggregationResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record JudgmentContext<T>(
    T executionContext,
    List<AgentResult> iterationResults,
    AggregationResult aggregationResult,
    int iteration,
    @Nullable String previousFeedback
) {}
```

- [ ] **Step 5: Create JudgmentPhase functional interface**

```java
package io.casehub.blocks.agentic.judgment;

@FunctionalInterface
public interface JudgmentPhase<T> {
    JudgmentDecision evaluate(JudgmentContext<T> context);
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl blocks -Dtest=JudgmentDecisionTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks add blocks/src/main/java/io/casehub/blocks/agentic/judgment/ blocks/src/test/java/io/casehub/blocks/agentic/judgment/
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(#173): JudgmentPhase SPI, JudgmentDecision, JudgmentContext in blocks core

Refs casehubio/engine#994"
```

### Task 2: ExecutionModel gains JudgmentPhase + AbstractExecutionDriver integration

**Files:**
- Modify: `blocks/src/main/java/io/casehub/blocks/agentic/model/ExecutionModel.java`
- Modify: `blocks/src/main/java/io/casehub/blocks/agentic/model/AbstractExecutionDriver.java`
- Modify: `blocks/src/main/java/io/casehub/blocks/agentic/model/ExecutionEventListener.java`
- Test: `blocks/src/test/java/io/casehub/blocks/agentic/model/ExecutionModelJudgmentTest.java`

**Interfaces:**
- Consumes: `JudgmentPhase<T>`, `JudgmentDecision`, `JudgmentContext<T>` from Task 1
- Produces: `ExecutionModel<T>` 12th component `@Nullable JudgmentPhase<T> judgment`
- Produces: `AbstractExecutionDriver` judgment loop integration (between aggregation and termination)
- Produces: `ExecutionEventListener.onJudgment(JudgmentDecision)` callback

- [ ] **Step 1: Write test for ExecutionModel with judgment**

```java
package io.casehub.blocks.agentic.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CallerIdentity;
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.FailurePolicy;
import io.casehub.blocks.agentic.RoutingCandidate;
import io.casehub.blocks.agentic.activation.AlwaysActivate;
import io.casehub.blocks.agentic.aggregation.PassThrough;
import io.casehub.blocks.agentic.decomposition.IdentityDecomposition;
import io.casehub.blocks.agentic.judgment.JudgmentContext;
import io.casehub.blocks.agentic.judgment.JudgmentDecision;
import io.casehub.blocks.agentic.judgment.JudgmentPhase;
import io.casehub.blocks.agentic.routing.FirstMatchRouting;
import io.casehub.blocks.agentic.termination.MaxIterationsTermination;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionModelJudgmentTest {

    @Test
    void nullJudgment_loopBehavesAsToday() {
        var agent = AgentRef.external("a", ctx -> CompletableFuture.completedFuture(
            AgentResult.success(null, "done")));
        var model = new ExecutionModel<>(
            new FirstMatchRouting<>(c -> true),
            new IdentityDecomposition<>(),
            new AlwaysActivate<>(),
            new PassThrough<>(),
            new MaxIterationsTermination<>(1),
            () -> List.of(new RoutingCandidate(agent, null)),
            FailurePolicy.defaults(),
            List.of(),
            "test",
            PatternType.SUPERVISOR);

        assertThat(model.judgment()).isNull();
        var driver = new OrchestratedDriver<>();
        var result = driver.execute(model, "ctx").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void approvedJudgment_proceedsToTermination() {
        var agent = AgentRef.external("a", ctx -> CompletableFuture.completedFuture(
            AgentResult.success(null, "done")));
        JudgmentPhase<Object> phase = ctx ->
            new JudgmentDecision.Approved("ok", List.of(), new CallerIdentity("test", "llm", null));

        var model = new ExecutionModel<>(
            new FirstMatchRouting<>(c -> true),
            new IdentityDecomposition<>(),
            new AlwaysActivate<>(),
            new PassThrough<>(),
            new MaxIterationsTermination<>(1),
            () -> List.of(new RoutingCandidate(agent, null)),
            FailurePolicy.defaults(),
            List.of(),
            "test",
            PatternType.SUPERVISOR,
            null,
            phase);

        var driver = new OrchestratedDriver<>();
        var result = driver.execute(model, "ctx").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void rejectedJudgment_reIteratesWithFeedback() {
        var callCount = new AtomicInteger(0);
        var agent = AgentRef.external("a", ctx -> CompletableFuture.completedFuture(
            AgentResult.success(null, "attempt-" + callCount.incrementAndGet())));

        var judgmentCalls = new AtomicInteger(0);
        JudgmentPhase<Object> phase = ctx -> {
            int call = judgmentCalls.incrementAndGet();
            if (call == 1) {
                assertThat(ctx.previousFeedback()).isNull();
                return new JudgmentDecision.Rejected("needs more detail", List.of(),
                    new CallerIdentity("test", "llm", null));
            }
            assertThat(ctx.previousFeedback()).isEqualTo("needs more detail");
            return new JudgmentDecision.Approved("ok", List.of(),
                new CallerIdentity("test", "llm", null));
        };

        var model = new ExecutionModel<>(
            new FirstMatchRouting<>(c -> true),
            new IdentityDecomposition<>(),
            new AlwaysActivate<>(),
            new PassThrough<>(),
            new MaxIterationsTermination<>(5),
            () -> List.of(new RoutingCandidate(agent, null)),
            FailurePolicy.defaults(),
            List.of(),
            "test",
            PatternType.SUPERVISOR,
            null,
            phase);

        var driver = new OrchestratedDriver<>();
        var result = driver.execute(model, "ctx").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(judgmentCalls.get()).isEqualTo(2);
    }

    @Test
    void escalatedJudgment_escalatesExecution() {
        var agent = AgentRef.external("a", ctx -> CompletableFuture.completedFuture(
            AgentResult.success(null, "done")));
        JudgmentPhase<Object> phase = ctx ->
            new JudgmentDecision.Escalated("beyond expertise", new CallerIdentity("test", "llm", null));

        var model = new ExecutionModel<>(
            new FirstMatchRouting<>(c -> true),
            new IdentityDecomposition<>(),
            new AlwaysActivate<>(),
            new PassThrough<>(),
            new MaxIterationsTermination<>(5),
            () -> List.of(new RoutingCandidate(agent, null)),
            FailurePolicy.defaults(),
            List.of(),
            "test",
            PatternType.SUPERVISOR,
            null,
            phase);

        var driver = new OrchestratedDriver<>();
        var result = driver.execute(model, "ctx").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Escalated.class);
    }

    @Test
    void listener_receivesJudgmentCallbacks() {
        var agent = AgentRef.external("a", ctx -> CompletableFuture.completedFuture(
            AgentResult.success(null, "done")));
        var decisions = new ArrayList<JudgmentDecision>();
        JudgmentPhase<Object> phase = ctx ->
            new JudgmentDecision.Approved("ok", List.of(), new CallerIdentity("test", "llm", null));

        ExecutionEventListener listener = new ExecutionEventListener() {
            @Override
            public void onJudgment(JudgmentDecision decision) {
                decisions.add(decision);
            }
        };

        var model = new ExecutionModel<>(
            new FirstMatchRouting<>(c -> true),
            new IdentityDecomposition<>(),
            new AlwaysActivate<>(),
            new PassThrough<>(),
            new MaxIterationsTermination<>(1),
            () -> List.of(new RoutingCandidate(agent, null)),
            FailurePolicy.defaults(),
            List.of(listener),
            "test",
            PatternType.SUPERVISOR,
            null,
            phase);

        var driver = new OrchestratedDriver<>();
        driver.execute(model, "ctx").await().indefinitely();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0)).isInstanceOf(JudgmentDecision.Approved.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl blocks -Dtest=ExecutionModelJudgmentTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: FAIL — ExecutionModel has no 12-arg constructor, no `judgment()` accessor

- [ ] **Step 3: Add JudgmentPhase to ExecutionModel**

Add 12th component `@Nullable JudgmentPhase<T> judgment` to the record. Add backward-compatible constructors passing null.

In `ExecutionModel.java`, add import for `io.casehub.blocks.agentic.judgment.JudgmentPhase` and `org.jspecify.annotations.Nullable`. Add the field to the record definition. Update the existing 10-arg and 9-arg constructors to pass `null` for judgment. Add an 11-arg constructor (with backend, without judgment) that also passes null.

- [ ] **Step 4: Add onJudgment callback to ExecutionEventListener**

In `ExecutionEventListener.java`, add:
```java
default void onJudgment(io.casehub.blocks.agentic.judgment.JudgmentDecision decision) {}
```

- [ ] **Step 5: Integrate judgment phase into AbstractExecutionDriver**

In `AbstractExecutionDriver.java`:
1. Add field `protected String lastJudgmentFeedback = null;`
2. Reset it in `execute()` alongside other state: `lastJudgmentFeedback = null;`
3. In `executeIteration()`, after the aggregation section (line ~116) and before the termination section (line ~132), insert the judgment phase:

```java
// Phase 3.5: Judgment (if configured)
if (model.judgment() != null) {
    var judgmentCtx = new JudgmentContext<>(
        context, results, lastAggregationResult, iteration, lastJudgmentFeedback);
    var judgmentDecision = model.judgment().evaluate(judgmentCtx);
    notifyJudgment(model, judgmentDecision);

    switch (judgmentDecision) {
        case JudgmentDecision.Approved approved -> lastJudgmentFeedback = null;
        case JudgmentDecision.Rejected rejected -> {
            lastJudgmentFeedback = rejected.feedback();
            return null; // re-iterate
        }
        case JudgmentDecision.Escalated escalated -> {
            return new ExecutionResult.Escalated(escalated.reason());
        }
    }
}
```

4. Add `notifyJudgment` helper:
```java
protected void notifyJudgment(ExecutionModel<T> model, JudgmentDecision decision) {
    for (var listener : model.listeners()) {
        listener.onJudgment(decision);
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl blocks -Dtest=ExecutionModelJudgmentTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: PASS (all 5 tests)

- [ ] **Step 7: Run full blocks test suite to check no regressions**

Run: `mvn test -pl blocks -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: All existing tests PASS — null judgment field means no behavior change

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks add blocks/src/
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(#173): ExecutionModel gains JudgmentPhase — loop integration with re-iterate on rejection

Refs casehubio/engine#994"
```

---

## Batch 2: Engine-Adapter — LlmJudgmentPhase, Config, YAML Parsing

### Task 3: PatternJudgmentConfig and LlmJudgmentPhase

**Files:**
- Create: `engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/PatternJudgmentConfig.java`
- Create: `engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/LlmJudgmentPhase.java`
- Test: `engine-adapter/src/test/java/io/casehub/engine/agentic/judgment/LlmJudgmentPhaseTest.java`

**Interfaces:**
- Consumes: `JudgmentPhase<T>`, `JudgmentDecision`, `JudgmentContext<T>` from Task 1
- Consumes: `ChatModelProvider` from engine-api, `JudgmentVerifier` from engine-api
- Produces: `PatternJudgmentConfig(String prompt, CallerConfig callerConfig, String verifierStrategy, List<EvidenceRequirement> evidenceRequirements, JudgmentMode mode, boolean afterStep)`
- Produces: `LlmJudgmentPhase<T>` implementing `JudgmentPhase<T>`

- [ ] **Step 1: Write tests for LlmJudgmentPhase**

```java
package io.casehub.engine.agentic.judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.api.model.CallerConfig;
import io.casehub.api.model.EvidenceRequirement;
import io.casehub.api.model.EvidenceType;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.spi.JudgmentVerifier;
import io.casehub.api.spi.VerificationResult;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.aggregation.AggregationResult;
import io.casehub.blocks.agentic.judgment.JudgmentContext;
import io.casehub.blocks.agentic.judgment.JudgmentDecision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmJudgmentPhaseTest {

    private ChatModel chatModel;
    private ChatModelProvider provider;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        provider = mock(ChatModelProvider.class);
        when(provider.get()).thenReturn(chatModel);
    }

    @Test
    void approved_whenLlmReturnsApproveResponse() {
        mockLlmResponse("APPROVE: The analysis is thorough and well-structured.");
        var config = new PatternJudgmentConfig(
            "Review the output", new CallerConfig.Llm(null, "test-model", null),
            null, List.of(), null, false);
        var phase = new LlmJudgmentPhase<>(provider, config, null);

        var ctx = new JudgmentContext<>("state", List.of(), new AggregationResult.Resolved("result"), 0, null);
        var decision = phase.evaluate(ctx);

        assertThat(decision).isInstanceOf(JudgmentDecision.Approved.class);
    }

    @Test
    void rejected_whenLlmReturnsRejectResponse() {
        mockLlmResponse("REJECT: Missing error handling analysis.");
        var config = new PatternJudgmentConfig(
            "Review the output", new CallerConfig.Llm(null, "test-model", null),
            null, List.of(), null, false);
        var phase = new LlmJudgmentPhase<>(provider, config, null);

        var ctx = new JudgmentContext<>("state", List.of(), new AggregationResult.Resolved("result"), 0, null);
        var decision = phase.evaluate(ctx);

        assertThat(decision).isInstanceOf(JudgmentDecision.Rejected.class);
        assertThat(((JudgmentDecision.Rejected) decision).feedback()).contains("Missing error handling");
    }

    @Test
    void previousFeedback_includedInPrompt() {
        mockLlmResponse("APPROVE: Now looks complete.");
        var config = new PatternJudgmentConfig(
            "Review the output", new CallerConfig.Llm(null, "test-model", null),
            null, List.of(), null, false);
        var phase = new LlmJudgmentPhase<>(provider, config, null);

        var ctx = new JudgmentContext<>("state", List.of(), new AggregationResult.Resolved("result"), 1, "needs more detail");
        var decision = phase.evaluate(ctx);

        assertThat(decision).isInstanceOf(JudgmentDecision.Approved.class);
    }

    @Test
    void verifierRejects_returnsRejected() {
        mockLlmResponse("APPROVE: Looks good.");
        var verifier = mock(JudgmentVerifier.class);
        when(verifier.verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new VerificationResult.Rejected("schema mismatch"));

        var config = new PatternJudgmentConfig(
            "Review", new CallerConfig.Llm(null, "test-model", null),
            "schema-validation", List.of(), null, false);
        var phase = new LlmJudgmentPhase<>(provider, config, verifier);

        var ctx = new JudgmentContext<>("state", List.of(), new AggregationResult.Resolved("result"), 0, null);
        var decision = phase.evaluate(ctx);

        assertThat(decision).isInstanceOf(JudgmentDecision.Rejected.class);
        assertThat(((JudgmentDecision.Rejected) decision).feedback()).contains("schema mismatch");
    }

    @Test
    void chatModelFailure_returnsEscalated() {
        when(chatModel.chat(anyList())).thenThrow(new RuntimeException("API error"));
        var config = new PatternJudgmentConfig(
            "Review", new CallerConfig.Llm(null, "test-model", null),
            null, List.of(), null, false);
        var phase = new LlmJudgmentPhase<>(provider, config, null);

        var ctx = new JudgmentContext<>("state", List.of(), new AggregationResult.Resolved("result"), 0, null);
        var decision = phase.evaluate(ctx);

        assertThat(decision).isInstanceOf(JudgmentDecision.Escalated.class);
    }

    private void mockLlmResponse(String text) {
        var aiMessage = AiMessage.from(text);
        var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
        when(chatModel.chat(anyList())).thenReturn(chatResponse);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl engine-adapter -Dtest=LlmJudgmentPhaseTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create PatternJudgmentConfig record**

```java
package io.casehub.engine.agentic.judgment;

import io.casehub.api.model.CallerConfig;
import io.casehub.api.model.EvidenceRequirement;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record PatternJudgmentConfig(
    String prompt,
    CallerConfig callerConfig,
    @Nullable String verifierStrategy,
    List<EvidenceRequirement> evidenceRequirements,
    @Nullable JudgmentMode mode,
    boolean afterStep
) {
    public enum JudgmentMode {
        INTEGRATED,
        POST_STEP
    }
}
```

- [ ] **Step 4: Create LlmJudgmentPhase**

```java
package io.casehub.engine.agentic.judgment;

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.Evidence;
import io.casehub.api.model.EvidenceType;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.spi.JudgmentVerifier;
import io.casehub.api.model.JudgmentResponse;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.VerificationContext;
import io.casehub.api.spi.VerificationResult;
import io.casehub.blocks.agentic.judgment.JudgmentContext;
import io.casehub.blocks.agentic.judgment.JudgmentDecision;
import io.casehub.blocks.agentic.judgment.JudgmentPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public class LlmJudgmentPhase<T> implements JudgmentPhase<T> {

    private final ChatModelProvider chatModelProvider;
    private final PatternJudgmentConfig config;
    private final @Nullable JudgmentVerifier verifier;

    public LlmJudgmentPhase(ChatModelProvider chatModelProvider,
                             PatternJudgmentConfig config,
                             @Nullable JudgmentVerifier verifier) {
        this.chatModelProvider = chatModelProvider;
        this.config = config;
        this.verifier = verifier;
    }

    @Override
    public JudgmentDecision evaluate(JudgmentContext<T> context) {
        try {
            return doEvaluate(context);
        } catch (Exception e) {
            return new JudgmentDecision.Escalated(
                "LLM judgment failed: " + e.getMessage(),
                buildCallerIdentity());
        }
    }

    private JudgmentDecision doEvaluate(JudgmentContext<T> context) {
        var chatModel = chatModelProvider.get();
        String prompt = buildPrompt(context);

        var messages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        if (config.callerConfig() instanceof io.casehub.api.model.CallerConfig.Llm llm
                && llm.systemPrompt() != null) {
            messages.add(dev.langchain4j.data.message.SystemMessage.from(llm.systemPrompt()));
        } else {
            messages.add(dev.langchain4j.data.message.SystemMessage.from(
                "You are a judgment evaluator. Review the work output and respond with "
                + "APPROVE if the output is satisfactory, or REJECT followed by specific feedback "
                + "if it needs improvement. Only respond with one of these two formats."));
        }
        messages.add(dev.langchain4j.data.message.UserMessage.from(prompt));

        var chatResponse = chatModel.chat(messages);
        String llmOutput = chatResponse.aiMessage().text().trim();

        var evidence = List.of(new Evidence("llm-reasoning", EvidenceType.REASONING, llmOutput, null));
        var callerIdentity = buildCallerIdentity();

        var response = new JudgmentResponse(
            Map.of("decision", llmOutput), evidence, callerIdentity, Instant.now());

        if (verifier != null) {
            var verificationResult = verifier.verify(response, null);
            if (verificationResult instanceof VerificationResult.Rejected rejected) {
                return new JudgmentDecision.Rejected(rejected.reason(), evidence, callerIdentity);
            }
            if (verificationResult instanceof VerificationResult.InsufficientEvidence insufficient) {
                return new JudgmentDecision.Rejected(insufficient.feedback(), evidence, callerIdentity);
            }
        }

        if (llmOutput.toUpperCase().startsWith("APPROVE") || llmOutput.toUpperCase().startsWith("ACCEPT")) {
            return new JudgmentDecision.Approved(llmOutput, evidence, callerIdentity);
        }

        String feedback = llmOutput.toUpperCase().startsWith("REJECT")
            ? llmOutput.substring(Math.min(7, llmOutput.length())).trim()
            : llmOutput;
        return new JudgmentDecision.Rejected(
            feedback.isEmpty() ? "Judgment rejected the output" : feedback,
            evidence, callerIdentity);
    }

    private String buildPrompt(JudgmentContext<T> context) {
        var sb = new StringBuilder();
        if (config.prompt() != null) {
            sb.append(config.prompt()).append("\n\n");
        }
        if (context.aggregationResult() instanceof io.casehub.blocks.agentic.aggregation.AggregationResult.Resolved resolved) {
            sb.append("Work output:\n").append(resolved.value()).append("\n\n");
        }
        if (context.previousFeedback() != null) {
            sb.append("Previous feedback (address this):\n").append(context.previousFeedback()).append("\n\n");
        }
        sb.append("Iteration: ").append(context.iteration() + 1).append("\n");
        return sb.toString();
    }

    private CallerIdentity buildCallerIdentity() {
        String modelName = "llm";
        if (config.callerConfig() instanceof io.casehub.api.model.CallerConfig.Llm llm && llm.modelName() != null) {
            modelName = llm.modelName();
        }
        return new CallerIdentity(modelName, "llm", null);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -pl engine-adapter -Dtest=LlmJudgmentPhaseTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: PASS (all 5 tests)

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks add engine-adapter/src/
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(#173): PatternJudgmentConfig and LlmJudgmentPhase — inline LLM judgment within patterns

Refs casehubio/engine#994"
```

### Task 4: YAML Parsing and PatternWorkerFunction Wiring

**Files:**
- Modify: `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunction.java`
- Modify: `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionProvider.java`
- Modify: `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionHandler.java`
- Modify: `engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/LlmJudgmentPhase.java` (add builder method)
- Test: `engine-adapter/src/test/java/io/casehub/engine/agentic/PatternJudgmentYamlTest.java`

**Interfaces:**
- Consumes: `PatternJudgmentConfig` from Task 3, `LlmJudgmentPhase` from Task 3
- Consumes: `PatternWorkerFunction`, `PatternWorkerFunctionProvider`, `PatternWorkerFunctionHandler`
- Produces: `PatternWorkerFunction` gains `@Nullable PatternJudgmentConfig judgmentConfig` (6th component)
- Produces: YAML `judgment:` block parsing in provider
- Produces: `PatternWorkerFunctionHandler` resolves `JudgmentPhase` and injects into `ExecutionModel`

- [ ] **Step 1: Write test for YAML parsing**

```java
package io.casehub.engine.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.CallerConfig;
import io.casehub.api.model.EvidenceType;
import io.casehub.engine.agentic.judgment.PatternJudgmentConfig;
import org.junit.jupiter.api.Test;

class PatternJudgmentYamlTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PatternWorkerFunctionProvider provider = new PatternWorkerFunctionProvider();

    @Test
    void parsesJudgmentBlockWithLlmCaller() {
        var node = mapper.createObjectNode();
        var pattern = node.putObject("pattern");
        pattern.put("type", "SUPERVISOR");
        var judgment = pattern.putObject("judgment");
        judgment.put("prompt", "Review the analysis");
        judgment.put("verifier", "schema-validation");
        var caller = judgment.putObject("caller");
        caller.put("type", "llm");
        caller.put("modelName", "claude-sonnet-4-20250514");

        var fn = (PatternWorkerFunction) provider.create(node);

        assertThat(fn.judgmentConfig()).isNotNull();
        assertThat(fn.judgmentConfig().prompt()).isEqualTo("Review the analysis");
        assertThat(fn.judgmentConfig().verifierStrategy()).isEqualTo("schema-validation");
        assertThat(fn.judgmentConfig().callerConfig()).isInstanceOf(CallerConfig.Llm.class);
    }

    @Test
    void parsesEvidenceRequirements() {
        var node = mapper.createObjectNode();
        var pattern = node.putObject("pattern");
        pattern.put("type", "SUPERVISOR");
        var judgment = pattern.putObject("judgment");
        judgment.put("prompt", "Review");
        var caller = judgment.putObject("caller");
        caller.put("type", "llm");
        var evidence = judgment.putArray("evidence");
        var req = evidence.addObject();
        req.put("name", "rationale");
        req.put("type", "REASONING");
        req.put("required", true);

        var fn = (PatternWorkerFunction) provider.create(node);

        assertThat(fn.judgmentConfig().evidenceRequirements()).hasSize(1);
        assertThat(fn.judgmentConfig().evidenceRequirements().get(0).name()).isEqualTo("rationale");
        assertThat(fn.judgmentConfig().evidenceRequirements().get(0).type()).isEqualTo(EvidenceType.REASONING);
    }

    @Test
    void noJudgmentBlock_returnsNullConfig() {
        var node = mapper.createObjectNode();
        var pattern = node.putObject("pattern");
        pattern.put("type", "SUPERVISOR");

        var fn = (PatternWorkerFunction) provider.create(node);

        assertThat(fn.judgmentConfig()).isNull();
    }

    @Test
    void parsesAfterStepFlag() {
        var node = mapper.createObjectNode();
        var pattern = node.putObject("pattern");
        pattern.put("type", "SEQUENCE");
        var judgment = pattern.putObject("judgment");
        judgment.put("prompt", "Quality gate");
        judgment.put("afterStep", true);
        var caller = judgment.putObject("caller");
        caller.put("type", "llm");

        var fn = (PatternWorkerFunction) provider.create(node);

        assertThat(fn.judgmentConfig().afterStep()).isTrue();
    }

    @Test
    void parsesJudgmentMode() {
        var node = mapper.createObjectNode();
        var pattern = node.putObject("pattern");
        pattern.put("type", "SUPERVISOR");
        var judgment = pattern.putObject("judgment");
        judgment.put("prompt", "Validate");
        judgment.put("mode", "post-step");
        var caller = judgment.putObject("caller");
        caller.put("type", "llm");

        var fn = (PatternWorkerFunction) provider.create(node);

        assertThat(fn.judgmentConfig().mode()).isEqualTo(PatternJudgmentConfig.JudgmentMode.POST_STEP);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl engine-adapter -Dtest=PatternJudgmentYamlTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: FAIL — `judgmentConfig()` doesn't exist on PatternWorkerFunction

- [ ] **Step 3: Add judgmentConfig to PatternWorkerFunction**

Add `@Nullable PatternJudgmentConfig judgmentConfig` as the 6th record component. Add backward-compatible constructors that pass null.

- [ ] **Step 4: Add YAML parsing to PatternWorkerFunctionProvider**

In `PatternWorkerFunctionProvider.create()`, after parsing constraints, add:

```java
PatternJudgmentConfig judgmentConfig = null;
if (patternNode.has("judgment")) {
    judgmentConfig = parseJudgmentConfig(patternNode.get("judgment"));
}
return new PatternWorkerFunction(null, patternType, checkpointing, constraints, null, judgmentConfig);
```

Add `parseJudgmentConfig(JsonNode)` method:
```java
private PatternJudgmentConfig parseJudgmentConfig(JsonNode judgmentNode) {
    String prompt = judgmentNode.path("prompt").asText(null);
    boolean afterStep = judgmentNode.path("afterStep").asBoolean(false);

    CallerConfig callerConfig = null;
    if (judgmentNode.has("caller")) {
        callerConfig = parseCallerConfig(judgmentNode.get("caller"));
    }

    String verifier = judgmentNode.path("verifier").asText(null);

    List<EvidenceRequirement> evidenceRequirements = new ArrayList<>();
    if (judgmentNode.has("evidence") && judgmentNode.get("evidence").isArray()) {
        for (JsonNode req : judgmentNode.get("evidence")) {
            evidenceRequirements.add(new EvidenceRequirement(
                req.path("name").asText(),
                EvidenceType.valueOf(req.path("type").asText()),
                req.path("required").asBoolean(false)));
        }
    }

    PatternJudgmentConfig.JudgmentMode mode = null;
    if (judgmentNode.has("mode")) {
        String modeStr = judgmentNode.get("mode").asText().toUpperCase().replace("-", "_");
        mode = PatternJudgmentConfig.JudgmentMode.valueOf(modeStr);
    }

    return new PatternJudgmentConfig(prompt, callerConfig, verifier, evidenceRequirements, mode, afterStep);
}

private CallerConfig parseCallerConfig(JsonNode callerNode) {
    String type = callerNode.path("type").asText("llm");
    return switch (type) {
        case "llm" -> new CallerConfig.Llm(
            callerNode.path("model").asText(null),
            callerNode.path("modelName").asText(null),
            callerNode.path("systemPrompt").asText(null));
        case "a2a" -> new CallerConfig.A2A(
            callerNode.path("endpoint").asText(null),
            callerNode.path("skill").asText(null),
            callerNode.path("streaming").asBoolean(false));
        default -> new CallerConfig.Any();
    };
}
```

- [ ] **Step 5: Wire JudgmentPhase in PatternWorkerFunctionHandler**

In `PatternWorkerFunctionHandler.execute()`, after resolving the effective model and before creating the driver, add:

```java
final ExecutionModel<?> modelWithJudgment;
if (patternFn.judgmentConfig() != null) {
    modelWithJudgment = injectJudgmentPhase(effectiveModel, patternFn.judgmentConfig());
} else {
    modelWithJudgment = effectiveModel;
}
```

Use `modelWithJudgment` instead of `effectiveModel` in the rest of the method. Add:

```java
@SuppressWarnings("unchecked")
private <T> ExecutionModel<T> injectJudgmentPhase(ExecutionModel<T> model, PatternJudgmentConfig config) {
    JudgmentPhase<T> phase = resolveJudgmentPhase(config);
    if (phase == null) return model;
    return new ExecutionModel<>(
        model.routing(), model.decomposition(), model.activation(),
        model.aggregation(), model.termination(), model.candidateSupplier(),
        model.failurePolicy(), model.listeners(), model.task(),
        model.patternType(), model.backend(), phase);
}

@SuppressWarnings("unchecked")
private <T> JudgmentPhase<T> resolveJudgmentPhase(PatternJudgmentConfig config) {
    if (config.callerConfig() instanceof CallerConfig.Llm) {
        if (!chatModelProviderInstance.isResolvable()) {
            LOG.warn("No ChatModelProvider — skipping pattern judgment phase");
            return null;
        }
        JudgmentVerifier verifier = resolveVerifier(config.verifierStrategy());
        return (JudgmentPhase<T>) new LlmJudgmentPhase<>(chatModelProviderInstance.get(), config, verifier);
    }
    LOG.warnf("Unsupported caller type for pattern judgment: %s",
        config.callerConfig().getClass().getSimpleName());
    return null;
}
```

Add injection fields for `Instance<ChatModelProvider>` and verifier resolution. Add a `resolveVerifier` method that looks up verifiers from CDI.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -pl engine-adapter -Dtest=PatternJudgmentYamlTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: PASS (all 5 tests)

- [ ] **Step 7: Run full engine-adapter test suite**

Run: `mvn test -pl engine-adapter -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: All existing tests PASS

- [ ] **Step 8: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks add engine-adapter/src/
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(#173): YAML judgment parsing, PatternWorkerFunction wiring, LlmJudgmentPhase handler integration

Refs casehubio/engine#994"
```

---

## Batch 3: Builder API and Pattern-Type Integration

### Task 5: AbstractPatternBuilder.judgment() and pattern-type defaults

**Files:**
- Modify: `blocks/src/main/java/io/casehub/blocks/agentic/pattern/AbstractPatternBuilder.java`
- Test: `blocks/src/test/java/io/casehub/blocks/agentic/pattern/SupervisorJudgmentTest.java`

**Interfaces:**
- Consumes: `JudgmentPhase<T>` from Task 1, `PatternJudgmentConfig` from Task 3
- Produces: `AbstractPatternBuilder.judgment(JudgmentPhase<T>)` method
- Produces: `ExecutionModel` built with judgment phase

- [ ] **Step 1: Write test for builder with judgment**

```java
package io.casehub.blocks.agentic.pattern;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CallerIdentity;
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.judgment.JudgmentDecision;
import io.casehub.blocks.agentic.judgment.JudgmentPhase;
import io.casehub.blocks.agentic.model.ExecutionResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SupervisorJudgmentTest {

    @Test
    void supervisorWithJudgment_completesAfterApproval() {
        var agent = AgentRef.external("analyst", ctx ->
            CompletableFuture.completedFuture(AgentResult.success(null, "analysis")));

        JudgmentPhase<Object> judgment = ctx ->
            new JudgmentDecision.Approved("approved", List.of(),
                new CallerIdentity("reviewer", "llm", null));

        var model = new SupervisorBuilder<>()
            .agents(agent)
            .task("supervised-analysis")
            .judgment(judgment)
            .build();

        assertThat(model.judgment()).isNotNull();

        var result = new io.casehub.blocks.agentic.model.OrchestratedDriver<>()
            .execute(model, "context").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    }

    @Test
    void supervisorWithRejection_reIteratesThenApproves() {
        var attempts = new AtomicInteger(0);
        var agent = AgentRef.external("analyst", ctx -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(AgentResult.success(null, "attempt-" + attempts.get()));
        });

        var judgmentCalls = new AtomicInteger(0);
        JudgmentPhase<Object> judgment = ctx -> {
            if (judgmentCalls.incrementAndGet() == 1) {
                return new JudgmentDecision.Rejected("incomplete", List.of(),
                    new CallerIdentity("reviewer", "llm", null));
            }
            return new JudgmentDecision.Approved("good", List.of(),
                new CallerIdentity("reviewer", "llm", null));
        };

        var model = new SupervisorBuilder<>()
            .agents(agent)
            .task("supervised-analysis")
            .judgment(judgment)
            .build();

        var result = new io.casehub.blocks.agentic.model.OrchestratedDriver<>()
            .execute(model, "context").await().indefinitely();
        assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
        assertThat(attempts.get()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl blocks -Dtest=SupervisorJudgmentTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: FAIL — `judgment()` method doesn't exist on builder

- [ ] **Step 3: Add judgment method to AbstractPatternBuilder**

In `AbstractPatternBuilder.java`, add field and method:

```java
protected JudgmentPhase<T> judgmentPhase;

public B judgment(JudgmentPhase<T> judgment) {
    this.judgmentPhase = judgment;
    return (B) this;
}
```

Update `build()` to pass `judgmentPhase`:
```java
public ExecutionModel<T> build() {
    return new ExecutionModel<>(routing, decomposition, activation,
                                aggregation, termination, candidateSupplier,
                                failurePolicy, listeners, task, patternType,
                                backend, judgmentPhase);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl blocks -Dtest=SupervisorJudgmentTest -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: PASS

- [ ] **Step 5: Run full blocks + engine-adapter test suite**

Run: `mvn test -pl blocks,engine-adapter -f /Users/mdproctor/claude/casehub/slots/160/blocks/pom.xml`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/slots/160/blocks add blocks/src/ engine-adapter/src/
git -C /Users/mdproctor/claude/casehub/slots/160/blocks commit -m "feat(#173): AbstractPatternBuilder.judgment() — Java DSL for yield-aware patterns

Refs casehubio/engine#994"
```

---

## References

- `docs/specs/issue-994-governed-yield/2026-08-28-yield-aware-patterns-design.md` — design spec
- `blocks/src/main/java/io/casehub/blocks/agentic/model/ExecutionModel.java` — 11-component record gaining 12th
- `blocks/src/main/java/io/casehub/blocks/agentic/model/AbstractExecutionDriver.java:97-149` — five-phase loop
- `blocks/src/main/java/io/casehub/blocks/agentic/model/ExecutionEventListener.java` — listener interface
- `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionHandler.java` — handler pipeline
- `engine-adapter/src/main/java/io/casehub/engine/agentic/PatternWorkerFunctionProvider.java` — YAML parsing
- `engine-adapter/src/main/java/io/casehub/engine/agentic/judgment/LlmJudgmentScheduler.java` — LLM caller pattern to follow
- `docs/protocols/casehub/plan-type-module-boundary.md` (PP-20260727-5267d2) — module boundary rules
- casehubio/blocks#173, casehubio/engine#994
