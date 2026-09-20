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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;
import java.util.Map;

public record HealthPolicy(
    @Nullable Double healthThreshold,
    @Nullable Double healthDeltaThreshold,
    @Nullable Integer healthWindowMinutes,
    @Nullable Integer recoveryWindowMinutes,
    @Nullable Integer halfOpenMaxImprovements,
    @Nullable Map<String, Double> weights) {

  public double effectiveHealthThreshold() {
    return healthThreshold != null ? healthThreshold : 0.6;
  }

  public double effectiveHealthDeltaThreshold() {
    return healthDeltaThreshold != null ? healthDeltaThreshold : 0.15;
  }

  public int effectiveHealthWindowMinutes() {
    return healthWindowMinutes != null ? healthWindowMinutes : 1440;
  }

  public int effectiveRecoveryWindowMinutes() {
    return recoveryWindowMinutes != null ? recoveryWindowMinutes : 120;
  }

  public int effectiveHalfOpenMaxImprovements() {
    return halfOpenMaxImprovements != null ? halfOpenMaxImprovements : 2;
  }

  public Map<String, Double> effectiveWeights() {
    if (weights != null) return weights;
    return Map.of(
        "stability", 0.2,
        "performance", 0.15,
        "execution", 0.1,
        "safety", 0.15,
        "integration", 0.1,
        "autonomy", 0.1,
        "cognitive-reasoning", 0.1,
        "coordination", 0.1);
  }
}
