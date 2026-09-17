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
package io.casehub.api.model.convergence;

public record OutputConvergenceConfig(
    double convergenceThreshold, int convergenceMinSamples, int outputWindowSize) {

  public static final double DEFAULT_CONVERGENCE_THRESHOLD = 0.9;
  public static final int DEFAULT_CONVERGENCE_MIN_SAMPLES = 3;
  public static final int DEFAULT_OUTPUT_WINDOW_SIZE = 10;

  public OutputConvergenceConfig {
    if (convergenceThreshold < 0.0 || convergenceThreshold > 1.0)
      throw new IllegalArgumentException(
          "convergenceThreshold must be in [0.0, 1.0], got: " + convergenceThreshold);
    if (convergenceMinSamples < 2)
      throw new IllegalArgumentException(
          "convergenceMinSamples must be >= 2, got: " + convergenceMinSamples);
    if (outputWindowSize < 2)
      throw new IllegalArgumentException("outputWindowSize must be >= 2, got: " + outputWindowSize);
  }

  public static OutputConvergenceConfig defaults() {
    return new OutputConvergenceConfig(
        DEFAULT_CONVERGENCE_THRESHOLD, DEFAULT_CONVERGENCE_MIN_SAMPLES, DEFAULT_OUTPUT_WINDOW_SIZE);
  }
}
