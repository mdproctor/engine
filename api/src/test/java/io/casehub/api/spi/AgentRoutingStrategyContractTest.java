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

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRoutingStrategyContractTest {

  @Test
  void interface_hasSelectMethod_returningUni() throws Exception {
    final var method =
        AgentRoutingStrategy.class.getMethod("select", AgentRoutingContext.class, List.class);
    assertThat(method).isNotNull();
    assertThat(method.getReturnType()).isEqualTo(Uni.class);
  }

  @Test
  void interface_isNotAnnotatedFunctional() {
    assertThat(AgentRoutingStrategy.class.isAnnotationPresent(FunctionalInterface.class)).isFalse();
  }

  @Test
  void agentRoutingContext_exposesAllThreeFields() {
    final UUID caseId = UUID.randomUUID();
    final var caseContext = NullNode.instance;
    final AgentRoutingContext ctx = new AgentRoutingContext(caseId, "data-analysis", caseContext);

    assertThat(ctx.caseId()).isEqualTo(caseId);
    assertThat(ctx.capabilityName()).isEqualTo("data-analysis");
    assertThat(ctx.caseContext()).isSameAs(caseContext);
  }

  @Test
  void agentCandidate_exposesAllFiveFields() {
    final AgentCandidate candidate =
        new AgentCandidate("agent-1", Set.of("research", "analysis"), 2, AgentHealth.READY, null);

    assertThat(candidate.workerId()).isEqualTo("agent-1");
    assertThat(candidate.capabilities()).containsExactlyInAnyOrder("research", "analysis");
    assertThat(candidate.runningJobs()).isEqualTo(2);
    assertThat(candidate.health()).isEqualTo(AgentHealth.READY);
    assertThat(candidate.agentDescriptor()).isNull();
  }

  @Test
  void agentHealth_hasExpectedValues() {
    assertThat(AgentHealth.values())
        .containsExactlyInAnyOrder(
            AgentHealth.READY, AgentHealth.EPISTEMICALLY_WEAK, AgentHealth.DEGRADED);
  }

  @Test
  void implementation_canReturnAssigned() {
    final AgentRoutingStrategy strategy =
        (ctx, candidates) ->
            candidates.isEmpty()
                ? Uni.createFrom().item(AgentAssignment.unresolvable())
                : Uni.createFrom().item(AgentAssignment.assign(candidates.get(0).workerId()));

    final UUID caseId = UUID.randomUUID();
    final AgentRoutingContext ctx = new AgentRoutingContext(caseId, "research", NullNode.instance);
    final AgentCandidate candidate =
        new AgentCandidate("agent-x", Set.of("research"), 0, AgentHealth.READY, null);

    final AgentAssignment result = strategy.select(ctx, List.of(candidate)).await().indefinitely();

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-x");
  }

  @Test
  void implementation_emptyCandidates_returnsUnresolvable() {
    final AgentRoutingStrategy strategy =
        (ctx, candidates) -> Uni.createFrom().item(AgentAssignment.unresolvable());
    final AgentRoutingContext ctx =
        new AgentRoutingContext(UUID.randomUUID(), "research", NullNode.instance);

    final AgentAssignment result = strategy.select(ctx, List.of()).await().indefinitely();

    assertThat(result).isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void implementation_canReturnEscalateToOversight() {
    final AgentRoutingStrategy strategy =
        (ctx, candidates) -> Uni.createFrom().item(AgentAssignment.escalate(ctx.capabilityName()));
    final AgentRoutingContext ctx =
        new AgentRoutingContext(UUID.randomUUID(), "sensitive-review", NullNode.instance);

    final AgentAssignment result = strategy.select(ctx, List.of()).await().indefinitely();

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).capabilityName())
        .isEqualTo("sensitive-review");
  }
}
