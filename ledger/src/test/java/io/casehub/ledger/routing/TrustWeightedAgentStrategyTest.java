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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustWeightedAgentStrategyTest {

  private TrustScoreCache cache;
  private TrustRoutingPolicyProvider policyProvider;
  private TrustWeightedAgentStrategy strategy;
  private AgentRoutingContext ctx;

  // Default policy: threshold=0.7, minimumObservations=5, borderlineMargin=0.1, blendFactor=0.6
  private static final TrustRoutingPolicy DEFAULT_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false);

  private static final TrustRoutingPolicy BOOTSTRAP_GUARD_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), true);

  @BeforeEach
  void setUp() {
    cache = mock(TrustScoreCache.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    strategy =
        new TrustWeightedAgentStrategy(new TrustCandidateClassifier(), cache, policyProvider);
    ctx = new AgentRoutingContext(UUID.randomUUID(), "research", NullNode.instance);

    when(policyProvider.forCapability("research")).thenReturn(DEFAULT_POLICY);
    when(cache.getCapabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(cache.getDecisionCount(any(), any())).thenReturn(0);
    when(cache.getCapabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
  }

  // ---- Phase 0: no trust history → availability routing -------------------

  @Test
  void phase0_noTrustHistory_selectsLeastLoaded() {
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-busy", 5), candidate("agent-idle", 0));

    final AgentAssignment result = select(candidates);

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-idle");
  }

  @Test
  void phase0_emptyCandidates_returnsUnresolvable() {
    assertThat(select(List.of())).isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  // ---- Phase 1: insufficient observations ---------------------------------

  @Test
  void phase1_insufficientObservations_fallsToAvailabilityRouting() {
    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.9));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(3);

    when(cache.getCapabilityScore("agent-2", "research")).thenReturn(OptionalDouble.empty());
    when(cache.getDecisionCount("agent-2", "research")).thenReturn(0);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 2), candidate("agent-2", 0));

    final AgentAssignment result = select(candidates);

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-2");
  }

  // ---- Phase 2a: borderline → EscalateToOversight -----------------------

  @Test
  void phase2a_singleBorderlineCandidate_escalates() {
    // 0.65: |0.65 - 0.7| = 0.05 ≤ 0.1 → borderline
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);

    final AgentAssignment result = select(List.of(candidate("agent-border", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).capabilityName())
        .isEqualTo("research");
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_borderlineAboveThreshold_alsoEscalates() {
    // |0.75 - 0.7| = 0.05 ≤ 0.1 → borderline (high side)
    when(cache.getCapabilityScore("agent-above-border", "research"))
        .thenReturn(OptionalDouble.of(0.75));
    when(cache.getDecisionCount("agent-above-border", "research")).thenReturn(10);

    final AgentAssignment result = select(List.of(candidate("agent-above-border", 0)));
    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_multipleBorderlineCandidates_escalates() {
    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.75));
    when(cache.getDecisionCount("agent-2", "research")).thenReturn(10);

    final AgentAssignment result =
        select(List.of(candidate("agent-1", 0), candidate("agent-2", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_borderlinePlusBelowThreshold_escalates() {
    // Mix of borderline + below-threshold → still escalates (borderline is present)
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.3));
    when(cache.getDecisionCount("agent-low", "research")).thenReturn(10);

    final AgentAssignment result =
        select(List.of(candidate("agent-border", 0), candidate("agent-low", 0)));
    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_bootstrapPlusBorderline_bootstrapWins() {
    // Phase 0 candidate has positive availability score → Assigned, not Escalate
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);
    // agent-new has no history → Phase 0

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-border", 0), candidate("agent-new", 1));

    final AgentAssignment result = select(candidates);

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-new");
  }

  // ---- Phase 2b: below threshold → Unresolvable ---------------------------

  @Test
  void phase2b_belowThreshold_returnsUnresolvable() {
    // 0.5: |0.5 - 0.7| = 0.2 > 0.1 → not borderline → EXCLUDED_PHASE2B
    when(cache.getCapabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(cache.getDecisionCount("agent-low", "research")).thenReturn(10);

    assertThat(select(List.of(candidate("agent-low", 0))))
        .isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void phase2_aboveThreshold_selected() {
    when(cache.getCapabilityScore("agent-good", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-good", "research")).thenReturn(10);

    final AgentAssignment result = select(List.of(candidate("agent-good", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-good");
  }

  // ---- Phase 3: quality floor checks --------------------------------------

  @Test
  void phase3_qualityFloorMet_candidateSelected() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.82));

    final AgentAssignment result = select(List.of(candidate("agent-1", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-1");
  }

  @Test
  void phase3_qualityFloorFailed_returnsUnresolvable() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.60)); // below floor → EXCLUDED_PHASE3

    assertThat(select(List.of(candidate("agent-1", 0))))
        .isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void phase3_noDimensionData_candidateNotPenalised() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    // no dimension data → OptionalDouble.empty() → graceful, not penalised

    assertThat(((AgentAssignment.Assigned) select(List.of(candidate("agent-1", 0)))).workerId())
        .isEqualTo("agent-1");
  }

  // ---- Blend scoring: trust vs workload -----------------------------------

  @Test
  void blendScoring_higherTrustWinsOverWorkload() {
    when(cache.getCapabilityScore("agent-high-trust", "research"))
        .thenReturn(OptionalDouble.of(0.95));
    when(cache.getDecisionCount("agent-high-trust", "research")).thenReturn(10);

    when(cache.getCapabilityScore("agent-low-trust", "research"))
        .thenReturn(OptionalDouble.of(0.82));
    when(cache.getDecisionCount("agent-low-trust", "research")).thenReturn(10);

    // agent-high-trust: 0.95*0.6 + (1/4)*0.4 = 0.57 + 0.10 = 0.67
    // agent-low-trust:  0.82*0.6 + (1/1)*0.4 = 0.49 + 0.40 = 0.89
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-high-trust", 3), candidate("agent-low-trust", 0));

    assertThat(((AgentAssignment.Assigned) select(candidates)).workerId())
        .isEqualTo("agent-low-trust");
  }

  @Test
  void blendScoring_purelyTrustBased_whenBlendFactorIsOne() {
    final TrustRoutingPolicy pureTrust = new TrustRoutingPolicy(0.7, 5, 0.1, 1.0, Map.of(), false);
    when(policyProvider.forCapability("research")).thenReturn(pureTrust);

    when(cache.getCapabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.90));
    when(cache.getDecisionCount("agent-a", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(cache.getDecisionCount("agent-b", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-a", 5), candidate("agent-b", 0));

    assertThat(((AgentAssignment.Assigned) select(candidates)).workerId()).isEqualTo("agent-a");
  }

  @Test
  void blendScoring_purelyWorkloadBased_whenBlendFactorIsZero() {
    final TrustRoutingPolicy pureWorkload =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.0, Map.of(), false);
    when(policyProvider.forCapability("research")).thenReturn(pureWorkload);

    when(cache.getCapabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.95));
    when(cache.getDecisionCount("agent-a", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(cache.getDecisionCount("agent-b", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-a", 5), candidate("agent-b", 0));

    assertThat(((AgentAssignment.Assigned) select(candidates)).workerId()).isEqualTo("agent-b");
  }

  // ---- All-excluded edge case ---------------------------------------------

  @Test
  void allCandidatesBelowThreshold_noBorderline_returnsUnresolvable() {
    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.50));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.30));
    when(cache.getDecisionCount("agent-2", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 0), candidate("agent-2", 0));

    assertThat(select(candidates)).isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  // ---- Bootstrap guard (bootstrapEscalationRequired = true) -----------------------

  @Test
  void bootstrap_noQualified_allBootstrap_escalatesNoQualifiedAgent() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    // All candidates: no trust score → BOOTSTRAP phase

    final AgentAssignment result =
        select(List.of(candidate("agent-1", 0), candidate("agent-2", 1)));

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
    assertThat(((AgentAssignment.EscalateToOversight) result).capabilityName())
        .isEqualTo("research");
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusBorderline_escalatesNoQualifiedAgent() {
    // Closes mixed-pool gap: without guard, BOOTSTRAP (workload>0) beats BORDERLINE (score=0)
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP (no score in cache)

    final AgentAssignment result =
        select(List.of(candidate("agent-border", 0), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusExcluded_escalatesNoQualifiedAgent() {
    // EXCLUDED_PHASE2B (score<threshold) + BOOTSTRAP: BOOTSTRAP would win by workload without guard
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(cache.getCapabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(cache.getDecisionCount("agent-low", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP

    final AgentAssignment result =
        select(List.of(candidate("agent-low", 0), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).reason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_qualifiedAssigned() {
    // QUALIFIED exists → pre-screen skips; BOOTSTRAP stripped from scoring pool
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(cache.getCapabilityScore("agent-qualified", "research"))
        .thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-qualified", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP, 0 jobs (would outscore QUALIFIED by workload without stripping)

    final AgentAssignment result =
        select(List.of(candidate("agent-qualified", 2), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_busyQualifiedWinsOverIdleBootstrap() {
    // Explicit: flag overrides workload comparison. Busy QUALIFIED beats idle BOOTSTRAP.
    // Without flag: BOOTSTRAP workload=1.0 > QUALIFIED blended~0.55 (5 jobs) → BOOTSTRAP wins.
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(cache.getCapabilityScore("agent-qualified", "research"))
        .thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-qualified", "research")).thenReturn(10);

    final AgentAssignment result =
        select(List.of(candidate("agent-qualified", 5), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapPlusBorderline_qualifiedWins_noBorderlineStalemate() {
    // [BOOTSTRAP, QUALIFIED, BORDERLINE] with flag:
    // BOOTSTRAP stripped → eligible=[QUALIFIED, BORDERLINE]
    // QUALIFIED wins positive score; BORDERLINE_STALEMATE must NOT fire
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(cache.getCapabilityScore("agent-qualified", "research"))
        .thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-qualified", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP

    final AgentAssignment result =
        select(
            List.of(
                candidate("agent-qualified", 0),
                candidate("agent-border", 0),
                candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_flagFalse_allBootstrap_assignsByWorkload() {
    // bootstrapEscalationRequired = false: pre-screen skipped; existing behaviour preserved
    when(policyProvider.forCapability("research")).thenReturn(DEFAULT_POLICY);

    final AgentAssignment result =
        select(List.of(candidate("agent-busy", 5), candidate("agent-idle", 0)));

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("agent-idle");
  }

  // ---- Helpers ------------------------------------------------------------

  private AgentAssignment select(final List<AgentCandidate> candidates) {
    return strategy.select(ctx, candidates).await().indefinitely();
  }

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return new AgentCandidate(workerId, Set.of("research"), jobs, AgentHealth.READY, null);
  }
}
