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

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Shared classification utility for trust-based routing strategies. Classifies each candidate by
 * trust maturity phase and determines the routing outcome.
 *
 * <p>Both {@link TrustWeightedAgentStrategy} and {@code SemanticAgentRoutingStrategy} share the
 * same 4-phase classification loop and the same outcome decision — only the scoring of QUALIFIED
 * candidates differs between them. This bean encapsulates the common logic to avoid duplication.
 *
 * <p>No mutable state — singleton {@code @ApplicationScoped} is safe.
 */
@ApplicationScoped
public class TrustCandidateClassifier {

  /**
   * Phase classification for a single candidate after trust analysis.
   *
   * <ul>
   *   <li>BOOTSTRAP — Phase 0/1: insufficient history; workload routing applies
   *   <li>QUALIFIED — Phase 2/3 passed: trust + workload blend applies
   *   <li>BORDERLINE — Phase 2a: score within margin of threshold; excluded; tracked separately
   *       from EXCLUDED so that all-borderline pools trigger escalation rather than unresolvable
   *   <li>EXCLUDED_PHASE2B — Phase 2b: score below threshold; excluded
   *   <li>EXCLUDED_PHASE3 — Phase 3: passed threshold but failed a quality floor; excluded;
   *       distinct from EXCLUDED_PHASE2B for future diagnostic use
   * </ul>
   */
  public enum Phase {
    BOOTSTRAP,
    QUALIFIED,
    BORDERLINE,
    EXCLUDED_PHASE2B,
    EXCLUDED_PHASE3
  }

  /**
   * A candidate with its trust phase classification and scores.
   *
   * <p>{@code trustScore} is empty for BOOTSTRAP candidates (no trust signal). Any code that reads
   * {@code trustScore} must handle the empty case — it cannot accidentally receive NaN.
   *
   * <p>{@code workloadScore} = 1/(1+runningJobs) — always computed for all phases.
   */
  public record ClassifiedCandidate(
      AgentCandidate candidate, Phase phase, OptionalDouble trustScore, double workloadScore) {

    /** True when this candidate is excluded from assignment regardless of scoring. */
    public boolean isExcluded() {
      return phase == Phase.BORDERLINE
          || phase == Phase.EXCLUDED_PHASE2B
          || phase == Phase.EXCLUDED_PHASE3;
    }
  }

  /**
   * A candidate paired with its final routing score after the strategy applies its scoring
   * algorithm. Strategies create these from {@link ClassifiedCandidate} instances.
   */
  public record ScoredCandidate(ClassifiedCandidate classified, double finalScore) {}

  /**
   * Classify all candidates by trust phase using the given policy and cache.
   *
   * <p>BOOTSTRAP: no capability score in cache, OR decision count below {@code
   * minimumObservations}. BORDERLINE: score within {@code borderlineMargin} of {@code threshold}.
   * EXCLUDED_PHASE2B: below threshold and not borderline. EXCLUDED_PHASE3: passed threshold check
   * but failed a quality floor (data present and below floor; no data → not penalised). QUALIFIED:
   * passed threshold check and all quality floors.
   */
  public List<ClassifiedCandidate> classify(
      final List<AgentCandidate> candidates,
      final String capabilityName,
      final TrustRoutingPolicy policy,
      final TrustScoreCache cache) {

    final List<ClassifiedCandidate> result = new ArrayList<>(candidates.size());
    for (final AgentCandidate candidate : candidates) {
      result.add(classifyOne(candidate, capabilityName, policy, cache));
    }
    return result;
  }

  private ClassifiedCandidate classifyOne(
      final AgentCandidate candidate,
      final String capabilityName,
      final TrustRoutingPolicy policy,
      final TrustScoreCache cache) {

    final double workload = 1.0 / (1.0 + candidate.runningJobs());
    final OptionalDouble capScore = cache.getCapabilityScore(candidate.workerId(), capabilityName);

    if (capScore.isEmpty()
        || policy.isBootstrap(cache.getDecisionCount(candidate.workerId(), capabilityName))) {
      return new ClassifiedCandidate(candidate, Phase.BOOTSTRAP, OptionalDouble.empty(), workload);
    }

    final double score = capScore.getAsDouble();

    if (policy.isBorderline(score)) {
      return new ClassifiedCandidate(
          candidate, Phase.BORDERLINE, OptionalDouble.of(score), workload);
    }

    if (!policy.passesThresholdCheck(score)) {
      return new ClassifiedCandidate(
          candidate, Phase.EXCLUDED_PHASE2B, OptionalDouble.of(score), workload);
    }

    // Phase 3: quality floor checks
    for (final Map.Entry<String, Double> floor : policy.qualityFloors().entrySet()) {
      final OptionalDouble quality =
          cache.getCapabilityDimensionScore(candidate.workerId(), capabilityName, floor.getKey());
      if (quality.isPresent() && quality.getAsDouble() < floor.getValue()) {
        return new ClassifiedCandidate(
            candidate, Phase.EXCLUDED_PHASE3, OptionalDouble.of(score), workload);
      }
    }

    return new ClassifiedCandidate(candidate, Phase.QUALIFIED, OptionalDouble.of(score), workload);
  }

  /**
   * Given classified candidates and their final scores, determine the routing outcome.
   *
   * <ul>
   *   <li>Any candidate scored above 0.0 → {@link AgentAssignment#assign(String)} with the highest
   *   <li>All scored 0.0 AND any was BORDERLINE → {@link AgentAssignment#escalate(String)} (human
   *       oversight required per trust-maturity-model.md Phase 2)
   *   <li>All scored 0.0 AND no BORDERLINE → {@link AgentAssignment#unresolvable()}
   * </ul>
   */
  public static AgentAssignment decide(
      final List<ClassifiedCandidate> classified,
      final List<ScoredCandidate> scored,
      final String capabilityName) {

    ScoredCandidate best = null;
    for (final ScoredCandidate sc : scored) {
      if (best == null || sc.finalScore() > best.finalScore()) {
        best = sc;
      }
    }

    if (best != null && best.finalScore() > 0.0) {
      return AgentAssignment.assign(best.classified().candidate().workerId());
    }

    final boolean anyBorderline = classified.stream().anyMatch(c -> c.phase() == Phase.BORDERLINE);

    return anyBorderline
        ? AgentAssignment.escalate(capabilityName)
        : AgentAssignment.unresolvable();
  }
}
