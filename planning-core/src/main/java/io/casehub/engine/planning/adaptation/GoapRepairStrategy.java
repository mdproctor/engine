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
package io.casehub.engine.planning.adaptation;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ai.AgentException;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.CompletedStep;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RepairStrategy;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.engine.plan.goap.Condition;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.goap.GoapPlanner;
import io.casehub.engine.plan.goap.GoapWorldState;
import io.casehub.engine.plan.goap.PlannerConfig;
import io.casehub.engine.planning.control.GoapCostEnricher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class GoapRepairStrategy implements RepairStrategy {

  private final GoapPlanner planner = new GoapPlanner();

  @Override
  public String id() {
    return "goap-repair";
  }

  @Override
  public RevisedPlan revise(RevisionContext context) {
    var adaptCtx = context.adaptationContext();
    CaseDefinition definition = adaptCtx.definition();

    Set<String> availableCapabilities =
        context.capabilities().stream().map(c -> c.name()).collect(Collectors.toSet());

    Set<String> completedActionNames =
        adaptCtx.completedSteps().stream()
            .map(CompletedStep::capabilityName)
            .collect(Collectors.toSet());

    List<GoapAction> actions =
        definition.getGoapActions().stream()
            .filter(a -> availableCapabilities.contains(a.name()))
            .filter(a -> !completedActionNames.contains(a.name()))
            .toList();

    actions =
        GoapCostEnricher.enrichWithLearnedCosts(
            actions,
            context.experiences(),
            GoapCostEnricher.resolveMinCostSamples(definition.getCbrConfig()));

    String failedAction = null;
    if (context.cause() instanceof AdaptationCause.StepFailed failed) {
      failedAction = failed.stepId();
    }

    Set<String> blacklist = failedAction != null ? Set.of(failedAction) : Set.of();

    GoapWorldState worldState = buildWorldState(adaptCtx.currentContext(), actions);
    Set<String> goalConditions = resolveGoalConditions(definition);

    var config = new PlannerConfig(PlannerConfig.DEFAULT_MAX_ITERATIONS, blacklist, true, true);
    List<GoapAction> planned = planner.plan(worldState, goalConditions, actions, config);

    if (planned.isEmpty()) {
      throw new AgentException("GOAP repair produced no plan after blacklisting: " + blacklist);
    }

    var steps = new ArrayList<PlanStepDescriptor>();
    for (int i = 0; i < planned.size(); i++) {
      var action = planned.get(i);
      steps.add(new PlanStepDescriptor("repair-" + i, action.name(), action.name()));
    }

    return new RevisedPlan(steps, "GOAP repair — blacklisted " + blacklist);
  }

  private GoapWorldState buildWorldState(
      com.fasterxml.jackson.databind.JsonNode context, List<GoapAction> actions) {
    GoapWorldState worldState = GoapWorldState.openWorld(context);
    for (GoapAction action : actions) {
      for (String key : action.preconditions().keySet()) {
        if (worldState.get(key) == Condition.UNKNOWN) {
          worldState = worldState.with(key, Condition.FALSE);
        }
      }
    }
    return worldState;
  }

  private Set<String> resolveGoalConditions(CaseDefinition definition) {
    Map<String, Set<String>> mapping = definition.getGoalToEffectKeys();
    Set<String> allEffectKeys = new HashSet<>();
    for (Set<String> effectKeys : mapping.values()) {
      allEffectKeys.addAll(effectKeys);
    }
    return allEffectKeys;
  }
}
