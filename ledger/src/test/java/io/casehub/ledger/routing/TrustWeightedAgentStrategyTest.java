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
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.ExperiencePlanStep;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustWeightedAgentStrategyTest {

  private TrustScoreSource source;
  private TrustRoutingPolicyProvider policyProvider;
  private TrustWeightedAgentStrategy strategy;
  private AgentRoutingContext ctx;

  // Default policy: threshold=0.7, minimumObservations=5, borderlineMargin=0.1, blendFactor=0.6
  private static final TrustRoutingPolicy DEFAULT_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, null, Set.of(), 0.0);

  private static final TrustRoutingPolicy BOOTSTRAP_GUARD_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), true, null, Set.of(), 0.0);

  @BeforeEach
  void setUp() {
    source = mock(TrustScoreSource.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    strategy =
        new TrustWeightedAgentStrategy(new TrustCandidateClassifier(), source, policyProvider);
    ctx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", List.of());

    when(policyProvider.forCapability("research")).thenReturn(DEFAULT_POLICY);
    when(source.capabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(source.decisionCount(any(), any())).thenReturn(0);
    when(source.capabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
  }

  // ---- Phase 0: no trust history → availability routing -------------------

  @Test
  void phase0_noTrustHistory_selectsLeastLoaded() {
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-busy", 5), candidate("agent-idle", 0));

    final RoutingResult result = select(candidates);

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-idle");
  }

  @Test
  void phase0_emptyCandidates_returnsUnresolvable() {
    assertThat(select(List.of())).isInstanceOf(RoutingResult.Unresolvable.class);
  }

  // ---- Phase 1: insufficient observations ---------------------------------

  @Test
  void phase1_insufficientObservations_fallsToAvailabilityRouting() {
    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.9));
    when(source.decisionCount("agent-1", "research")).thenReturn(3);

    when(source.capabilityScore("agent-2", "research")).thenReturn(OptionalDouble.empty());
    when(source.decisionCount("agent-2", "research")).thenReturn(0);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 2), candidate("agent-2", 0));

    final RoutingResult result = select(candidates);

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-2");
  }

  // ---- Phase 2a: borderline → Escalated -----------------------

  @Test
  void phase2a_singleBorderlineCandidate_escalates() {
    // 0.65: |0.65 - 0.7| = 0.05 ≤ 0.1 → borderline
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);

    final RoutingResult result = select(List.of(candidate("agent-border", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).capabilityName()).isEqualTo("research");
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_borderlineAboveThreshold_alsoEscalates() {
    // |0.75 - 0.7| = 0.05 ≤ 0.1 → borderline (high side)
    when(source.capabilityScore("agent-above-border", "research"))
        .thenReturn(OptionalDouble.of(0.75));
    when(source.decisionCount("agent-above-border", "research")).thenReturn(10);

    final RoutingResult result = select(List.of(candidate("agent-above-border", 0)));
    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_multipleBorderlineCandidates_escalates() {
    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    when(source.capabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.75));
    when(source.decisionCount("agent-2", "research")).thenReturn(10);

    final RoutingResult result = select(List.of(candidate("agent-1", 0), candidate("agent-2", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_borderlinePlusBelowThreshold_escalates() {
    // Mix of borderline + below-threshold → still escalates (borderline is present)
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.3));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);

    final RoutingResult result =
        select(List.of(candidate("agent-border", 0), candidate("agent-low", 0)));
    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.BORDERLINE_STALEMATE);
  }

  @Test
  void phase2a_bootstrapPlusBorderline_bootstrapWins() {
    // Phase 0 candidate has positive availability score → Selected, not Escalate
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);
    // agent-new has no history → Phase 0

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-border", 0), candidate("agent-new", 1));

    final RoutingResult result = select(candidates);

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-new");
  }

  // ---- Phase 2b: below threshold → Unresolvable ---------------------------

  @Test
  void phase2b_belowThreshold_returnsUnresolvable() {
    // 0.5: |0.5 - 0.7| = 0.2 > 0.1 → not borderline → EXCLUDED_PHASE2B
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);

    assertThat(select(List.of(candidate("agent-low", 0))))
        .isInstanceOf(RoutingResult.Unresolvable.class);
  }

  @Test
  void phase2_aboveThreshold_selected() {
    when(source.capabilityScore("agent-good", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-good", "research")).thenReturn(10);

    final RoutingResult result = select(List.of(candidate("agent-good", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-good");
  }

  // ---- Phase 3: quality floor checks --------------------------------------

  @Test
  void phase3_qualityFloorMet_candidateSelected() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(
            0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    when(source.capabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.82));

    final RoutingResult result = select(List.of(candidate("agent-1", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-1");
  }

  @Test
  void phase3_qualityFloorFailed_returnsUnresolvable() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(
            0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    when(source.capabilityDimensionScore("agent-1", "research", "thoroughness"))
        .thenReturn(OptionalDouble.of(0.60)); // below floor → EXCLUDED_PHASE3

    assertThat(select(List.of(candidate("agent-1", 0))))
        .isInstanceOf(RoutingResult.Unresolvable.class);
  }

  @Test
  void phase3_noDimensionData_candidateNotPenalised() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(
            0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability("research")).thenReturn(policyWithFloor);

    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    // no dimension data → OptionalDouble.empty() → graceful, not penalised

    assertThat(
            ((RoutingResult.Selected) select(List.of(candidate("agent-1", 0))))
                .single()
                .executorId())
        .isEqualTo("agent-1");
  }

  // ---- Blend scoring: trust vs workload -----------------------------------

  @Test
  void blendScoring_higherTrustWinsOverWorkload() {
    when(source.capabilityScore("agent-high-trust", "research"))
        .thenReturn(OptionalDouble.of(0.95));
    when(source.decisionCount("agent-high-trust", "research")).thenReturn(10);

    when(source.capabilityScore("agent-low-trust", "research")).thenReturn(OptionalDouble.of(0.82));
    when(source.decisionCount("agent-low-trust", "research")).thenReturn(10);

    // agent-high-trust: 0.95*0.6 + (1/4)*0.4 = 0.57 + 0.10 = 0.67
    // agent-low-trust:  0.82*0.6 + (1/1)*0.4 = 0.49 + 0.40 = 0.89
    final List<AgentCandidate> candidates =
        List.of(candidate("agent-high-trust", 3), candidate("agent-low-trust", 0));

    assertThat(((RoutingResult.Selected) select(candidates)).single().executorId())
        .isEqualTo("agent-low-trust");
  }

  @Test
  void blendScoring_purelyTrustBased_whenBlendFactorIsOne() {
    final TrustRoutingPolicy pureTrust =
        new TrustRoutingPolicy(0.7, 5, 0.1, 1.0, Map.of(), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability("research")).thenReturn(pureTrust);

    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.90));
    when(source.decisionCount("agent-a", "research")).thenReturn(10);
    when(source.capabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-b", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-a", 5), candidate("agent-b", 0));

    assertThat(((RoutingResult.Selected) select(candidates)).single().executorId())
        .isEqualTo("agent-a");
  }

  @Test
  void blendScoring_purelyWorkloadBased_whenBlendFactorIsZero() {
    final TrustRoutingPolicy pureWorkload =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.0, Map.of(), false, null, Set.of(), 0.0);
    when(policyProvider.forCapability("research")).thenReturn(pureWorkload);

    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.95));
    when(source.decisionCount("agent-a", "research")).thenReturn(10);
    when(source.capabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-b", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-a", 5), candidate("agent-b", 0));

    assertThat(((RoutingResult.Selected) select(candidates)).single().executorId())
        .isEqualTo("agent-b");
  }

  // ---- All-excluded edge case ---------------------------------------------

  @Test
  void allCandidatesBelowThreshold_noBorderline_returnsUnresolvable() {
    when(source.capabilityScore("agent-1", "research")).thenReturn(OptionalDouble.of(0.50));
    when(source.decisionCount("agent-1", "research")).thenReturn(10);
    when(source.capabilityScore("agent-2", "research")).thenReturn(OptionalDouble.of(0.30));
    when(source.decisionCount("agent-2", "research")).thenReturn(10);

    final List<AgentCandidate> candidates =
        List.of(candidate("agent-1", 0), candidate("agent-2", 0));

    assertThat(select(candidates)).isInstanceOf(RoutingResult.Unresolvable.class);
  }

  // ---- Bootstrap guard (bootstrapEscalationRequired = true) -----------------------

  @Test
  void bootstrap_noQualified_allBootstrap_escalatesNoQualifiedAgent() {
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    // All candidates: no trust score → BOOTSTRAP phase

    final RoutingResult result = select(List.of(candidate("agent-1", 0), candidate("agent-2", 1)));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
    assertThat(((RoutingResult.Escalated) result).capabilityName()).isEqualTo("research");
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusBorderline_escalatesNoQualifiedAgent() {
    // Closes mixed-pool gap: without guard, BOOTSTRAP (workload>0) beats BORDERLINE (score=0)
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP (no score in cache)

    final RoutingResult result =
        select(List.of(candidate("agent-border", 0), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_noQualified_bootstrapPlusExcluded_escalatesNoQualifiedAgent() {
    // EXCLUDED_PHASE2B (score<threshold) + BOOTSTRAP: BOOTSTRAP would win by workload without guard
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP

    final RoutingResult result =
        select(List.of(candidate("agent-low", 0), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
    assertThat(((RoutingResult.Escalated) result).escalationReason())
        .isEqualTo(EscalationReason.NO_QUALIFIED_AGENT);
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_qualifiedAssigned() {
    // QUALIFIED exists → pre-screen skips; BOOTSTRAP stripped from scoring pool
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP, 0 jobs (would outscore QUALIFIED by workload without stripping)

    final RoutingResult result =
        select(List.of(candidate("agent-qualified", 2), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapStripped_busyQualifiedWinsOverIdleBootstrap() {
    // Explicit: flag overrides workload comparison. Busy QUALIFIED beats idle BOOTSTRAP.
    // Without flag: BOOTSTRAP workload=1.0 > QUALIFIED blended~0.55 (5 jobs) → BOOTSTRAP wins.
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);

    final RoutingResult result =
        select(List.of(candidate("agent-qualified", 5), candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_qualifiedExists_bootstrapPlusBorderline_qualifiedWins_noBorderlineStalemate() {
    // [BOOTSTRAP, QUALIFIED, BORDERLINE] with flag:
    // BOOTSTRAP stripped → eligible=[QUALIFIED, BORDERLINE]
    // QUALIFIED wins positive score; BORDERLINE_STALEMATE must NOT fire
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-qualified", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-qualified", "research")).thenReturn(10);
    when(source.capabilityScore("agent-border", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-border", "research")).thenReturn(10);
    // agent-new: BOOTSTRAP

    final RoutingResult result =
        select(
            List.of(
                candidate("agent-qualified", 0),
                candidate("agent-border", 0),
                candidate("agent-new", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId())
        .isEqualTo("agent-qualified");
  }

  @Test
  void bootstrap_allExcluded_noBootstrap_returnsUnresolvable() {
    // Guard requires hasBootstrap=true; all-EXCLUDED pool has no BOOTSTRAP → guard must not fire
    when(policyProvider.forCapability("research")).thenReturn(BOOTSTRAP_GUARD_POLICY);
    when(source.capabilityScore("agent-low", "research")).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount("agent-low", "research")).thenReturn(10);
    when(source.capabilityScore("agent-lower", "research")).thenReturn(OptionalDouble.of(0.3));
    when(source.decisionCount("agent-lower", "research")).thenReturn(10);

    final RoutingResult result =
        select(List.of(candidate("agent-low", 0), candidate("agent-lower", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Unresolvable.class);
  }

  @Test
  void bootstrap_flagFalse_allBootstrap_assignsByWorkload() {
    // bootstrapEscalationRequired = false: pre-screen skipped; existing behaviour preserved
    when(policyProvider.forCapability("research")).thenReturn(DEFAULT_POLICY);

    final RoutingResult result =
        select(List.of(candidate("agent-busy", 5), candidate("agent-idle", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-idle");
  }

  // ---- Helpers ------------------------------------------------------------

  // ---- CBR-enhanced scoring (cbrWeight > 0) --------------------------------

  private static final TrustRoutingPolicy CBR_POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of(), false, null, Set.of(), 0.2);

  @Test
  void cbr_noExperiences_identicalToPureTrust() {
    when(policyProvider.forCapability("research")).thenReturn(CBR_POLICY);
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-a", "research")).thenReturn(10);

    final RoutingResult result = select(List.of(candidate("agent-a", 0)));

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().reason()).doesNotContain("cbr_bonus");
  }

  @Test
  void cbr_agentWithHigherCbrBonusWins() {
    when(policyProvider.forCapability("research")).thenReturn(CBR_POLICY);

    // agent-a: trust=0.85 but strong CBR history (SUCCESS on similar case)
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.85));
    when(source.decisionCount("agent-a", "research")).thenReturn(15);
    // agent-b: trust=0.87 but no CBR history
    when(source.capabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.87));
    when(source.decisionCount("agent-b", "research")).thenReturn(15);

    var experiences =
        List.of(
            new RetrievedExperience(
                "problem",
                "solution",
                "COMPLETED",
                1.0,
                0.85,
                Map.of(),
                List.of(
                    new ExperiencePlanStep(
                        "binding", "research", "agent-a", "SUCCESS", 0, Map.of())),
                Map.of()));
    var cbrCtx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", experiences);

    final RoutingResult result =
        strategy
            .select(cbrCtx, List.of(candidate("agent-a", 0), candidate("agent-b", 0)))
            .await()
            .indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    var selected = (RoutingResult.Selected) result;
    assertThat(selected.single().executorId()).isEqualTo("agent-a");
    assertThat(selected.single().reason()).contains("cbr_bonus");
  }

  @Test
  void cbr_asymmetricHistory_workerWithoutHistory_retainsPureTrust() {
    when(policyProvider.forCapability("research")).thenReturn(CBR_POLICY);

    // Both agents same trust; agent-a has CBR history, agent-b does not
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-a", "research")).thenReturn(15);
    when(source.capabilityScore("agent-b", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-b", "research")).thenReturn(15);

    var experiences =
        List.of(
            new RetrievedExperience(
                "problem",
                "solution",
                "COMPLETED",
                1.0,
                0.9,
                Map.of(),
                List.of(
                    new ExperiencePlanStep(
                        "binding", "research", "agent-a", "SUCCESS", 0, Map.of())),
                Map.of()));
    var cbrCtx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", experiences);

    final RoutingResult result =
        strategy
            .select(cbrCtx, List.of(candidate("agent-a", 0), candidate("agent-b", 0)))
            .await()
            .indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().executorId()).isEqualTo("agent-a");
  }

  @Test
  void cbr_bootstrapCandidate_noCbrBonus() {
    when(policyProvider.forCapability("research")).thenReturn(CBR_POLICY);

    // agent-a is bootstrap (2 < 5 minimumObservations)
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-a", "research")).thenReturn(2);

    var experiences =
        List.of(
            new RetrievedExperience(
                "problem",
                "solution",
                "COMPLETED",
                1.0,
                0.9,
                Map.of(),
                List.of(
                    new ExperiencePlanStep(
                        "binding", "research", "agent-a", "SUCCESS", 0, Map.of())),
                Map.of()));
    var cbrCtx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", experiences);

    final RoutingResult result =
        strategy.select(cbrCtx, List.of(candidate("agent-a", 0))).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().reason()).contains("bootstrap");
    assertThat(((RoutingResult.Selected) result).single().reason()).doesNotContain("cbr_bonus");
  }

  @Test
  void cbr_borderlineAgent_notRescuedByCbr() {
    when(policyProvider.forCapability("research")).thenReturn(CBR_POLICY);

    // agent-a trust=0.65 — borderline (within 0.1 of 0.7 threshold)
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount("agent-a", "research")).thenReturn(15);

    var experiences =
        List.of(
            new RetrievedExperience(
                "problem",
                "solution",
                "COMPLETED",
                1.0,
                0.9,
                Map.of(),
                List.of(
                    new ExperiencePlanStep(
                        "binding", "research", "agent-a", "SUCCESS", 0, Map.of())),
                Map.of()));
    var cbrCtx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", experiences);

    final RoutingResult result =
        strategy.select(cbrCtx, List.of(candidate("agent-a", 0))).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Escalated.class);
  }

  @Test
  void cbr_cbrWeightZero_experiencesIgnored() {
    // DEFAULT_POLICY has cbrWeight=0.0; experiences present but should be ignored
    when(source.capabilityScore("agent-a", "research")).thenReturn(OptionalDouble.of(0.80));
    when(source.decisionCount("agent-a", "research")).thenReturn(15);

    var experiences =
        List.of(
            new RetrievedExperience(
                "problem",
                "solution",
                "COMPLETED",
                1.0,
                0.9,
                Map.of(),
                List.of(
                    new ExperiencePlanStep(
                        "binding", "research", "agent-a", "SUCCESS", 0, Map.of())),
                Map.of()));
    var cbrCtx =
        new AgentRoutingContext(
            UUID.randomUUID(), "research", NullNode.instance, "test-tenant", experiences);

    final RoutingResult result =
        strategy.select(cbrCtx, List.of(candidate("agent-a", 0))).await().indefinitely();

    assertThat(result).isInstanceOf(RoutingResult.Selected.class);
    assertThat(((RoutingResult.Selected) result).single().reason()).doesNotContain("cbr_bonus");
  }

  private RoutingResult select(final List<AgentCandidate> candidates) {
    return strategy.select(ctx, candidates).await().indefinitely();
  }

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return new AgentCandidate(workerId, Set.of("research"), jobs, AgentHealth.READY, null, null);
  }
}
