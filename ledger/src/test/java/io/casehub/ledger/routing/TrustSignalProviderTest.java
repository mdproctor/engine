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
package io.casehub.ledger.routing;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustSignalProviderTest {

  private TrustCandidateClassifier classifier;
  private TrustScoreSource source;
  private TrustRoutingPolicyProvider policyProvider;
  private TrustSignalProvider provider;
  private TrustRoutingPolicy defaultPolicy;
  private static final TrustRoutingPolicy BOOTSTRAP_GUARD_POLICY =
      new TrustRoutingPolicy(0.3, 5, 0.1, 0.7, Map.of(), true, null, Set.of(), 0.0);

  @BeforeEach
  void setUp() {
    classifier     = new TrustCandidateClassifier();
    source         = mock(TrustScoreSource.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    defaultPolicy  = new TrustRoutingPolicy(0.3, 5, 0.1, 0.7, Map.of(), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability(anyString())).thenReturn(defaultPolicy);
    when(source.capabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(source.decisionCount(any(), any())).thenReturn(0);
    when(source.capabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
    provider = new TrustSignalProvider(classifier, source, policyProvider);}

  @Test
  void id_isTrust() {
    assertThat(provider.id()).isEqualTo("trust");
  }

  @Test
  void qualifiedCandidate_returnsScore() {
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.of(0.8));
    when(source.decisionCount("agent-a", "cap")).thenReturn(10);
    var result = provider.evaluate(ctx(), List.of(candidate("agent-a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("agent-a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal).value()).isEqualTo(0.8);
  }

  @Test
  void bootstrapCandidate_defaultPolicy_returnsNeutralScore() {
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.empty());
    var result = provider.evaluate(ctx(), List.of(candidate("agent-a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("agent-a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(((RoutingSignal.CandidateSignal.Score) signal).value()).isEqualTo(0.5);
  }

  @Test
  void bootstrapOnly_withEscalationPolicy_returnsEscalate() {
    when(policyProvider.forCapability(anyString())).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.empty());
    var result = provider.evaluate(ctx(), List.of(candidate("agent-a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("agent-a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Escalate.class);
    assertThat(((RoutingSignal.CandidateSignal.Escalate) signal).reason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrapWithQualified_escalationPolicy_excludesBootstrap() {
    when(policyProvider.forCapability(anyString())).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.of(0.8));
    when(source.decisionCount("agent-a", "cap")).thenReturn(10);
    when(source.capabilityScore("agent-b", "cap")).thenReturn(OptionalDouble.empty());

    var result =
        provider.evaluate(ctx(), List.of(candidate("agent-a"), candidate("agent-b")));

    assertThat(result).isNotNull();
    var signalA = result.candidates().get("agent-a");
    var signalB = result.candidates().get("agent-b");
    assertThat(signalA).isInstanceOf(RoutingSignal.CandidateSignal.Score.class);
    assertThat(signalB).isInstanceOf(RoutingSignal.CandidateSignal.Exclude.class);
  }

  @Test
  void borderlineCandidate_returnsEscalate() {
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.of(0.25));
    when(source.decisionCount("agent-a", "cap")).thenReturn(10);
    var result = provider.evaluate(ctx(), List.of(candidate("agent-a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("agent-a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Escalate.class);
    assertThat(((RoutingSignal.CandidateSignal.Escalate) signal).reason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void excludedCandidate_returnsExclude() {
    var strictPolicy = new TrustRoutingPolicy(0.5, 5, 0.05, 0.7, Map.of(), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability(anyString())).thenReturn(strictPolicy);
    when(source.capabilityScore("agent-a", "cap")).thenReturn(OptionalDouble.of(0.1));
    when(source.decisionCount("agent-a", "cap")).thenReturn(10);
    var result = provider.evaluate(ctx(), List.of(candidate("agent-a")));

    assertThat(result).isNotNull();
    var signal = result.candidates().get("agent-a");
    assertThat(signal).isInstanceOf(RoutingSignal.CandidateSignal.Exclude.class);
  }

  @Test
  void emptyList_returnsNull() {
    var result = provider.evaluate(ctx(), List.of());
    assertThat(result).isNull();
  }

  private static AgentCandidate candidate(String id) {
    return new AgentCandidate(id, Set.of("cap"), 0, AgentHealth.READY, null, null);
  }

  private static AgentRoutingContext ctx() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "cap", NullNode.getInstance(), "t1", List.of(), null, null);
  }
}
