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
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRoutingStrategyContractTest {

  @Test
  void interface_hasSelectMethod_returningRoutingResult() throws Exception {
    final var method =
        AgentRoutingStrategy.class.getMethod("select", AgentRoutingContext.class, List.class);
    assertThat(method).isNotNull();
    assertThat(method.getReturnType()).isEqualTo(RoutingResult.class);
  }

  @Test
  void interface_isNotAnnotatedFunctional() {
    assertThat(AgentRoutingStrategy.class.isAnnotationPresent(FunctionalInterface.class)).isFalse();
  }

  @Test
  void agentRoutingContext_exposesAllFourFields() {
    final UUID caseId = UUID.randomUUID();
    final var caseContext = NullNode.instance;
    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            caseId, "data-analysis", caseContext, "test-tenant", List.of(), null, null);

    assertThat(ctx.caseId()).isEqualTo(caseId);
    assertThat(ctx.capabilityName()).isEqualTo("data-analysis");
    assertThat(ctx.caseContext()).isSameAs(caseContext);
    assertThat(ctx.tenancyId()).isEqualTo("test-tenant");
  }

  @Test
  void agentCandidate_exposesAllFields() {
    final AgentCandidate candidate =
        new AgentCandidate(
            "agent-1", Set.of("research", "analysis"), 2, AgentHealth.READY, null, null);

    assertThat(candidate.workerId()).isEqualTo("agent-1");
    assertThat(candidate.capabilities()).containsExactlyInAnyOrder("research", "analysis");
    assertThat(candidate.runningJobs()).isEqualTo(2);
    assertThat(candidate.health()).isEqualTo(AgentHealth.READY);
    assertThat(candidate.agentDescriptor()).isNull();
    assertThat(candidate.matchDegree()).isNull();
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
        new AgentRoutingStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public RoutingResult select(AgentRoutingContext ctx, List<AgentCandidate> candidates) {
            return candidates.isEmpty()
                ? RoutingResult.unresolvable("no candidates available")
                : RoutingResult.assigned(candidates.get(0).workerId(), "selected by test strategy");
          }
        };

    final UUID caseId = UUID.randomUUID();
    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            caseId, "research", NullNode.instance, "test-tenant", List.of(), null, null);
    final AgentCandidate candidate =
        new AgentCandidate("agent-x", Set.of("research"), 0, AgentHealth.READY, null, null);

    final RoutingResult result = strategy.select(ctx, List.of(candidate));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-x");
    assertThat(((RoutingResult.Selected) result).single().reason())
        .isEqualTo("selected by test strategy");
  }

  @Test
  void implementation_emptyCandidates_returnsUnresolvable() {
    final AgentRoutingStrategy strategy =
        new AgentRoutingStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public RoutingResult select(AgentRoutingContext ctx, List<AgentCandidate> candidates) {
            return RoutingResult.unresolvable("no candidates available");
          }
        };
    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", List.of(), null, null);

    final RoutingResult result = strategy.select(ctx, List.of());

    assertThat(result).isInstanceOf(RoutingResult.Unresolvable.class);
    assertThat(((RoutingResult.Unresolvable) result).reason()).isEqualTo("no candidates available");
  }

  @Test
  void implementation_canReturnEscalateToOversight() {
    final AgentRoutingStrategy strategy =
        new AgentRoutingStrategy() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public RoutingResult select(AgentRoutingContext ctx, List<AgentCandidate> candidates) {
            return RoutingResult.escalate(
                ctx.capabilityName(),
                EscalationReason.BORDERLINE_STALEMATE,
                "all candidates borderline for capability '%s' — oversight required"
                    .formatted(ctx.capabilityName()));
          }
        };
    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            UUID.randomUUID(),
            "sensitive-review",
            NullNode.instance,
            "test-tenant",
            List.of(),
            null,
            null);

    final RoutingResult result = strategy.select(ctx, List.of());

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).capabilityName()).isEqualTo("sensitive-review");
    assertThat(((RoutingResult.Escalated) result).reason()).contains("sensitive-review");
  }
}
