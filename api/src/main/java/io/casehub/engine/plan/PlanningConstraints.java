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
package io.casehub.engine.plan;

import java.time.Duration;
import java.util.Map;

public record PlanningConstraints(
    Duration timeBudget,
    Integer resourceLimit,
    Map<String, Double> weights,
    Map<String, Integer> costBudgets) {

  public PlanningConstraints {
    weights = weights != null ? Map.copyOf(weights) : Map.of();
    costBudgets = costBudgets != null ? Map.copyOf(costBudgets) : Map.of();
  }

  public static PlanningConstraints unconstrained() {
    return new PlanningConstraints(null, null, Map.of(), Map.of());
  }

  public static PlanningConstraints of(Duration timeBudget, Integer resourceLimit) {
    return new PlanningConstraints(timeBudget, resourceLimit, Map.of(), Map.of());
  }

  public boolean hasHardConstraints() {
    return timeBudget != null || resourceLimit != null || !costBudgets.isEmpty();
  }
}
