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
package io.casehub.engine.internal.improvement.area;

import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment.LandscapePosition;
import io.casehub.api.spi.improvement.CapabilityArea;
import java.time.Instant;

public abstract class AbstractCapabilityArea implements CapabilityArea {

  protected CapabilityAreaAssessment neutralAssessment() {
    return new CapabilityAreaAssessment(
        id(), 0.5, LandscapePosition.ABSENT, 0.0, 0.0, 0.0, Instant.now());
  }

  protected LandscapePosition positionFromScore(double score) {
    if (score < 0.4) return LandscapePosition.BEHIND;
    if (score < 0.7) return LandscapePosition.AT_PARITY;
    return LandscapePosition.AHEAD;
  }

  protected double impactEstimate(double healthScore) {
    return 1.0 - healthScore;
  }

  protected double costEstimate(double healthScore) {
    return healthScore < 0.5 ? 0.7 : 0.3;
  }

  protected double roi(double healthScore) {
    double cost = costEstimate(healthScore);
    return cost > 0 ? impactEstimate(healthScore) / cost : 0.0;
  }

  protected CapabilityAreaAssessment buildAssessment(double healthScore) {
    return new CapabilityAreaAssessment(
        id(),
        healthScore,
        positionFromScore(healthScore),
        impactEstimate(healthScore),
        costEstimate(healthScore),
        roi(healthScore),
        Instant.now());
  }
}
