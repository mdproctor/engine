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
import io.casehub.api.model.CognitiveDemand;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionValue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalitySignalProviderTest {

  @Test
  void id_isPersonality() {
    var provider = new PersonalitySignalProvider(noOpHealth());
    assertThat(provider.id()).isEqualTo("personality");
  }

  @Test
  void noCognitiveDemand_returnsNull() {
    var provider = new PersonalitySignalProvider(noOpHealth());
    var result = provider.evaluate(ctx(null), List.of(candidateWithProfile("a", Map.of("Ti", 0.5))));
    assertThat(result).isNull();
  }

  @Test
  void noDispositionProfile_absentFromMap() {
    var provider = new PersonalitySignalProvider(noOpHealth());
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Ne", 0.3, "Si", 0.1));
    var result = provider.evaluate(ctx(demand), List.of(candidateWithoutProfile("a")));
    assertThat(result).isNull();
  }

  @Test
  void perfectAlignment_scoresHigh() {
    var weights = Map.of("Ti", 0.6, "Ne", 0.3, "Si", 0.1);
    var health = mockHealth(weights);
    var provider = new PersonalitySignalProvider(health);
    var demand = new CognitiveDemand(Map.of("Ti", 0.6, "Ne", 0.3, "Si", 0.1));
    var result = provider.evaluate(ctx(demand), List.of(candidateWithProfile("a", weights)));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal).value()).isCloseTo(1.0, within(0.01));
  }

  @Test
  void orthogonalProfile_scoresLow() {
    var health = mockHealth(Map.of("Fe", 0.8));
    var provider = new PersonalitySignalProvider(health);
    var demand = new CognitiveDemand(Map.of("Ti", 1.0));
    var result = provider.evaluate(ctx(demand), List.of(candidateWithProfile("a", Map.of("Fe", 0.8))));

    assertThat(result).isNotNull();
    var score = ((RoutingSignal.CandidateSignal.Score) result.candidates().get("a")).value();
    assertThat(score).isCloseTo(0.0, within(0.01));
  }

  @Test
  void cosineSimilarity_correctValue() {
    double expected = 0.56 / (Math.sqrt(0.52) * Math.sqrt(0.68));
    double actual =
        PersonalitySignalProvider.cosineSimilarity(
            Map.of("Ti", 0.6, "Ne", 0.4), Map.of("Ti", 0.8, "Ne", 0.2));
    assertThat(actual).isCloseTo(expected, within(0.001));
  }

  @Test
  void multipleCandidates_onlyProfiledOnesScored() {
    var health = mockHealth(Map.of("Ti", 0.5));
    var provider = new PersonalitySignalProvider(health);
    var demand = new CognitiveDemand(Map.of("Ti", 0.5, "Ne", 0.3, "Si", 0.2));
    var result =
        provider.evaluate(
            ctx(demand),
            List.of(
                candidateWithProfile("a", Map.of("Ti", 0.5)),
                candidateWithoutProfile("b"),
                candidateWithProfile("c", Map.of("Ne", 0.5))));

    assertThat(result).isNotNull();
    assertThat(result.candidates()).containsOnlyKeys("a", "c");
  }

  // --- helpers ---

  private static DispositionHealth noOpHealth() {
    return (descriptor, ctx) -> new DispositionHealth.DispositionStatus.Aligned(Map.of());
  }

  private static DispositionHealth mockHealth(Map<String, Double> effectiveWeights) {
    return (descriptor, ctx) -> new DispositionHealth.DispositionStatus.Aligned(effectiveWeights);
  }

  private static AgentCandidate candidateWithProfile(String id, Map<String, Double> weights) {
    var values =
        weights.entrySet().stream()
            .map(e -> new DispositionValue(e.getKey(), e.getValue()))
            .toList();
    var disposition = AgentDisposition.builder().dispositionProfile(values).build();
    var descriptor =
        AgentDescriptor.builder()
            .agentId(id)
            .name(id)
            .slot("test")
            .tenancyId("t1")
            .disposition(disposition)
            .build();
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, descriptor, null);
  }

  private static AgentCandidate candidateWithoutProfile(String id) {
    return new AgentCandidate(id, Set.of(), 0, AgentHealth.READY, null, null);
  }

  private static AgentRoutingContext ctx(CognitiveDemand demand) {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", List.of(), demand, null);
  }
}
