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
    ctx = new AgentRoutingContext(UUID.randomUUID(), "data-analysis");
  }

  @Test
  void emptyCandidates_returnsNoOp() {
    assertThat(strategy.select(ctx, List.of()).isNoOp()).isTrue();
  }

  @Test
  void singleCandidate_isSelected() {
    final AgentCandidate only = candidate("agent-1", 3);
    final AgentAssignment result = strategy.select(ctx, List.of(only));
    assertThat(result.workerId()).isEqualTo("agent-1");
  }

  @Test
  void selectsLeastLoaded_byRunningJobs() {
    final AgentCandidate busy = candidate("agent-busy", 5);
    final AgentCandidate idle = candidate("agent-idle", 0);
    final AgentCandidate mid = candidate("agent-mid", 2);

    final AgentAssignment result = strategy.select(ctx, List.of(busy, mid, idle));
    assertThat(result.workerId()).isEqualTo("agent-idle");
  }

  @Test
  void tie_firstInListWins() {
    // deterministic tiebreaker: list order
    final AgentCandidate first = candidate("agent-a", 2);
    final AgentCandidate second = candidate("agent-b", 2);

    final AgentAssignment result = strategy.select(ctx, List.of(first, second));
    assertThat(result.workerId()).isEqualTo("agent-a");
  }

  @Test
  void healthDoesNotAffectSelection_leastLoadedWinsRegardlessOfHealth() {
    // LeastLoadedAgentStrategy ignores health — TrustWeightedAgentStrategy handles demotion
    final AgentCandidate readyBusy = candidate("ready-busy", 5, AgentHealth.READY);
    final AgentCandidate weakIdle = candidate("weak-idle", 0, AgentHealth.EPISTEMICALLY_WEAK);

    final AgentAssignment result = strategy.select(ctx, List.of(readyBusy, weakIdle));
    assertThat(result.workerId()).isEqualTo("weak-idle");
  }

  @Test
  void zeroJobs_idle_isPreferred() {
    final AgentCandidate oneJob = candidate("one", 1);
    final AgentCandidate zeroJobs = candidate("zero", 0);

    assertThat(strategy.select(ctx, List.of(oneJob, zeroJobs)).workerId()).isEqualTo("zero");
  }

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return candidate(workerId, jobs, AgentHealth.READY);
  }

  private static AgentCandidate candidate(
      final String workerId, final int jobs, final AgentHealth health) {
    return new AgentCandidate(workerId, Set.of("data-analysis"), jobs, health);
  }
}
