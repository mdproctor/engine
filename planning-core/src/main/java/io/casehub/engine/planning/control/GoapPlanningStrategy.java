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

import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.goap.GoapPlanner;
import io.casehub.engine.plan.goap.GoapWorldState;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
@Unremovable
public class GoapPlanningStrategy implements PlanningStrategy {

  private final GoapPlanner planner = new GoapPlanner();

  @Override
  public String id() {
    return "goap";
  }

  @Override
  public String getName() {
    return "GOAP Planning Strategy";
  }

  @Override
  public List<Binding> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {
    if (eligible.isEmpty()) return List.of();

    CaseDefinition definition = context.definition();
    List<GoapAction> allActions = definition.getGoapActions();
    if (allActions.isEmpty()) return List.of();

    Set<String> eligibleNames = eligible.stream().map(Binding::getName).collect(Collectors.toSet());

    List<GoapAction> filteredActions =
        allActions.stream().filter(a -> eligibleNames.contains(a.name())).toList();

    if (filteredActions.isEmpty()) return List.of();

    int minSamples = GoapCostEnricher.resolveMinCostSamples(definition.getCbrConfig());
    filteredActions =
        GoapCostEnricher.enrichWithLearnedCosts(filteredActions, context.experiences(), minSamples);

    GoapWorldState worldState = buildWorldState(context);
    Set<String> goalConditions = resolveGoalConditions(definition);

    if (goalConditions.isEmpty() || worldState.satisfiesAll(goalConditions)) return List.of();

    List<GoapAction> planned = planner.plan(worldState, goalConditions, filteredActions);
    if (planned.isEmpty()) return List.of();

    String nextActionName = planned.get(0).name();
    return eligible.stream().filter(b -> b.getName().equals(nextActionName)).toList();
  }

  protected GoapWorldState buildWorldState(PlanExecutionContext context) {
    Map<String, Boolean> conditions = new HashMap<>();
    var caseContext = context.caseContext();
    if (caseContext != null) {
      ReadableLayer workingLayer = caseContext.layer(ContextLayer.WORKING);
      if (workingLayer != null) {
        for (String key : workingLayer.getKeys()) {
          conditions.put(key, true);
        }
      }
    }
    for (GoapAction action : context.definition().getGoapActions()) {
      for (String key : action.preconditions().keySet()) {
        conditions.putIfAbsent(key, false);
      }
    }
    return GoapWorldState.closedWorld(conditions);
  }

  protected Set<String> resolveGoalConditions(CaseDefinition definition) {
    Map<String, Set<String>> mapping = definition.getGoalToEffectKeys();
    Set<String> allEffectKeys = new HashSet<>();
    for (Set<String> effectKeys : mapping.values()) {
      allEffectKeys.addAll(effectKeys);
    }
    return allEffectKeys;
  }
}
