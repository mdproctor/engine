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

import jakarta.annotation.Nullable;
import java.time.Duration;

public record ConvergenceThresholdConfig(
    @Nullable Double dispatchRateThreshold,
    @Nullable Double signalDepositRateThreshold,
    @Nullable Double contextMutationRateThreshold,
    @Nullable Double evaluationRateThreshold,
    @Nullable Duration stabilityWindow,
    @Nullable Duration rateWindow,
    @Nullable Integer maxWindowEntries) {

  public static final double DEFAULT_DISPATCH_RATE = 0.1;
  public static final double DEFAULT_SIGNAL_DEPOSIT_RATE = 0.1;
  public static final double DEFAULT_CONTEXT_MUTATION_RATE = 0.1;
  public static final double DEFAULT_EVALUATION_RATE = 0.5;
  public static final Duration DEFAULT_STABILITY_WINDOW = Duration.ofSeconds(30);
  public static final Duration DEFAULT_RATE_WINDOW = Duration.ofSeconds(60);

  public ConvergenceThresholdConfig {
    if (dispatchRateThreshold != null && dispatchRateThreshold < 0)
      throw new IllegalArgumentException("dispatchRateThreshold must be >= 0");
    if (signalDepositRateThreshold != null && signalDepositRateThreshold < 0)
      throw new IllegalArgumentException("signalDepositRateThreshold must be >= 0");
    if (contextMutationRateThreshold != null && contextMutationRateThreshold < 0)
      throw new IllegalArgumentException("contextMutationRateThreshold must be >= 0");
    if (evaluationRateThreshold != null && evaluationRateThreshold < 0)
      throw new IllegalArgumentException("evaluationRateThreshold must be >= 0");
    if (stabilityWindow != null && (stabilityWindow.isNegative() || stabilityWindow.isZero()))
      throw new IllegalArgumentException("stabilityWindow must be positive");
    if (rateWindow != null && (rateWindow.isNegative() || rateWindow.isZero()))
      throw new IllegalArgumentException("rateWindow must be positive");
    if (maxWindowEntries != null && maxWindowEntries < 1)
      throw new IllegalArgumentException("maxWindowEntries must be >= 1");
  }

  public static ConvergenceThresholdConfig defaults() {
    return new ConvergenceThresholdConfig(
        DEFAULT_DISPATCH_RATE,
        DEFAULT_SIGNAL_DEPOSIT_RATE,
        DEFAULT_CONTEXT_MUTATION_RATE,
        DEFAULT_EVALUATION_RATE,
        DEFAULT_STABILITY_WINDOW,
        DEFAULT_RATE_WINDOW,
        null);
  }

  public int effectiveMaxWindowEntries() {
    if (maxWindowEntries != null) return maxWindowEntries;
    Duration window = rateWindow != null ? rateWindow : DEFAULT_RATE_WINDOW;
    return (int) (window.toSeconds() * 10);
  }

  public double effectiveDispatchRateThreshold() {
    return dispatchRateThreshold != null ? dispatchRateThreshold : DEFAULT_DISPATCH_RATE;
  }

  public double effectiveSignalDepositRateThreshold() {
    return signalDepositRateThreshold != null
        ? signalDepositRateThreshold
        : DEFAULT_SIGNAL_DEPOSIT_RATE;
  }

  public double effectiveContextMutationRateThreshold() {
    return contextMutationRateThreshold != null
        ? contextMutationRateThreshold
        : DEFAULT_CONTEXT_MUTATION_RATE;
  }

  public double effectiveEvaluationRateThreshold() {
    return evaluationRateThreshold != null ? evaluationRateThreshold : DEFAULT_EVALUATION_RATE;
  }

  public Duration effectiveStabilityWindow() {
    return stabilityWindow != null ? stabilityWindow : DEFAULT_STABILITY_WINDOW;
  }

  public Duration effectiveRateWindow() {
    return rateWindow != null ? rateWindow : DEFAULT_RATE_WINDOW;
  }
}
