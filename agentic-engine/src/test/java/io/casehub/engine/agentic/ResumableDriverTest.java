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

import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.ExecutionResult;
import io.casehub.blocks.agentic.pattern.Patterns;
import io.casehub.engine.plan.execution.AgentResultRecord;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResumableDriverTest {

  @Test
  void resumesFromCheckpointIteration() {
    var invocationCount = new AtomicInteger(0);
    AgentRef agent1 =
        AgentRef.external(
            "a1",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("step", invocationCount.incrementAndGet()))));
    AgentRef agent2 =
        AgentRef.external(
            "a2",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("step", invocationCount.incrementAndGet()))));
    AgentRef agent3 =
        AgentRef.external(
            "a3",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("step", invocationCount.incrementAndGet()))));

    var checkpoint =
        new PatternExecutionCheckpoint(
            UUID.randomUUID(),
            "worker",
            2,
            List.of(
                AgentResultRecord.of("a1", Map.of("step", 1), 100L, "SUCCESS"),
                AgentResultRecord.of("a2", Map.of("step", 2), 100L, "SUCCESS")),
            Set.of(),
            null,
            0,
            Map.of(
                "activationCounts", Map.of("a1", 1, "a2", 1),
                "consecutiveIdleCounts", Map.of()));

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>sequence().agents(agent1, agent2, agent3).build();

    var driver =
        new ResumableDriver<Map<String, Object>>(
            io.casehub.blocks.agentic.model.AgentInvoker.defaultInvoker(), checkpoint);
    var result = driver.execute(model, Map.of()).await().indefinitely();

    assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    assertThat(invocationCount.get()).as("only the 3rd agent should be invoked").isEqualTo(1);
  }

  @Test
  void freshExecutionWhenNoCheckpoint() {
    var invocationCount = new AtomicInteger(0);
    AgentRef agent1 =
        AgentRef.external(
            "a1",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("step", invocationCount.incrementAndGet()))));
    AgentRef agent2 =
        AgentRef.external(
            "a2",
            ctx ->
                CompletableFuture.completedFuture(
                    AgentResult.success(null, Map.of("step", invocationCount.incrementAndGet()))));

    var checkpoint =
        new PatternExecutionCheckpoint(
            UUID.randomUUID(), "worker", 0, List.of(), Set.of(), null, 0, Map.of());

    ExecutionModel<Map<String, Object>> model =
        Patterns.<Map<String, Object>>sequence().agents(agent1, agent2).build();

    var driver =
        new ResumableDriver<Map<String, Object>>(
            io.casehub.blocks.agentic.model.AgentInvoker.defaultInvoker(), checkpoint);
    var result = driver.execute(model, Map.of()).await().indefinitely();

    assertThat(result).isInstanceOf(ExecutionResult.Completed.class);
    assertThat(invocationCount.get()).isEqualTo(2);
  }
}
