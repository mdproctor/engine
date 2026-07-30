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
import io.casehub.api.spi.routing.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComposableAgentRoutingStrategyTest {

  @Test
  void singleProvider_selectsHighestScore() {
    var provider =
        testProvider(
            "workload",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(0.8, "low load"),
                "agent-b", new RoutingSignal.CandidateSignal.Score(0.3, "high load")));
    var strategy = composable(provider);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a"), candidate("agent-b")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-a");
  }

  @Test
  void excludeRemovesCandidate() {
    var provider =
        testProvider(
            "trust",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(0.9, "qualified"),
                "agent-b", new RoutingSignal.CandidateSignal.Exclude("phase 2b")));
    var strategy = composable(provider);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a"), candidate("agent-b")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-a");
  }

  @Test
  void allExcluded_returnsUnresolvable() {
    var provider =
        testProvider(
            "trust", Map.of("agent-a", new RoutingSignal.CandidateSignal.Exclude("phase 2b")));
    var strategy = composable(provider);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a")));

    assertThat(result).isInstanceOf(RoutingResult.Unresolvable.class);
    assertThat(((RoutingResult.Unresolvable) result).reason()).contains("phase 2b");
  }

  @Test
  void escalateSignal_producesEscalatedResult() {
    var provider =
        testProvider(
            "trust",
            Map.of(
                "agent-a",
                new RoutingSignal.CandidateSignal.Escalate(
                    EscalationReason.NO_QUALIFIED_AGENT, "bootstrap only")));
    var strategy = composable(provider);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a")));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
  }

  @Test
  void weightedBlending_twoProviders() {
    var p1 =
        testProvider(
            "trust",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(0.2, null),
                "agent-b", new RoutingSignal.CandidateSignal.Score(0.9, null)));
    var p2 =
        testProvider(
            "workload",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(1.0, null),
                "agent-b", new RoutingSignal.CandidateSignal.Score(0.1, null)));
    var strategy = composable(p1, p2);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a"), candidate("agent-b")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-a");
  }

  @Test
  void absentCandidate_weightRedistributed() {
    var p1 =
        testProvider(
            "trust",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(0.8, null),
                "agent-b", new RoutingSignal.CandidateSignal.Score(0.7, null)));
    var p2 =
        testProvider(
            "personality", Map.of("agent-b", new RoutingSignal.CandidateSignal.Score(0.9, null)));
    var strategy = composable(p1, p2);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a"), candidate("agent-b")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
  }

  @Test
  void allAbstain_neutralScore() {
    RoutingSignalProvider provider =
        new RoutingSignalProvider() {
          @Override
          public String id() {
            return "empty";
          }

          @Override
          public RoutingSignal evaluate(AgentRoutingContext c, List<AgentCandidate> e) {
            return null;
          }
        };
    var strategy = composable(provider);

    var result = strategy.select(ctx(null), List.of(candidate("agent-a")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
  }

  @Test
  void perCaseWeights_onlyNamedProvidersUsed() {
    var p1 =
        testProvider(
            "trust", Map.of("agent-a", new RoutingSignal.CandidateSignal.Score(0.1, null)));
    var p2 =
        testProvider(
            "workload", Map.of("agent-a", new RoutingSignal.CandidateSignal.Score(0.9, null)));
    var strategy = composable(p1, p2);

    var weights = Map.of("workload", 1.0);
    var result = strategy.select(ctx(weights), List.of(candidate("agent-a")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().reason()).contains("composable score");
  }

  @Test
  void emptyCandidates_returnsUnresolvable() {
    var strategy = composable();
    var result = strategy.select(ctx(null), List.of());
    assertThat(result).isInstanceOf(RoutingResult.Unresolvable.class);
  }

  @Test
  void mixedExcludeAndScore_excludedFiltered() {
    var provider =
        testProvider(
            "trust",
            Map.of(
                "agent-a", new RoutingSignal.CandidateSignal.Score(0.5, null),
                "agent-b", new RoutingSignal.CandidateSignal.Exclude("trust too low"),
                "agent-c", new RoutingSignal.CandidateSignal.Score(0.9, null)));
    var strategy = composable(provider);

    var result =
        strategy.select(
            ctx(null), List.of(candidate("agent-a"), candidate("agent-b"), candidate("agent-c")));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-c");
  }

  // --- helpers ---

  private static ComposableAgentRoutingStrategy composable(RoutingSignalProvider... providers) {
    return new ComposableAgentRoutingStrategy(new RoutingSignalAssembler(List.of(providers)));
  }

  private static RoutingSignalProvider testProvider(
      String id, Map<String, RoutingSignal.CandidateSignal> signals) {
    return new RoutingSignalProvider() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public RoutingSignal evaluate(AgentRoutingContext c, List<AgentCandidate> e) {
        return new RoutingSignal(signals);
      }
    };
  }

  private static AgentCandidate candidate(String workerId) {
    return new AgentCandidate(workerId, Set.of(), 0, AgentHealth.READY, null, null);
  }

  private static AgentRoutingContext ctx(Map<String, Double> weights) {
    return new AgentRoutingContext(
        UUID.randomUUID(),
        "test-capability",
        NullNode.getInstance(),
        "tenant-1",
        List.of(),
        null,
        weights);
  }
}
