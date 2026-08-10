/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.engine.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.WorkerContext;
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.PatternType;
import io.casehub.blocks.agentic.pattern.Patterns;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.worker.api.WorkerOutcome;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatternWorkerFunctionHandlerTest {

  private WorkerRuntimeFactory runtimeFactory;
  private WorkerRuntime runtime;
  private PatternCheckpointStore checkpointStore;
  private PatternWorkerFunctionHandler handler;

  @BeforeEach
  void setUp() {
    runtimeFactory = mock(WorkerRuntimeFactory.class);
    runtime = mock(WorkerRuntime.class);
    checkpointStore = mock(PatternCheckpointStore.class);
    when(runtimeFactory.create(any(UUID.class), anyString(), any(WorkerContext.class)))
        .thenReturn(runtime);
    handler = new PatternWorkerFunctionHandler(runtimeFactory, checkpointStore);
  }

  @Test
  void supportsPatternWorkerFunction() {
    var fn = new PatternWorkerFunction(null, PatternType.DEBATE, false);
    assertThat(handler.supports(fn)).isTrue();
  }

  @Test
  void doesNotSupportOtherFunctions() {
    assertThat(handler.supports(io.casehub.worker.api.WorkerFunction.NONE)).isFalse();
  }

  @Test
  void executesSequencePatternWithExternalAgents() {
    var agent1 =
        AgentRef.external(
            "a1",
            ctx ->
                CompletableFuture.completedFuture(AgentResult.success(null, Map.of("step", "1"))));
    var agent2 =
        AgentRef.external(
            "a2",
            ctx ->
                CompletableFuture.completedFuture(AgentResult.success(null, Map.of("step", "2"))));

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>sequence().agents(agent1, agent2).build();

    var fn = new PatternWorkerFunction(model, PatternType.SEQUENCE, false);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result).isNotNull();
    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
  }

  @Test
  void returnsFailureWhenModelIsNull() {
    var fn = new PatternWorkerFunction(null, PatternType.SEQUENCE, false);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result.result().outcome()).isNotInstanceOf(WorkerOutcome.Success.class);
    assertThat(result.protocolMetadata()).containsEntry("patternType", "SEQUENCE");
  }

  @Test
  void protocolMetadataIncludesPatternType() {
    var agent =
        AgentRef.external(
            "a", ctx -> CompletableFuture.completedFuture(AgentResult.success(null, Map.of())));

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>sequence().agents(agent).build();

    var fn = new PatternWorkerFunction(model, PatternType.SEQUENCE, false);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result.protocolMetadata()).containsEntry("patternType", "SEQUENCE");
  }

  @Test
  void timeBudgetReducesEffectiveTimeout() {
    var slowAgent =
        AgentRef.external(
            "slow",
            ctx -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return CompletableFuture.completedFuture(
                  AgentResult.success(null, Map.of("done", true)));
            });

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>sequence().agents(slowAgent).build();

    var constraints =
        io.casehub.engine.plan.PlanningConstraints.of(java.time.Duration.ofMillis(50), null);
    var fn = new PatternWorkerFunction(model, PatternType.SEQUENCE, false, constraints);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result.result().outcome()).isNotInstanceOf(WorkerOutcome.Success.class);
  }

  @Test
  void resourceLimitCapsAgentsPerIteration() {
    var counter = new java.util.concurrent.atomic.AtomicInteger(0);
    var agents =
        java.util.stream.IntStream.range(0, 5)
            .mapToObj(
                i ->
                    AgentRef.external(
                        "agent-" + i,
                        ctx -> {
                          counter.incrementAndGet();
                          return CompletableFuture.completedFuture(
                              AgentResult.success(null, Map.of("agent", i)));
                        }))
            .toList();

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>parallel().agents(agents.toArray(AgentRef[]::new)).build();

    var constraints = io.casehub.engine.plan.PlanningConstraints.of(null, 2);
    var fn = new PatternWorkerFunction(model, PatternType.PARALLEL, false, constraints);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(counter.get()).isLessThanOrEqualTo(2);
  }

  @Test
  void executesHtnPatternViaHtnExecutor() {
    var agent =
        AgentRef.external(
            "analyser",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("analysis", "done"))));

    var leaf =
        new io.casehub.blocks.agentic.decomposition.PlannedTask<>(
            "s1", java.time.Instant.now(), "analyse", agent, null);
    var plan =
        io.casehub.engine.plan.DagPlan.<io.casehub.engine.plan.TaskNode.LeafTask<Object>>singleton(
            leaf);

    io.casehub.engine.plan.DecompositionStrategy<Object> decomposition =
        (task, ctx) -> io.smallrye.mutiny.Uni.createFrom().item(plan);

    var rootTask = new io.casehub.engine.plan.TaskNode.CompoundTask<>("goal", java.util.List.of());
    var model =
        Patterns.<Object>htn()
            .decompose(decomposition)
            .agents(new io.casehub.blocks.agentic.RoutingCandidate(agent, null))
            .build();

    var fn = new PatternWorkerFunction(model, PatternType.HTN, false, null, rootTask);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
  }

  @Test
  void htnPatternWithReplanRecovery() {
    var failAgent =
        AgentRef.external(
            "fail",
            ctx -> CompletableFuture.completedFuture(AgentResult.failure(null, "transient error")));
    var recoveryAgent =
        AgentRef.external(
            "recovery",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("recovered", true))));

    var failLeaf =
        new io.casehub.blocks.agentic.decomposition.PlannedTask<>(
            "s1", java.time.Instant.now(), "will-fail", failAgent, null);
    var recoveryLeaf =
        new io.casehub.blocks.agentic.decomposition.PlannedTask<>(
            "r1", java.time.Instant.now(), "recovery", recoveryAgent, null);

    var originalPlan =
        io.casehub.engine.plan.DagPlan.<io.casehub.engine.plan.TaskNode.LeafTask<Object>>singleton(
            failLeaf);
    var revisedPlan =
        io.casehub.engine.plan.DagPlan.<io.casehub.engine.plan.TaskNode.LeafTask<Object>>singleton(
            recoveryLeaf);

    io.casehub.engine.plan.DecompositionStrategy<Object> decomposition =
        new io.casehub.engine.plan.DecompositionStrategy<>() {
          @Override
          public io.smallrye.mutiny.Uni<
                  io.casehub.engine.plan.DagPlan<io.casehub.engine.plan.TaskNode.LeafTask<Object>>>
              decompose(
                  io.casehub.engine.plan.TaskNode<Object> task,
                  io.casehub.engine.plan.DecompositionContext<Object> ctx) {
            return io.smallrye.mutiny.Uni.createFrom().item(originalPlan);
          }

          @Override
          public io.smallrye.mutiny.Uni<
                  io.casehub.engine.plan.DagPlan<io.casehub.engine.plan.TaskNode.LeafTask<Object>>>
              replan(
                  io.casehub.engine.plan.TaskNode<Object> task,
                  io.casehub.engine.plan.DecompositionContext<Object> ctx,
                  io.casehub.engine.plan.ReplanContext<Object> replanCtx) {
            return io.smallrye.mutiny.Uni.createFrom().item(revisedPlan);
          }
        };

    var rootTask = new io.casehub.engine.plan.TaskNode.CompoundTask<>("goal", java.util.List.of());
    var failurePolicy =
        new io.casehub.blocks.agentic.FailurePolicy(
            io.casehub.blocks.agentic.FailurePolicy.RoutingFailureAction.FAIL,
            io.casehub.blocks.agentic.FailurePolicy.AggregationFailureAction.FAIL,
            io.casehub.blocks.agentic.FailurePolicy.defaults().agentRetry(),
            new io.casehub.blocks.agentic.FailurePolicy.ReplanPolicy(
                2, io.casehub.blocks.agentic.FailurePolicy.RoutingFailureAction.FAIL));

    var model =
        Patterns.<Object>htn()
            .decompose(decomposition)
            .agents(
                new io.casehub.blocks.agentic.RoutingCandidate(failAgent, null),
                new io.casehub.blocks.agentic.RoutingCandidate(recoveryAgent, null))
            .build();
    model =
        new io.casehub.blocks.agentic.model.ExecutionModel<>(
            model.routing(),
            model.decomposition(),
            model.activation(),
            model.aggregation(),
            model.termination(),
            model.candidateSupplier(),
            failurePolicy,
            model.listeners(),
            model.task(),
            model.patternType());

    var fn = new PatternWorkerFunction(model, PatternType.HTN, false, null, rootTask);
    var context = mock(WorkerContext.class);
    when(context.caseId()).thenReturn(UUID.randomUUID());
    var metadata = new ExecutionMetadata("test-worker", null);

    HandlerResult result = handler.execute(fn, Map.of(), context, 60000, metadata);
    assertThat(result.result().outcome()).isInstanceOf(WorkerOutcome.Success.class);
  }
}
