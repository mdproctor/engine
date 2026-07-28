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
package io.casehub.engine.planning.control;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class CompoundStrategyDispatcher {

  private static final String DEFAULT_STRATEGY = "default";

  private final Function<String, PlanningStrategy> strategyResolver;

  public CompoundStrategyDispatcher(Function<String, PlanningStrategy> strategyResolver) {
    this.strategyResolver = strategyResolver;
  }

  public List<Binding> dispatch(
      CasePlanModel plan, PlanExecutionContext ctx, List<Binding> eligible) {
    if (eligible.isEmpty()) return List.of();

    Map<String, List<Binding>> byCompound = new LinkedHashMap<>();
    List<Binding> freeFloating = new ArrayList<>();

    for (Binding binding : eligible) {
      Optional<String> parentOpt = plan.getParentOf(binding.getName());
      if (parentOpt.isPresent()) {
        byCompound.computeIfAbsent(parentOpt.get(), k -> new ArrayList<>()).add(binding);
      } else {
        freeFloating.add(binding);
      }
    }

    List<Binding> result = new ArrayList<>();

    for (var entry : byCompound.entrySet()) {
      String compoundId = entry.getKey();
      List<Binding> groupBindings = entry.getValue();
      PlanItemDefinition def = plan.getDefinition(compoundId);
      if (def instanceof PlanItemDefinition.Compound compound) {
        String strategyId =
            compound.planningStrategy() != null ? compound.planningStrategy() : DEFAULT_STRATEGY;
        PlanningStrategy strategy = strategyResolver.apply(strategyId);
        if (strategy == null) strategy = strategyResolver.apply(DEFAULT_STRATEGY);
        result.addAll(strategy.select(plan, ctx, compound, groupBindings));
      } else {
        result.addAll(groupBindings);
      }
    }

    if (!freeFloating.isEmpty()) {
      PlanningStrategy defaultStrategy = strategyResolver.apply(DEFAULT_STRATEGY);
      result.addAll(defaultStrategy.select(plan, ctx, freeFloating));
    }

    return result;
  }
}
