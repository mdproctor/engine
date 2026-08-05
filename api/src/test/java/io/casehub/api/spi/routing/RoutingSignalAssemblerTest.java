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
package io.casehub.api.spi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.annotation.Priority;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoutingSignalAssemblerTest {

  @Test
  void noProviders_returnsEmptyMap() {
    var assembler = new RoutingSignalAssembler(List.of());
    assertThat(assembler.assemble(context(), candidates())).isEmpty();
  }

  @Test
  void singleProvider_returnsSignalKeyedById() {
    var provider = provider("trust", signal("agent-1", 0.8, "high trust"));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust");
    assertThat(
            ((RoutingSignal.CandidateSignal.Score) result.get("trust").candidates().get("agent-1"))
                .value())
        .isEqualTo(0.8);
    assertThat(
            ((RoutingSignal.CandidateSignal.Score) result.get("trust").candidates().get("agent-1"))
                .rationale())
        .isEqualTo("high trust");
  }

  @Test
  void multipleProviders_allContribute() {
    var p1 = provider("trust", signal("agent-1", 0.9, null));
    var p2 = provider("experience", signal("agent-1", 0.7, "3 prior cases"));
    var assembler = new RoutingSignalAssembler(List.of(p1, p2));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust", "experience");
  }

  @Test
  void providerReturnsNull_skipped() {
    var nullProvider = provider("empty", null);
    var realProvider = provider("trust", signal("agent-1", 0.5, null));
    var assembler = new RoutingSignalAssembler(List.of(nullProvider, realProvider));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust");
  }

  @Test
  void throwingProvider_loggedAndSkipped_othersSurvive() {
    RoutingSignalProvider boom =
        new RoutingSignalProvider() {
          @Override
          public String id() {
            return "boom";
          }

          @Override
          public RoutingSignal evaluate(AgentRoutingContext ctx, List<AgentCandidate> eligible) {
            throw new RuntimeException("provider failure");
          }
        };
    var ok = provider("trust", signal("agent-1", 0.6, null));
    var assembler = new RoutingSignalAssembler(List.of(boom, ok));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust");
    assertThat(
            ((RoutingSignal.CandidateSignal.Score) result.get("trust").candidates().get("agent-1"))
                .value())
        .isEqualTo(0.6);
  }

  @Test
  void scoreAboveOne_clampedToOne() {
    var provider = provider("over", signal("agent-1", 1.5, null));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    assertThat(
            ((RoutingSignal.CandidateSignal.Score) result.get("over").candidates().get("agent-1"))
                .value())
        .isEqualTo(1.0);
  }

  @Test
  void scoreBelowZero_clampedToZero() {
    var provider = provider("under", signal("agent-1", -0.3, null));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    assertThat(
            ((RoutingSignal.CandidateSignal.Score) result.get("under").candidates().get("agent-1"))
                .value())
        .isEqualTo(0.0);
  }

  @Test
  void inRangeScores_notClamped() {
    var candidates = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    candidates.put("a", new RoutingSignal.CandidateSignal.Score(0.0, null));
    candidates.put("b", new RoutingSignal.CandidateSignal.Score(1.0, null));
    candidates.put("c", new RoutingSignal.CandidateSignal.Score(0.5, "mid"));
    var provider = provider("valid", new RoutingSignal(candidates));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    var signal = result.get("valid");
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("a")).value())
        .isEqualTo(0.0);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("b")).value())
        .isEqualTo(1.0);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("c")).value())
        .isEqualTo(0.5);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("c")).rationale())
        .isEqualTo("mid");
  }

  @Test
  void priorityOrdering_lowerValueComesFirst() {
    @Priority(100)
    class HighPriority implements RoutingSignalProvider {
      @Override
      public String id() {
        return "high";
      }

      @Override
      public RoutingSignal evaluate(AgentRoutingContext ctx, List<AgentCandidate> eligible) {
        return RoutingSignalAssemblerTest.signal("agent-1", 0.9, null);
      }
    }

    @Priority(200)
    class LowPriority implements RoutingSignalProvider {
      @Override
      public String id() {
        return "low";
      }

      @Override
      public RoutingSignal evaluate(AgentRoutingContext ctx, List<AgentCandidate> eligible) {
        return RoutingSignalAssemblerTest.signal("agent-1", 0.1, null);
      }
    }

    class NoPriority implements RoutingSignalProvider {
      @Override
      public String id() {
        return "none";
      }

      @Override
      public RoutingSignal evaluate(AgentRoutingContext ctx, List<AgentCandidate> eligible) {
        return RoutingSignalAssemblerTest.signal("agent-1", 0.5, null);
      }
    }

    var assembler =
        new RoutingSignalAssembler(
            List.of(new NoPriority(), new LowPriority(), new HighPriority()));

    var result = assembler.assemble(context(), candidates());

    assertThat(result.keySet()).containsExactly("high", "low", "none");
  }

  @Test
  void clampPreservesReason() {
    var provider = provider("clamped", signal("agent-1", 2.0, "important reason"));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    var cs =
        (RoutingSignal.CandidateSignal.Score) result.get("clamped").candidates().get("agent-1");
    assertThat(cs.value()).isEqualTo(1.0);
    assertThat(cs.rationale()).isEqualTo("important reason");
  }

  @Test
  void mixedClampedAndValid_onlyOutOfRangeClamped() {
    var candidates = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    candidates.put("ok", new RoutingSignal.CandidateSignal.Score(0.7, null));
    candidates.put("over", new RoutingSignal.CandidateSignal.Score(1.2, null));
    candidates.put("under", new RoutingSignal.CandidateSignal.Score(-0.1, null));
    var provider = provider("mixed", new RoutingSignal(candidates));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    var signal = result.get("mixed");
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("ok")).value())
        .isEqualTo(0.7);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("over")).value())
        .isEqualTo(1.0);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal.candidates().get("under")).value())
        .isEqualTo(0.0);
  }

  @Test
  void allProvidersReturnNull_returnsEmptyMap() {
    var p1 = provider("a", null);
    var p2 = provider("b", null);
    var assembler = new RoutingSignalAssembler(List.of(p1, p2));

    assertThat(assembler.assemble(context(), candidates())).isEmpty();
  }

  private static AgentRoutingContext context() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "analysis", NullNode.instance, "test-tenant", List.of(), null, null);
  }

  private static List<AgentCandidate> candidates() {
    return List.of(
        new AgentCandidate("agent-1", Set.of("analysis"), 0, AgentHealth.READY, null, null, null));
  }

  private static RoutingSignal signal(String workerId, double score, String reason) {
    return new RoutingSignal(
        Map.of(workerId, new RoutingSignal.CandidateSignal.Score(score, reason)));
  }

  private static RoutingSignalProvider provider(String id, RoutingSignal signal) {
    return new RoutingSignalProvider() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public RoutingSignal evaluate(AgentRoutingContext ctx, List<AgentCandidate> eligible) {
        return signal;
      }
    };
  }

  @Test
  void providerReturnsExclude_preservedInResult() {
    var candidates = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    candidates.put("agent-1", new RoutingSignal.CandidateSignal.Exclude("low trust"));
    var provider = provider("trust", new RoutingSignal(candidates));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust");
    var signal = result.get("trust").candidates().get("agent-1");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Exclude.class);
    assertThat(((RoutingSignal.CandidateSignal.Exclude) signal).reason()).isEqualTo("low trust");
  }

  @Test
  void providerReturnsEscalate_preservedInResult() {
    var candidates = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    candidates.put(
        "agent-1",
        new RoutingSignal.CandidateSignal.Escalate(
            EscalationReason.BORDERLINE_STALEMATE, "no suitable agents"));
    var provider = provider("trust", new RoutingSignal(candidates));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    assertThat(result).containsOnlyKeys("trust");
    var signal = result.get("trust").candidates().get("agent-1");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Escalate.class);
    var escalate = (RoutingSignal.CandidateSignal.Escalate) signal;
    assertThat(escalate.reason()).isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
    assertThat(escalate.rationale()).isEqualTo("no suitable agents");
  }

  @Test
  void mixedSignalTypes_allPreserved() {
    var candidates = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    candidates.put("agent-1", new RoutingSignal.CandidateSignal.Score(0.8, "high score"));
    candidates.put("agent-2", new RoutingSignal.CandidateSignal.Exclude("excluded"));
    candidates.put(
        "agent-3",
        new RoutingSignal.CandidateSignal.Escalate(
            EscalationReason.NO_QUALIFIED_AGENT, "escalated"));
    var provider = provider("mixed", new RoutingSignal(candidates));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), candidates());

    var signal = result.get("mixed");
    assertThat(signal.candidates()).hasSize(3);
    assertThat(signal.candidates().get("agent-1"))
        .isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(signal.candidates().get("agent-2"))
        .isInstanceOf(RoutingSignal.CandidateSignal.Exclude.class);
    assertThat(signal.candidates().get("agent-3"))
        .isInstanceOf(RoutingSignal.CandidateSignal.Escalate.class);
  }

  @Test
  void emptyEligibleList_assemblesWithoutError() {
    var provider = provider("trust", signal("agent-1", 0.7, null));
    var assembler = new RoutingSignalAssembler(List.of(provider));

    var result = assembler.assemble(context(), List.of());

    assertThat(result).containsOnlyKeys("trust");
  }
}
