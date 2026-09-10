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
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.goap.GoapPlanner;
import io.casehub.engine.plan.goap.GoapWorldState;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
@Unremovable
public class AdaptivePlanningStrategy extends GoapPlanningStrategy {

  private final GoapPlanner planner = new GoapPlanner();
  private final Map<UUID, Set<String>> executedActions = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> replanCounts = new ConcurrentHashMap<>();

  @Override
  public String id() {
    return "adaptive";
  }

  @Override
  public String getName() {
    return "Adaptive Planning Strategy (OODA)";
  }

  @Override
  public List<Binding> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {
    if (eligible.isEmpty()) return List.of();

    UUID caseId = context.caseId();
    Set<String> executed = executedActions.getOrDefault(caseId, Set.of());
    int replanCount = replanCounts.getOrDefault(caseId, 0);

    CaseDefinition definition = context.definition();
    int maxReplans = definition.getGoapActions().size() * 2;
    if (replanCount > maxReplans) {
      return List.of();
    }

    List<GoapAction> availableActions =
        definition.getGoapActions().stream().filter(a -> !executed.contains(a.name())).toList();

    if (availableActions.isEmpty()) return List.of();

    Set<String> eligibleNames = eligible.stream().map(Binding::getName).collect(Collectors.toSet());

    List<GoapAction> filteredActions =
        availableActions.stream().filter(a -> eligibleNames.contains(a.name())).toList();

    if (filteredActions.isEmpty()) return List.of();

    int minSamples = GoapCostEnricher.resolveMinCostSamples(definition.getCbrConfig());
    filteredActions =
        GoapCostEnricher.enrichWithLearnedCosts(filteredActions, context.experiences(), minSamples);

    replanCounts.merge(caseId, 1, Integer::sum);

    GoapWorldState worldState = buildWorldState(context);
    Set<String> goalConditions = resolveGoalConditions(definition);

    if (goalConditions.isEmpty() || worldState.satisfiesAll(goalConditions)) return List.of();

    List<GoapAction> planned = planner.plan(worldState, goalConditions, filteredActions);
    if (planned.isEmpty()) return List.of();

    String nextActionName = planned.get(0).name();
    return eligible.stream().filter(b -> b.getName().equals(nextActionName)).toList();
  }

  public void recordExecution(UUID caseId, String actionName) {
    executedActions.computeIfAbsent(caseId, k -> ConcurrentHashMap.newKeySet()).add(actionName);
  }

  public void cleanCase(UUID caseId) {
    executedActions.remove(caseId);
    replanCounts.remove(caseId);
  }
}
