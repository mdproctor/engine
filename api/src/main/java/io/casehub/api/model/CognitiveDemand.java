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
package io.casehub.api.model;

import java.util.Map;

/**
 * Weighted cognitive function demand profile for a capability. Keys are Jungian function name
 * strings (Ti, Te, Fi, Fe, Si, Se, Ni, Ne). Weights are 0.0–1.0 and must sum to 1.0.
 *
 * <p>Used by {@code PersonalitySignalProvider} to score candidates by alignment between the task's
 * cognitive demands and the agent's personality profile.
 *
 * @param functionWeights function name → demand weight, summing to 1.0
 */
public record CognitiveDemand(Map<String, Double> functionWeights) {
  public CognitiveDemand {
    functionWeights = Map.copyOf(functionWeights);
    double sum = functionWeights.values().stream().mapToDouble(Double::doubleValue).sum();
    if (Math.abs(sum - 1.0) > 0.01) {
      throw new IllegalArgumentException("functionWeights must sum to 1.0, got " + sum);
    }
  }
}
