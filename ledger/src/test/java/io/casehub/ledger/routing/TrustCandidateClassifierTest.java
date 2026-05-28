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

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustCandidateClassifierTest {

  private TrustScoreCache cache;
  private TrustCandidateClassifier classifier;

  // threshold=0.7, minimumObservations=5, borderlineMargin=0.1
  private static final TrustRoutingPolicy POLICY =
      new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of());
  private static final String CAP = "research";

  @BeforeEach
  void setUp() {
    cache = mock(TrustScoreCache.class);
    classifier = new TrustCandidateClassifier();
    when(cache.getCapabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(cache.getDecisionCount(any(), any())).thenReturn(0);
    when(cache.getCapabilityDimensionScore(any(), any(), any())).thenReturn(OptionalDouble.empty());
  }

  // ---- classify: BOOTSTRAP -------------------------------------------------

  @Test
  void classify_noTrustHistory_isBootstrap() {
    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 2)), CAP, POLICY, cache);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).phase()).isEqualTo(Phase.BOOTSTRAP);
    assertThat(result.get(0).trustScore()).isEmpty();
    assertThat(result.get(0).workloadScore()).isEqualTo(1.0 / (1 + 2));
  }

  @Test
  void classify_insufficientObservations_isBootstrap() {
    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.9));
    when(cache.getDecisionCount("a", CAP)).thenReturn(3); // below minimumObservations=5

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, POLICY, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.BOOTSTRAP);
  }

  // ---- classify: BORDERLINE ------------------------------------------------

  @Test
  void classify_borderlineScore_isBorderline() {
    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.65));
    when(cache.getDecisionCount("a", CAP)).thenReturn(10);

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, POLICY, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.BORDERLINE);
    assertThat(result.get(0).trustScore()).hasValue(0.65);
  }

  // ---- classify: EXCLUDED_PHASE2B ------------------------------------------

  @Test
  void classify_belowThresholdNotBorderline_isExcludedPhase2b() {
    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.5));
    when(cache.getDecisionCount("a", CAP)).thenReturn(10);

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, POLICY, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.EXCLUDED_PHASE2B);
  }

  // ---- classify: EXCLUDED_PHASE3 -------------------------------------------

  @Test
  void classify_qualityFloorFailed_isExcludedPhase3() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75));

    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("a", CAP)).thenReturn(10);
    when(cache.getCapabilityDimensionScore("a", CAP, "thoroughness"))
        .thenReturn(OptionalDouble.of(0.60)); // below floor

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, policyWithFloor, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.EXCLUDED_PHASE3);
  }

  // ---- classify: QUALIFIED -------------------------------------------------

  @Test
  void classify_aboveThresholdNotBorderline_isQualified() {
    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("a", CAP)).thenReturn(10);

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, POLICY, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.QUALIFIED);
    assertThat(result.get(0).trustScore()).hasValue(0.85);
  }

  @Test
  void classify_qualityFloorMissing_isQualified() {
    final TrustRoutingPolicy policyWithFloor =
        new TrustRoutingPolicy(0.7, 5, 0.1, 0.6, Map.of("thoroughness", 0.75));
    when(cache.getCapabilityScore("a", CAP)).thenReturn(OptionalDouble.of(0.85));
    when(cache.getDecisionCount("a", CAP)).thenReturn(10);
    // no dimension data → graceful; not penalised

    final List<ClassifiedCandidate> result =
        classifier.classify(List.of(candidate("a", 0)), CAP, policyWithFloor, cache);

    assertThat(result.get(0).phase()).isEqualTo(Phase.QUALIFIED);
  }

  // ---- decide: outcomes ----------------------------------------------------

  @Test
  void decide_highestScoredAboveZero_returnsAssigned() {
    final ClassifiedCandidate cand = classified("worker-1", Phase.QUALIFIED, 0.85, 1.0);
    final ScoredCandidate scored = new ScoredCandidate(cand, 0.75);

    final AgentAssignment result =
        TrustCandidateClassifier.decide(List.of(cand), List.of(scored), CAP);

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("worker-1");
  }

  @Test
  void decide_allZeroScores_someBorderline_returnsEscalate() {
    final ClassifiedCandidate cand = classified("worker-1", Phase.BORDERLINE, 0.65, 1.0);
    final ScoredCandidate scored = new ScoredCandidate(cand, 0.0);

    final AgentAssignment result =
        TrustCandidateClassifier.decide(List.of(cand), List.of(scored), CAP);

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
    assertThat(((AgentAssignment.EscalateToOversight) result).capabilityName()).isEqualTo(CAP);
  }

  @Test
  void decide_allZeroScores_noBorderline_returnsUnresolvable() {
    final ClassifiedCandidate cand = classified("worker-1", Phase.EXCLUDED_PHASE2B, 0.5, 1.0);
    final ScoredCandidate scored = new ScoredCandidate(cand, 0.0);

    final AgentAssignment result =
        TrustCandidateClassifier.decide(List.of(cand), List.of(scored), CAP);

    assertThat(result).isInstanceOf(AgentAssignment.Unresolvable.class);
  }

  @Test
  void decide_mixedBorderlineAndExcluded_allZero_returnsEscalate() {
    final ClassifiedCandidate border = classified("w1", Phase.BORDERLINE, 0.65, 1.0);
    final ClassifiedCandidate excluded = classified("w2", Phase.EXCLUDED_PHASE2B, 0.5, 1.0);
    final List<ScoredCandidate> scored =
        List.of(new ScoredCandidate(border, 0.0), new ScoredCandidate(excluded, 0.0));

    final AgentAssignment result =
        TrustCandidateClassifier.decide(List.of(border, excluded), scored, CAP);

    assertThat(result).isInstanceOf(AgentAssignment.EscalateToOversight.class);
  }

  @Test
  void decide_bootstrapCandidateWins_returnsAssigned() {
    final ClassifiedCandidate bootstrap = classified("w1", Phase.BOOTSTRAP, Double.NaN, 0.5);
    final ClassifiedCandidate borderline = classified("w2", Phase.BORDERLINE, 0.65, 1.0);
    final List<ScoredCandidate> scored =
        List.of(
            new ScoredCandidate(bootstrap, 0.5), // availability score > 0
            new ScoredCandidate(borderline, 0.0)); // excluded

    final AgentAssignment result =
        TrustCandidateClassifier.decide(List.of(bootstrap, borderline), scored, CAP);

    assertThat(result).isInstanceOf(AgentAssignment.Assigned.class);
    assertThat(((AgentAssignment.Assigned) result).workerId()).isEqualTo("w1");
  }

  // ---- Helpers ---------------------------------------------------------------

  private static AgentCandidate candidate(final String workerId, final int jobs) {
    return new AgentCandidate(workerId, Set.of(CAP), jobs, AgentHealth.READY, null);
  }

  private static ClassifiedCandidate classified(
      final String workerId, final Phase phase, final double trustScore, final double workload) {
    final OptionalDouble ts =
        Double.isNaN(trustScore) ? OptionalDouble.empty() : OptionalDouble.of(trustScore);
    return new ClassifiedCandidate(candidate(workerId, 0), phase, ts, workload);
  }
}
