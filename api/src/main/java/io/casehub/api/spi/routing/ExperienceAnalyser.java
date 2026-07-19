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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared utility for computing per-worker success rates from CBR plan trace data. Used by both
 * {@link io.casehub.ledger.routing.TrustWeightedAgentStrategy} (engine-ledger, trust-blended
 * scoring) and {@code CbrAgentRoutingStrategy} (blocks, CBR-first routing).
 *
 * <p>Stateless — all methods are static. Co-located with {@link RetrievedExperience} and {@link
 * ExperiencePlanStep} which it operates on.
 */
public final class ExperienceAnalyser {

  public static final Map<RoutingOutcome, Double> DEFAULT_OUTCOME_WEIGHTS =
      Map.of(
          RoutingOutcome.SUCCESS, 1.0,
          RoutingOutcome.GATE_EXPIRED, 0.5,
          RoutingOutcome.GATE_REJECTED, 0.25,
          RoutingOutcome.FAILURE, 0.0);

  private ExperienceAnalyser() {}

  /**
   * Computes per-worker success rates using a caller-supplied step filter predicate.
   *
   * <p>Generalisation of {@link #workerSuccessRates(List, Set, String, Map)} — the predicate
   * replaces the hardcoded {@code capabilityName.equals(step.capabilityName())} check, enabling
   * callers to match on any step field (e.g. {@code step.bindingName()} for humanTask traces).
   *
   * @param experiences retrieved similar cases from the CBR store
   * @param eligibleWorkerIds worker IDs to score
   * @param stepFilter predicate selecting which plan trace steps to include in scoring
   * @param outcomeWeights per-outcome scoring weights
   * @return per-worker scores in [0.0, 1.0]; empty map when no matching data
   */
  public static Map<String, Double> workerSuccessRates(
      final List<RetrievedExperience> experiences,
      final Set<String> eligibleWorkerIds,
      final java.util.function.Predicate<ExperiencePlanStep> stepFilter,
      final Map<RoutingOutcome, Double> outcomeWeights) {
    final Map<String, double[]> workerStats = new HashMap<>();

    for (final RetrievedExperience exp : experiences) {
      final double relevance = exp.similarityScore();
      if (relevance <= 0.0) {
        continue;
      }

      for (final ExperiencePlanStep step : exp.planTrace()) {
        if (!stepFilter.test(step)
            || step.workerName() == null
            || !eligibleWorkerIds.contains(step.workerName())) {
          continue;
        }

        if ("ADDED".equals(step.adaptationAction())
            || "SUBSTITUTED".equals(step.adaptationAction())) {
          continue;
        }

        RoutingOutcome outcome;
        try {
          outcome = RoutingOutcome.valueOf(step.stepOutcome());
        } catch (final IllegalArgumentException e) {
          continue;
        }

        final double outcomeWeight = outcomeWeights.getOrDefault(outcome, 0.0);
        final double[] stats = workerStats.computeIfAbsent(step.workerName(), k -> new double[2]);
        stats[0] += outcomeWeight * relevance;
        stats[1] += relevance;
      }
    }

    final Map<String, Double> scores = new HashMap<>();
    for (final Map.Entry<String, double[]> entry : workerStats.entrySet()) {
      final double evidenceMass = entry.getValue()[1];
      if (evidenceMass > 0.0) {
        scores.put(entry.getKey(), entry.getValue()[0] / evidenceMass);
      }
    }
    return scores;
  }

  /**
   * Computes per-worker success rates, filtering plan trace steps by capability name.
   *
   * <p>Delegates to the predicate overload. Steps with null {@code capabilityName} (e.g. humanTask
   * traces) are naturally excluded since {@code "x".equals(null)} is false.
   */
  public static Map<String, Double> workerSuccessRates(
      final List<RetrievedExperience> experiences,
      final Set<String> eligibleWorkerIds,
      final String capabilityName,
      final Map<RoutingOutcome, Double> outcomeWeights) {
    return workerSuccessRates(
        experiences,
        eligibleWorkerIds,
        step -> capabilityName.equals(step.capabilityName()),
        outcomeWeights);
  }
}
