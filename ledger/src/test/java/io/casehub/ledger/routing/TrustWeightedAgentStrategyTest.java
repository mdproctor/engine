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

import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
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
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of());

  @BeforeEach
  void setUp() {
    cache = mock(TrustScoreCache.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    strategy = new TrustWeightedAgentStrategy(cache, policyProvider);
    ctx = new AgentRoutingContext(UUID.randomUUID(), "research");

    when(policyProvider.forCapability("research")).thenReturn(DEFAULT_POLICY);
    // Default: no scores in cache
    when(cache.getCapabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(cache.getDecisionCount(any(), any())).thenReturn(0);
    when(cache.getCapabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
  }

  // ---- Phase 0: no trust history → availability routing -------------------

  @Test
  void phase0_noTrustHistory_selectsLeastLoaded() {
    // No cache entry → Phase 0 → availability score (workload-based)
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-busy", 5), candidate("agent-idle", 0));

    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-idle");
  }

  @Test
  void phase0_emptyCandidates_returnsNoOp() {
    assertThat(strategy.select(ctx, List.of()).isNoOp()).isTrue();
  }

  // ---- Phase 1: insufficient observations ---------------------------------

  @Test
  void phase1_insufficientObservations_fallsToAvailabilityRouting() {
    // Has score but below minimumObservations (5) → Phase 0/1 treatment
    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.9));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(3); // below 5

    when(cache.getCapabilityScore("agent-2", "research"))
        .thenReturn(OptionalDouble.empty()); // Phase 0
    when(cache.getDecisionCount("agent-2", "research")).thenReturn(0);

    // Both are availability-scored; agent-2 has 0 jobs, agent-1 has 2 → agent-2 wins on workload
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 2), candidate("agent-2", 0));

    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-2");
  }

  // ---- Phase 2: borderline candidates excluded ----------------------------

  @Test
  void phase2a_borderlineCandidate_excluded() {
    // Trust score within borderlineMargin (0.1) of threshold (0.7): 0.65 is borderline
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);

    // Single candidate: borderline → excluded → noOp
    assertThat(strategy.select(ctx, List.of(candidate("agent-border", 0))).isNoOp()).isTrue();
  }

  @Test
  void phase2a_aboveThresholdButBorderline_excluded() {
    // Trust score above threshold but within borderlineMargin → borderline on the high side
    // |0.75 - 0.7| = 0.05 ≤ borderlineMargin(0.1) → excluded
    when(cache.getCapabilityScore("agent-above-border", "research"))
        .thenReturn(OptionalDouble.of(0.75));
    when(cache.getDecisionCount("agent-above-border", "research")).thenReturn(10);

    assertThat(strategy.select(ctx, List.of(candidate("agent-above-border", 0))).isNoOp()).isTrue();
  }

  @Test
  void phase2b_belowThreshold_excluded() {
    // Below threshold (0.7) and not borderline (0.5 is 0.2 away)
    when(cache.getCapabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(cache.getDecisionCount("agent-low", "research")).thenReturn(10);

    assertThat(strategy.select(ctx, List.of(candidate("agent-low", 0))).isNoOp()).isTrue();
  }

  @Test
  void phase2_aboveThreshold_selected() {
    // Clearly above threshold and not borderline
    when(cache.getCapabilityScore("agent-good", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-good", "research")).thenReturn(10);

    assertThat(strategy.select(ctx, List.of(candidate("agent-good", 0))).workerId())
        .isEqualTo("agent-good");
  }

  @Test
  void phase0CandidateBeatsPhase2Borderline_explicitPolicy() {
    // Phase 2 borderline candidate scores 0.0; Phase 0 candidate has availability score > 0
    when(cache.getCapabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.68));
    when(cache.getDecisionCount("agent-border", "research")).thenReturn(10);
    // agent-new has no history → Phase 0

    final List<AgentCandidate> candidates =
        List.of(
            candidate("agent-border", 0), // borderline → score 0.0
            candidate("agent-new", 1)); // Phase 0 → workloadScore(1) = 1/(1+1) = 0.5

    // Phase 0 beats borderline
    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-new");
  }

  // ---- Phase 3: quality floor checks --------------------------------------

  @Test
  void phase3_qualityFloorMet_candidateSelected() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75));
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.82)); // above floor 0.75

    assertThat(strategy.select(ctx, List.of(candidate("agent-1", 0))).workerId())
        .isEqualTo("agent-1");
  }

  @Test
  void phase3_qualityFloorFailed_candidateExcluded() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75));
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.60)); // below floor 0.75 → excluded

    assertThat(strategy.select(ctx, List.of(candidate("agent-1", 0))).isNoOp()).isTrue();
  }

  @Test
  void phase3_noDimensionData_candidateNotPenalised() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75));
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.empty()); // no data yet → graceful Phase 0 for this dimension

    // Not penalised — candidate passes through to blend scoring
    assertThat(strategy.select(ctx, List.of(candidate("agent-1", 0))).workerId())
        .isEqualTo("agent-1");
  }

  // ---- Blend scoring: trust vs workload -----------------------------------

  @Test
  void blendScoring_higherTrustWinsOverWorkload() {
    // blendFactor=0.6 → trust weight 60%, workload weight 40%
    when(cache.getCapabilityScore("agent-high-trust", "research"))
        .thenReturn(OptionalDouble.of(0.95));
    when(cache.getDecisionCount("agent-high-trust", "research")).thenReturn(10);

    when(cache.getCapabilityScore("agent-low-trust", "research"))
        .thenReturn(
            OptionalDouble.of(0.82)); // 0.82 is not borderline (|0.82-0.7|=0.12 > margin=0.1)
    when(cache.getDecisionCount("agent-low-trust", "research")).thenReturn(10);

    // agent-high-trust: 0.95*0.6 + (1/(1+3))*0.4 = 0.57 + 0.10 = 0.67
    // agent-low-trust:  0.82*0.6 + (1/(1+0))*0.4 = 0.49 + 0.40 = 0.89
    // Idle + lower trust beats busy + higher trust when workload dominates the gap
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-high-trust", 3), candidate("agent-low-trust", 0));

    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-low-trust");
  }

  @Test
  void blendScoring_purelyTrustBased_whenBlendFactorIsOne() {
    final TrustRoutingPolicy pureTrust = new TrustRoutingPolicy(0.7, 5, 0.1, 1.0, Map.of());
    when(policyProvider.forCapability("research")).thenReturn(pureTrust);

    when(cache.getCapabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.90));
    when(cache.getDecisionCount("agent-a", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(cache.getDecisionCount("agent-b", "research")).thenReturn(10);

    // With blendFactor=1.0: score = trust only → agent-a wins regardless of jobs
    final List<AgentCandidate> candidates =
        List.of(
            candidate("agent-a", 5), // busy but higher trust
            candidate("agent-b", 0)); // idle but lower trust

    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-a");
  }

  @Test
  void blendScoring_purelyWorkloadBased_whenBlendFactorIsZero() {
    final TrustRoutingPolicy pureWorkload = new TrustRoutingPolicy(0.7, 5, 0.1, 0.0, Map.of());
    when(policyProvider.forCapability("research")).thenReturn(pureWorkload);

    when(cache.getCapabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.95));
    when(cache.getDecisionCount("agent-a", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(cache.getDecisionCount("agent-b", "research")).thenReturn(10);

    // With blendFactor=0: score = workloadScore only → idle agent wins regardless of trust
    final List<AgentCandidate> candidates =
        List.of(
            candidate("agent-a", 5), // high trust but busy
            candidate("agent-b", 0)); // lower trust but idle

    assertThat(strategy.select(ctx, candidates).workerId()).isEqualTo("agent-b");
  }

  // ---- All-excluded edge case ---------------------------------------------

  @Test
  void allCandidatesExcluded_returnsNoOp() {
    when(cache.getCapabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.50));
    when(cache.getDecisionCount("agent-1", "research")).thenReturn(10);
    when(cache.getCapabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.30));
    when(cache.getDecisionCount("agent-2", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 0), candidate("agent-2", 0));

    assertThat(strategy.select(ctx, candidates).isNoOp()).isTrue();
  }

  // ---- Helpers ------------------------------------------------------------

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return new AgentCandidate(workerId, Set.of("research"), jobs, AgentHealth.READY);
  }
}
