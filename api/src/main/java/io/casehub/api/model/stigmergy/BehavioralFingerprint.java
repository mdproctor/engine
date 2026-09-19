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

import java.util.Map;

public record BehavioralFingerprint(
    Map<String, Double> perception,
    Map<String, Double> communication,
    Map<String, Double> decision,
    Map<String, Double> effect) {

  public static final BehavioralFingerprint EMPTY =
      new BehavioralFingerprint(Map.of(), Map.of(), Map.of(), Map.of());

  public BehavioralFingerprint {
    perception = Map.copyOf(perception);
    communication = Map.copyOf(communication);
    decision = Map.copyOf(decision);
    effect = Map.copyOf(effect);
  }

  public static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
    if (a.isEmpty() && b.isEmpty()) return 1.0;
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    double dot = 0.0, magA = 0.0, magB = 0.0;
    for (var entry : a.entrySet()) {
      double va = entry.getValue();
      magA += va * va;
      Double vb = b.get(entry.getKey());
      if (vb != null) dot += va * vb;
    }
    for (double vb : b.values()) magB += vb * vb;
    double denom = Math.sqrt(magA) * Math.sqrt(magB);
    return denom > 0 ? dot / denom : 0.0;
  }

  public double weightedSimilarity(BehavioralFingerprint other, RoleDomainWeights weights) {
    double[] w = weights.normalized();
    return w[0] * cosineSimilarity(perception, other.perception)
        + w[1] * cosineSimilarity(communication, other.communication)
        + w[2] * cosineSimilarity(decision, other.decision)
        + w[3] * cosineSimilarity(effect, other.effect);
  }
}
