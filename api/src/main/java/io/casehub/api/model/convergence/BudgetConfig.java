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

public record BudgetConfig(
    @Nullable Integer maxDispatches,
    @Nullable Integer maxSignalDeposits,
    @Nullable Integer maxContextMutations,
    @Nullable Integer maxEvaluationCycles) {

  public BudgetConfig {
    if (maxDispatches != null && maxDispatches < 0)
      throw new IllegalArgumentException("maxDispatches must be >= 0, got: " + maxDispatches);
    if (maxSignalDeposits != null && maxSignalDeposits < 0)
      throw new IllegalArgumentException(
          "maxSignalDeposits must be >= 0, got: " + maxSignalDeposits);
    if (maxContextMutations != null && maxContextMutations < 0)
      throw new IllegalArgumentException(
          "maxContextMutations must be >= 0, got: " + maxContextMutations);
    if (maxEvaluationCycles != null && maxEvaluationCycles < 0)
      throw new IllegalArgumentException(
          "maxEvaluationCycles must be >= 0, got: " + maxEvaluationCycles);
  }

  public static BudgetConfig defaults() {
    return new BudgetConfig(null, null, null, null);
  }

  public static boolean isExceeded(long count, @Nullable Integer cap) {
    return cap != null && count > cap;
  }
}
