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

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkloadSignalProviderTest {

  private final WorkloadSignalProvider provider = new WorkloadSignalProvider();

  @Test
  void id_isWorkload() {
    assertThat(provider.id()).isEqualTo("workload");
  }

  @Test
  void zeroJobs_scoresOne() {
    var result = provider.evaluate(ctx(), List.of(candidate("a", 0)));
    assertThat(result).isNotNull();
    var signal = (RoutingSignal.CandidateSignal.Score) result.candidates().get("a");
    assertThat(signal.value()).isEqualTo(1.0);
  }

  @Test
  void moreJobs_lowerScore() {
    var result = provider.evaluate(ctx(), List.of(candidate("a", 0), candidate("b", 3)));
    var scoreA = ((RoutingSignal.CandidateSignal.Score) result.candidates().get("a")).value();
    var scoreB = ((RoutingSignal.CandidateSignal.Score) result.candidates().get("b")).value();
    assertThat(scoreA).isGreaterThan(scoreB);
    assertThat(scoreB).isCloseTo(0.25, within(0.001));
  }

  @Test
  void allCandidatesScored() {
    var result =
        provider.evaluate(
            ctx(), List.of(candidate("a", 0), candidate("b", 1), candidate("c", 5)));
    assertThat(result.candidates()).containsOnlyKeys("a", "b", "c");
  }

  @Test
  void rationale_includesLoadCount() {
    var result = provider.evaluate(ctx(), List.of(candidate("a", 3)));
    var signal = (RoutingSignal.CandidateSignal.Score) result.candidates().get("a");
    assertThat(signal.rationale()).isEqualTo("load 3");
  }

  private static AgentCandidate candidate(String id, int jobs) {
    return new AgentCandidate(id, Set.of(), jobs, AgentHealth.READY, null, null);
  }

  private static AgentRoutingContext ctx() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", List.of(), null, null);
  }
}
