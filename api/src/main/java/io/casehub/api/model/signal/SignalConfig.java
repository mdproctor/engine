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
package io.casehub.api.model.signal;

import java.time.Duration;

public record SignalConfig(
    Duration defaultHalfLife, double effectiveZeroThreshold, int maxSignalsPerCase) {

  public static final Duration DEFAULT_HALF_LIFE = Duration.ofMinutes(5);
  public static final double DEFAULT_EFFECTIVE_ZERO_THRESHOLD = 0.01;
  public static final int DEFAULT_MAX_SIGNALS_PER_CASE = 100;

  public SignalConfig {
    if (effectiveZeroThreshold < 0.0 || effectiveZeroThreshold > 1.0)
      throw new IllegalArgumentException(
          "effectiveZeroThreshold must be in [0.0, 1.0], got: " + effectiveZeroThreshold);
    if (maxSignalsPerCase < 1)
      throw new IllegalArgumentException(
          "maxSignalsPerCase must be >= 1, got: " + maxSignalsPerCase);
    if (defaultHalfLife.isNegative() || defaultHalfLife.isZero())
      throw new IllegalArgumentException(
          "defaultHalfLife must be positive, got: " + defaultHalfLife);
  }

  public static SignalConfig defaults() {
    return new SignalConfig(
        DEFAULT_HALF_LIFE, DEFAULT_EFFECTIVE_ZERO_THRESHOLD, DEFAULT_MAX_SIGNALS_PER_CASE);
  }
}
