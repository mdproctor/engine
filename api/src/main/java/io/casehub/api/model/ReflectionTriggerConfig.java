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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ReflectionTriggerConfig(
    boolean enabled,
    double importanceThreshold,
    int maxUnreflectedOutcomes,
    int maxSourceMemories,
    Map<String, Double> importanceWeights) {

  public static final Map<String, Double> DEFAULT_IMPORTANCE_WEIGHTS =
      Map.of("SUCCESS", 0.3, "COMPLETED", 0.3, "DECLINED", 0.6, "FAILED", 0.8, "EXPIRED", 0.5);

  public ReflectionTriggerConfig {
    if (importanceThreshold < 0.0 || importanceThreshold > 10.0)
      throw new IllegalArgumentException("importanceThreshold must be in [0, 10]");
    if (maxUnreflectedOutcomes < 1)
      throw new IllegalArgumentException("maxUnreflectedOutcomes must be >= 1");
    if (maxSourceMemories < 1) throw new IllegalArgumentException("maxSourceMemories must be >= 1");
    importanceWeights =
        importanceWeights == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(importanceWeights));
  }

  public static ReflectionTriggerConfig defaults() {
    return new ReflectionTriggerConfig(false, 3.0, 10, 50, DEFAULT_IMPORTANCE_WEIGHTS);
  }
}
