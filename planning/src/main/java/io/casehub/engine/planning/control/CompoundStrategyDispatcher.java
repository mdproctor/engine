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

@jakarta.enterprise.context.ApplicationScoped
public class CompoundStrategyDispatcher {

  private static final String DEFAULT_STRATEGY = "default";

  private final java.util.function.Function<String, PlanningStrategy> strategyResolver;

  @jakarta.inject.Inject
  public CompoundStrategyDispatcher(
      jakarta.enterprise.inject.Instance<PlanningStrategy> strategyBeans) {
    java.util.Map<String, PlanningStrategy> strategies =
        java.util.stream.StreamSupport.stream(strategyBeans.spliterator(), false)
            .collect(java.util.stream.Collectors.toMap(PlanningStrategy::id, s -> s));
    this.strategyResolver = strategies::get;
  }

  CompoundStrategyDispatcher(
      java.util.function.Function<String, PlanningStrategy> strategyResolver) {
    this.strategyResolver = strategyResolver;
  }

  public java.util.List<io.casehub.api.model.Binding> dispatch(
      io.casehub.engine.planning.plan.CasePlanModel plan,
      io.casehub.api.engine.PlanExecutionContext ctx,
      java.util.List<io.casehub.api.model.Binding> eligible) {
    if (eligible.isEmpty()) {
      return java.util.List.of();
    }

    java.util.Map<String, java.util.List<io.casehub.api.model.Binding>> byCompound =
        new java.util.LinkedHashMap<>();
    java.util.List<io.casehub.api.model.Binding> freeFloating = new java.util.ArrayList<>();

    for (io.casehub.api.model.Binding binding : eligible) {
      java.util.Optional<String> parentOpt = plan.getParentOf(binding.getName());
      if (parentOpt.isPresent()) {
        byCompound.computeIfAbsent(parentOpt.get(), k -> new java.util.ArrayList<>()).add(binding);
      } else {
        freeFloating.add(binding);
      }
    }

    java.util.List<io.casehub.api.model.Binding> result = new java.util.ArrayList<>();

    for (var entry : byCompound.entrySet()) {
      String compoundId = entry.getKey();
      java.util.List<io.casehub.api.model.Binding> groupBindings = entry.getValue();
      io.casehub.engine.planning.plan.PlanItemDefinition def = plan.getDefinition(compoundId);
      if (def instanceof io.casehub.engine.planning.plan.PlanItemDefinition.Compound compound) {
        String strategyId =
            compound.planningStrategy() != null ? compound.planningStrategy() : DEFAULT_STRATEGY;
        PlanningStrategy strategy = strategyResolver.apply(strategyId);
        if (strategy == null) {
          strategy = strategyResolver.apply(DEFAULT_STRATEGY);
        }
        result.addAll(strategy.select(plan, ctx, compound, groupBindings));
      } else {
        result.addAll(groupBindings);
      }
    }

    if (!freeFloating.isEmpty()) {
      String strategyId =
          ctx.definition() != null && ctx.definition().getPlanningStrategy() != null
              ? ctx.definition().getPlanningStrategy()
              : DEFAULT_STRATEGY;
      PlanningStrategy strategy = strategyResolver.apply(strategyId);
      if (strategy == null) {
        strategy = strategyResolver.apply(DEFAULT_STRATEGY);
      }
      result.addAll(strategy.select(plan, ctx, freeFloating));
    }

    return result;
  }
}
