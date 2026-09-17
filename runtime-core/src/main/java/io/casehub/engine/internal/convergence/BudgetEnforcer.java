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
package io.casehub.engine.internal.convergence;

import io.casehub.api.model.convergence.BudgetConfig;
import jakarta.annotation.Nullable;

public class BudgetEnforcer {

  public boolean isExhausted(
      @Nullable BudgetConfig config,
      long dispatches,
      long signalDeposits,
      long contextMutations,
      long evaluationCycles) {
    if (config == null) return false;
    return BudgetConfig.isExceeded(dispatches, config.maxDispatches())
        || BudgetConfig.isExceeded(signalDeposits, config.maxSignalDeposits())
        || BudgetConfig.isExceeded(contextMutations, config.maxContextMutations())
        || BudgetConfig.isExceeded(evaluationCycles, config.maxEvaluationCycles());
  }

  public @Nullable String exhaustedMetric(
      @Nullable BudgetConfig config,
      long dispatches,
      long signalDeposits,
      long contextMutations,
      long evaluationCycles) {
    if (config == null) return null;
    if (BudgetConfig.isExceeded(dispatches, config.maxDispatches())) return "dispatches";
    if (BudgetConfig.isExceeded(signalDeposits, config.maxSignalDeposits()))
      return "signalDeposits";
    if (BudgetConfig.isExceeded(contextMutations, config.maxContextMutations()))
      return "contextMutations";
    if (BudgetConfig.isExceeded(evaluationCycles, config.maxEvaluationCycles()))
      return "evaluationCycles";
    return null;
  }
}
