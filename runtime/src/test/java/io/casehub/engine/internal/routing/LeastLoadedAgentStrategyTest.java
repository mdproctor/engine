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
package io.casehub.engine.internal.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LeastLoadedAgentStrategyTest {

  private LeastLoadedAgentStrategy strategy;
  private AgentRoutingContext ctx;

  @BeforeEach
  void setUp() {
    strategy = new LeastLoadedAgentStrategy();
    ctx = new AgentRoutingContext(UUID.randomUUID(), "data-analysis", NullNode.instance);
  }

  @Test
  void emptyCandidates_returnsUnresolvable() {
    assertThat(strategy.select(ctx, List.of()).await().indefinitely())
        .isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void singleCandidate_isSelected() {
    final AgentAssignment result =
        strategy.select(ctx, List.of(candidate("agent-1", 3))).await().indefinitely();
    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-1");
  }

  @Test
  void selectsLeastLoaded_byRunningJobs() {
    final AgentAssignment result =
        strategy
            .select(
                ctx,
                List.of(
                    candidate("agent-busy", 5),
                    candidate("agent-mid", 2),
                    candidate("agent-idle", 0)))
            .await()
            .indefinitely();
    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-idle");
  }

  @Test
  void tie_firstInListWins() {
    final AgentAssignment result =
        strategy
            .select(ctx, List.of(candidate("agent-a", 2), candidate("agent-b", 2)))
            .await()
            .indefinitely();
    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-a");
  }

  @Test
  void healthDoesNotAffectSelection_leastLoadedWinsRegardlessOfHealth() {
    final AgentAssignment result =
        strategy
            .select(
                ctx,
                List.of(
                    candidate("ready-busy", 5, AgentHealth.READY),
                    candidate("weak-idle", 0, AgentHealth.EPISTEMICALLY_WEAK)))
            .await()
            .indefinitely();
    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("weak-idle");
  }

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return candidate(workerId, jobs, AgentHealth.READY);
  }

  private static AgentCandidate candidate(
      final String workerId, final int jobs, final AgentHealth health) {
    return new AgentCandidate(workerId, Set.of("data-analysis"), jobs, health, null);
  }
}
