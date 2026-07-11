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
package io.casehub.engine.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticAgentRoutingStrategyTest {

  private TrustScoreSource source;
  private TrustRoutingPolicyProvider policyProvider;
  private AgentEmbeddingProvider embeddingProvider;
  private JQEvaluator jqEvaluator;
  private SemanticAgentRoutingStrategy strategy;

  private static final TrustRoutingPolicy POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, null);
  private static final TrustRoutingPolicy BOOTSTRAP_GUARD_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), true, null);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeEach
  void setUp() {
    source = mock(TrustScoreSource.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    embeddingProvider = mock(AgentEmbeddingProvider.class);
    jqEvaluator = mock(JQEvaluator.class);

    when(policyProvider.forCapability(any())).thenReturn(POLICY);
    when(source.capabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(source.decisionCount(any(), any())).thenReturn(0);
    when(source.capabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());

    strategy =
        new SemanticAgentRoutingStrategy(
            new TrustCandidateClassifier(),
            source,
            policyProvider,
            embeddingProvider,
            new EmbeddingCache(100),
            jqEvaluator,
            0.4,
            ".");
  }

  @Test
  void bootstrapCandidates_selectedByWorkloadOnly_notBySemantic() {
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any())).thenReturn(new float[] {1.0f, 0.0f});

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-idle", 0, "idle"),
            candidateWithDescriptor("agent-busy", 3, "busy"));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-idle");
  }

  @Test
  void qualifiedCandidates_semanticAndTrustCombined() {
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-a", "research")).thenReturn(10);
    when(source.capabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.82));
    when(source.decisionCount("agent-b", "research")).thenReturn(10);

    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(
            ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("data science"))));
    when(embeddingProvider.embed(any()))
        .thenReturn(new float[] {1.0f, 0.0f})
        .thenReturn(new float[] {0.98f, 0.02f})
        .thenReturn(new float[] {0.1f, 0.99f});

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-a", 0, "agent-a"),
            candidateWithDescriptor("agent-b", 0, "agent-b"));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-a");
  }

  @Test
  void allBorderlineCandidates_escalates() {
    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    when(source.capabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.75));
    when(source.decisionCount("agent-2", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-1", 0, "agent-1"),
            candidateWithDescriptor("agent-2", 0, "agent-2"));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void nullDescriptor_treatedAsBootstrap() {
    final AgentCandidate noDescriptor =
        new AgentCandidate("agent-x", Set.of("research"), 1, AgentHealth.READY, null, null);

    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed("research")).thenReturn(new float[] {1.0f, 0.0f});

    final RoutingResult result =
        strategy.select(ctx(), List.of(noDescriptor)).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-x");
  }

  @Test
  void nullCaseContext_fallsBackToCapabilityName() {
    when(jqEvaluator.eval(anyString(), any())).thenReturn(ValidationResult.ok(List.of()));
    when(embeddingProvider.embed("research")).thenReturn(new float[] {1.0f, 0.0f});
    when(embeddingProvider.embed(vocabularyText("agent-x"))).thenReturn(new float[] {0.9f, 0.1f});

    final AgentCandidate candidate = candidateWithDescriptor("agent-x", 0, "agent-x");
    final RoutingResult result = strategy.select(ctx(), List.of(candidate)).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
  }

  @Test
  void emptyCandidates_returnsUnresolvable() {
    assertThat(strategy.select(ctx(), List.of()).await().indefinitely())
        .isInstanceOf(RoutingResult.Unresolvable.class);
  }

  // ---- Bootstrap guard (bootstrapEscalationRequired = true) -----------------------

  @Test
  void bootstrap_noQualified_allBootstrap_escalatesNoQualifiedAgent() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);

    final List<AgentCandidate> candidates =
        List.of(
            new AgentCandidate("agent-1", Set.of("research"), 0, AgentHealth.READY, null, null),
            new AgentCandidate("agent-2", Set.of("research"), 1, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
    assertThat(((RoutingResult.Escalated) result).capabilityName()).isEqualTo("research");
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusBorderline_escalatesNoQualifiedAgent() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-border", 0, "agent-b"),
            new AgentCandidate("agent-new", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusExcluded_escalatesNoQualifiedAgent() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-low", 0, "agent-l"),
            new AgentCandidate("agent-new", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_qualifiedAssigned() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any()))
        .thenReturn(new float[] {1.0f, 0.0f})
        .thenReturn(new float[] {0.9f, 0.1f});

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-qualified", 2, "agent-q"),
            new AgentCandidate("agent-new", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_busyQualifiedWinsOverIdleBootstrap() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any()))
        .thenReturn(new float[] {1.0f, 0.0f})
        .thenReturn(new float[] {0.9f, 0.1f});

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-qualified", 5, "agent-q"),
            new AgentCandidate("agent-new", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapPlusBorderline_qualifiedWins_noBorderlineStalemate() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any()))
        .thenReturn(new float[] {1.0f, 0.0f})
        .thenReturn(new float[] {0.9f, 0.1f});

    final List<AgentCandidate> candidates =
        List.of(
            candidateWithDescriptor("agent-qualified", 0, "agent-q"),
            candidateWithDescriptor("agent-border", 0, "agent-b"),
            new AgentCandidate("agent-new", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_allExcluded_noBootstrap_returnsUnresolvable() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any())).thenReturn(new float[] {1.0f, 0.0f});

    final List<AgentCandidate> candidates =
        List.of(candidateWithDescriptor("agent-low", 0, "agent-l"));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Unresolvable.class);
  }

  @Test
  void bootstrap_flagFalse_allBootstrap_assignsByWorkload() {
    when(jqEvaluator.eval(anyString(), any()))
        .thenReturn(ValidationResult.ok(List.of(MAPPER.createObjectNode().textNode("research"))));
    when(embeddingProvider.embed(any())).thenReturn(new float[] {1.0f, 0.0f});

    final List<AgentCandidate> candidates =
        List.of(
            new AgentCandidate("agent-busy", Set.of("research"), 5, AgentHealth.READY, null, null),
            new AgentCandidate("agent-idle", Set.of("research"), 0, AgentHealth.READY, null, null));

    final RoutingResult result = strategy.select(ctx(), candidates).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-idle");
  }

  // ---- Helpers ---------------------------------------------------------------

  private AgentRoutingContext ctx() {
    return new AgentRoutingContext(
        UUID.randomUUID(), "research", NullNode.instance, "test-tenant", List.of());
  }

  private String vocabularyText(final String agentId) {
    return "domain-vocab slot-vocab disposition-vocab\ncapability:research tags:analysis ml domains:statistics";
  }

  private AgentCandidate candidateWithDescriptor(
      final String workerId, final int jobs, final String agentId) {
    final AgentDescriptor descriptor =
        new AgentDescriptor(
            agentId,
            agentId,
            "1.0",
            "openai",
            "gpt-4",
            "4-turbo",
            null,
            "domain-vocab",
            "slot-vocab",
            "disposition-vocab",
            null,
            "research",
            List.of(
                new AgentCapability(
                    "research",
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of("analysis", "ml"),
                    Map.of("statistics", 0.8),
                    Set.of())),
            null,
            null,
            null,
            "casehubio",
            null);
    return new AgentCandidate(
        workerId, Set.of("research"), jobs, AgentHealth.READY, descriptor, null);
  }
}
