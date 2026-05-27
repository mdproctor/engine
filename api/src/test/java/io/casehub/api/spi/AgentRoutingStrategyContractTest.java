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
package io.casehub.api.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRoutingStrategyContractTest {

  @Test
  void interface_hasSelectMethod() throws Exception {
    assertThat(
            AgentRoutingStrategy.class.getMethod("select", AgentRoutingContext.class, List.class))
        .isNotNull();
  }

  @Test
  void agentAssignment_noOp_isNoOp() {
    assertThat(AgentAssignment.noOp().isNoOp()).isTrue();
  }

  @Test
  void agentAssignment_withWorkerId_isNotNoOp() {
    assertThat(new AgentAssignment("worker-1").isNoOp()).isFalse();
  }

  @Test
  void agentAssignment_noOp_workerIdIsNull() {
    assertThat(AgentAssignment.noOp().workerId()).isNull();
  }

  @Test
  void agentRoutingContext_exposesFields() {
    final UUID caseId = UUID.randomUUID();
    final AgentRoutingContext ctx = new AgentRoutingContext(caseId, "data-analysis");

    assertThat(ctx.caseId()).isEqualTo(caseId);
    assertThat(ctx.capabilityName()).isEqualTo("data-analysis");
  }

  @Test
  void agentCandidate_exposesFields() {
    final AgentCandidate candidate =
        new AgentCandidate("agent-1", Set.of("research", "analysis"), 2, AgentHealth.READY);

    assertThat(candidate.workerId()).isEqualTo("agent-1");
    assertThat(candidate.capabilities()).containsExactlyInAnyOrder("research", "analysis");
    assertThat(candidate.runningJobs()).isEqualTo(2);
    assertThat(candidate.health()).isEqualTo(AgentHealth.READY);
  }

  @Test
  void agentHealth_hasExpectedValues() {
    assertThat(AgentHealth.values())
        .containsExactlyInAnyOrder(
            AgentHealth.READY, AgentHealth.EPISTEMICALLY_WEAK, AgentHealth.DEGRADED);
  }

  @Test
  void anonymousImplementation_compilesAndSelectsCorrectly() {
    final AgentRoutingStrategy strategy =
        (ctx, candidates) ->
            candidates.isEmpty()
                ? AgentAssignment.noOp()
                : new AgentAssignment(candidates.get(0).workerId());

    final UUID caseId = UUID.randomUUID();
    final AgentRoutingContext ctx = new AgentRoutingContext(caseId, "research");
    final AgentCandidate candidate =
        new AgentCandidate("agent-x", Set.of("research"), 0, AgentHealth.READY);

    final AgentAssignment result = strategy.select(ctx, List.of(candidate));

    assertThat(result.workerId()).isEqualTo("agent-x");
  }

  @Test
  void anonymousImplementation_emptyCandidates_returnsNoOp() {
    final AgentRoutingStrategy strategy = (ctx, candidates) -> AgentAssignment.noOp();
    final AgentRoutingContext ctx = new AgentRoutingContext(UUID.randomUUID(), "research");

    assertThat(strategy.select(ctx, List.of()).isNoOp()).isTrue();
  }
}
