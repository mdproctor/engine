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

import java.util.Map;

/**
 * Per-capability trust routing policy parameters.
 *
 * @param threshold minimum CAPABILITY trust score for selection (Phase 2 entry)
 * @param minimumObservations decision count below which routing falls to Phase 0/1 (availability)
 * @param borderlineMargin candidates whose score is within this margin of the threshold are
 *     excluded (score 0.0); tracked for escalation in engine#377
 * @param blendFactor weight of trust score vs workload efficiency (0.0 = pure workload, 1.0 = pure
 *     trust)
 * @param qualityFloors Phase 3: dimension name → minimum acceptable quality score; candidates
 *     failing any floor are excluded; no penalty if dimension data is absent
 */
public record TrustRoutingPolicy(
    double threshold,
    int minimumObservations,
    double borderlineMargin,
    double blendFactor,
    Map<String, Double> qualityFloors) {

  /** Conservative defaults: 0.7 threshold, 10 observations, 0.1 margin, 60% trust blend. */
  public static final TrustRoutingPolicy DEFAULT =
      new TrustRoutingPolicy(0.7, 10, 0.1, 0.6, Map.of());

  /** True when an agent lacks sufficient decision history for trust-based routing (Phase 0/1). */
  public boolean isBootstrap(final int decisionCount) {
    return decisionCount < minimumObservations;
  }

  /**
   * True when the trust score is within {@code borderlineMargin} of {@code threshold}.
   *
   * <p>A borderline candidate is NOT qualified for assignment. Borderline is a distinct Phase 2a
   * state that triggers human oversight when all candidates are in this state.
   */
  public boolean isBorderline(final double score) {
    return Math.abs(score - threshold) <= borderlineMargin;
  }

  /**
   * True when the score exceeds the threshold and is not borderline.
   *
   * <p>This is a Phase 2 first-pass check only — Phase 3 quality floors may still exclude a
   * candidate that passes this check. Do not interpret as "ready to assign".
   */
  public boolean passesThresholdCheck(final double score) {
    return score >= threshold && !isBorderline(score);
  }
}
