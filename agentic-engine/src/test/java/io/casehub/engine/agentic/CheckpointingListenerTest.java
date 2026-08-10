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
import io.casehub.blocks.agentic.termination.TerminationDecision;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class CheckpointingListenerTest {

  private final List<PatternExecutionCheckpoint> saved = new ArrayList<>();

  @Test
  void writesCheckpointOnContinueTermination() {
    var listener = createListener();
    var agent = AgentRef.external("agent-1", ctx -> CompletableFuture.completedFuture(null));

    listener.onAgentResult(AgentResult.success(agent, Map.of("r", "ok")));
    listener.onTermination(TerminationDecision.Continue.INSTANCE);

    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).completedIterations()).isEqualTo(1);
    assertThat(saved.get(0).results()).hasSize(1);
    assertThat(saved.get(0).results().get(0).agentId()).isEqualTo("agent-1");
  }

  @Test
  void incrementsIterationCountOnEachContinue() {
    var listener = createListener();
    var agent = AgentRef.external("a1", ctx -> CompletableFuture.completedFuture(null));

    listener.onAgentResult(AgentResult.success(agent, "r1"));
    listener.onTermination(TerminationDecision.Continue.INSTANCE);

    listener.onAgentResult(AgentResult.success(agent, "r2"));
    listener.onTermination(TerminationDecision.Continue.INSTANCE);

    assertThat(saved).hasSize(2);
    assertThat(saved.get(1).completedIterations()).isEqualTo(2);
    assertThat(saved.get(1).results()).hasSize(2);
  }

  @Test
  void doesNotWriteCheckpointOnTerminalDecision() {
    var listener = createListener();
    var agent = AgentRef.external("a1", ctx -> CompletableFuture.completedFuture(null));

    listener.onAgentResult(AgentResult.success(agent, "r1"));
    listener.onTermination(new TerminationDecision.Complete(List.of()));

    assertThat(saved).isEmpty();
  }

  @Test
  void capturesActivationCounts() {
    var listener = createListener();
    var agent = AgentRef.external("a1", ctx -> CompletableFuture.completedFuture(null));

    listener.onActivation(agent, true);
    listener.onAgentResult(AgentResult.success(agent, "r1"));
    listener.onTermination(TerminationDecision.Continue.INSTANCE);

    var state = saved.get(0).driverState();
    @SuppressWarnings("unchecked")
    var counts = (Map<String, Integer>) state.get("activationCounts");
    assertThat(counts).containsEntry("a1", 1);
  }

  @Test
  void capturesIdleCounts() {
    var listener = createListener();
    var agent = AgentRef.external("a1", ctx -> CompletableFuture.completedFuture(null));

    listener.onActivation(agent, false);
    listener.onTermination(TerminationDecision.Continue.INSTANCE);

    var state = saved.get(0).driverState();
    @SuppressWarnings("unchecked")
    var counts = (Map<String, Integer>) state.get("consecutiveIdleCounts");
    assertThat(counts).containsEntry("a1", 1);
  }

  private CheckpointingListener createListener() {
    return new CheckpointingListener(
        UUID.randomUUID(),
        "worker-1",
        "tenant-1",
        (checkpoint, tenancyId) -> saved.add(checkpoint));
  }
}
