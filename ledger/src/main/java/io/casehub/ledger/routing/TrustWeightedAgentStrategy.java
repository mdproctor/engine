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
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Trust-aware {@link AgentRoutingStrategy} implementing the four-phase trust maturity model
 * (trust-maturity-model.md). Activates automatically when {@code casehub-engine-ledger} is on the
 * classpath — no configuration required.
 *
 * <h3>Scoring phases</h3>
 *
 * <ul>
 *   <li><b>Phase 0:</b> No CAPABILITY history → availability routing (Gastown parity). Score =
 *       1/(1+runningJobs), same as {@code LeastLoadedAgentStrategy}.
 *   <li><b>Phase 1:</b> History exists but {@code decisionCount < minimumObservations} → treated as
 *       Phase 0.
 *   <li><b>Phase 2a:</b> Borderline (|score - threshold| ≤ borderlineMargin) → excluded (score
 *       0.0). See engine#377 for escalation path when all candidates are borderline.
 *   <li><b>Phase 2b:</b> Below threshold → excluded (score 0.0).
 *   <li><b>Phase 3:</b> Quality floor check (CAPABILITY_DIMENSION). If dimension data exists and
 *       score < floor → excluded (score 0.0). Missing dimension data is not penalised (graceful
 *       Phase 0 for that dimension).
 *   <li><b>Passed all checks:</b> blend score = trust × blendFactor + workload × (1 - blendFactor).
 * </ul>
 *
 * <h3>Mixed-pool policy (explicit)</h3>
 *
 * A Phase 0 candidate (no history) always outscores a borderline Phase 2 candidate (score 0.0). New
 * agents with no track record are preferred over established agents with borderline trust.
 *
 * <p>Deferred: full-exclusion escalation (all candidates score 0.0 → noOp) tracked in engine#377.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TrustWeightedAgentStrategy implements AgentRoutingStrategy {

  private final TrustScoreCache cache;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustWeightedAgentStrategy(
      final TrustScoreCache cache, final TrustRoutingPolicyProvider policyProvider) {
    this.cache = cache;
    this.policyProvider = policyProvider;
  }

  @Override
  public AgentAssignment select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return AgentAssignment.noOp();
    }

    final TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());

    return candidates.stream()
        .map(c -> new ScoredCandidate(c, score(c, context.capabilityName(), policy)))
        .max(Comparator.comparingDouble(ScoredCandidate::score))
        .filter(sc -> sc.score() > 0.0)
        .map(sc -> new AgentAssignment(sc.candidate().workerId()))
        .orElse(AgentAssignment.noOp());
  }

  private double score(
      final AgentCandidate candidate,
      final String capabilityName,
      final TrustRoutingPolicy policy) {

    final OptionalDouble capScore = cache.getCapabilityScore(candidate.workerId(), capabilityName);

    // Phase 0: no CAPABILITY history → availability routing (Gastown parity)
    if (capScore.isEmpty()) {
      return availabilityScore(candidate);
    }

    // Phase 1: insufficient observations → availability routing
    if (cache.getDecisionCount(candidate.workerId(), capabilityName)
        < policy.minimumObservations()) {
      return availabilityScore(candidate);
    }

    final double t = capScore.getAsDouble();

    // Phase 2a: borderline → excluded
    if (Math.abs(t - policy.threshold()) <= policy.borderlineMargin()) {
      return 0.0;
    }

    // Phase 2b: below threshold → excluded
    if (t < policy.threshold()) {
      return 0.0;
    }

    // Phase 3: quality floor checks (CAPABILITY_DIMENSION)
    for (final Map.Entry<String, Double> floor : policy.qualityFloors().entrySet()) {
      final OptionalDouble quality =
          cache.getCapabilityDimensionScore(candidate.workerId(), capabilityName, floor.getKey());
      // Data present and below floor → excluded
      if (quality.isPresent() && quality.getAsDouble() < floor.getValue()) {
        return 0.0;
      }
      // No data for this dimension → graceful Phase 0 for that dimension; not penalised
    }

    // All checks passed: blend trust score with workload efficiency
    return t * policy.blendFactor() + workloadScore(candidate) * (1.0 - policy.blendFactor());
  }

  // Phase 0/1: Gastown parity — route by workload when no trust signal is available
  private double availabilityScore(final AgentCandidate candidate) {
    return workloadScore(candidate);
  }

  // 1.0 = fully idle, approaches 0 asymptotically as load increases; never exactly 0
  private double workloadScore(final AgentCandidate candidate) {
    return 1.0 / (1.0 + candidate.runningJobs());
  }

  private record ScoredCandidate(AgentCandidate candidate, double score) {}
}
