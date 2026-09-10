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
package io.casehub.engine.planning.decomposition;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.JoinType;
import io.casehub.engine.plan.ReplanContext;
import io.casehub.engine.plan.TaskNode;
import io.casehub.engine.plan.goap.GoapAction;
import io.casehub.engine.plan.goap.GoapPlanner;
import io.casehub.engine.plan.goap.GoapWorldState;
import io.casehub.engine.plan.goap.PlannerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class GoapDecompositionStrategy implements DecompositionStrategy<JsonNode> {

  private final GoapPlanner planner = new GoapPlanner();

  @Override
  public String id() {
    return "goap";
  }

  @Override
  public DagPlan<TaskNode.LeafTask<JsonNode>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) {
    CaseDefinition definition = extractDefinition(context);
    if (definition == null) {
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");
    }

    List<GoapAction> allActions = definition.getGoapActions();
    if (allActions.isEmpty()) {
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");
    }

    Set<String> availableCapabilities = extractCapabilityNames(context);
    List<GoapAction> actions =
        allActions.stream().filter(a -> availableCapabilities.contains(a.name())).toList();
    if (actions.isEmpty()) {
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");
    }

    actions = enrichActions(actions, context, definition);

    GoapWorldState worldState = buildOpenWorldState(context.state(), actions);
    Set<String> goalConditions = resolveGoalConditions(definition);
    if (goalConditions.isEmpty()) {
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");
    }

    List<GoapAction> planned =
        planner.plan(worldState, goalConditions, actions, PlannerConfig.defaults());
    if (planned.isEmpty()) {
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");
    }

    DagPlan<TaskNode.LeafTask<JsonNode>> plan = buildDagPlan(planned);
    return attachCbrContingencies(plan, actions, worldState, goalConditions, context, definition);
  }

  @Override
  public DagPlan<TaskNode.LeafTask<JsonNode>> replan(
      TaskNode<JsonNode> task,
      DecompositionContext<JsonNode> context,
      ReplanContext<JsonNode> replanContext) {

    CaseDefinition definition = extractDefinition(context);
    if (definition == null)
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");

    Set<String> completedActions =
        replanContext.completedSteps().stream()
            .map(step -> resolveActionName(step.stepId(), replanContext.originalPlan()))
            .collect(Collectors.toSet());

    String failedActionName =
        resolveActionName(replanContext.failedStep().stepId(), replanContext.originalPlan());

    Set<String> availableCapabilities = extractCapabilityNames(context);
    List<GoapAction> actions =
        definition.getGoapActions().stream()
            .filter(a -> availableCapabilities.contains(a.name()))
            .filter(a -> !completedActions.contains(a.name()))
            .toList();

    actions = enrichActions(actions, context, definition);

    Set<String> blacklist = failedActionName != null ? Set.of(failedActionName) : Set.of();
    GoapWorldState worldState = buildOpenWorldState(context.state(), actions);
    Set<String> goalConditions = resolveGoalConditions(definition);

    var config = new PlannerConfig(PlannerConfig.DEFAULT_MAX_ITERATIONS, blacklist, true, true);
    List<GoapAction> planned = planner.plan(worldState, goalConditions, actions, config);
    if (planned.isEmpty())
      throw new io.casehub.api.model.ai.AgentException("GOAP decomposition produced no plan");

    return buildDagPlan(planned);
  }

  private DagPlan<TaskNode.LeafTask<JsonNode>> buildDagPlan(List<GoapAction> planned) {
    List<DagNode<TaskNode.LeafTask<JsonNode>>> nodes = new ArrayList<>();
    Map<String, String> effectToNodeId = new HashMap<>();

    for (int i = 0; i < planned.size(); i++) {
      GoapAction action = planned.get(i);
      String nodeId = "goap-" + i;
      var goalStep = new GoalStep(UUID.randomUUID(), action.name(), action.name(), Instant.now());

      Set<String> dependsOn = new HashSet<>();
      for (String precondKey : action.preconditions().keySet()) {
        String depNodeId = effectToNodeId.get(precondKey);
        if (depNodeId != null) {
          dependsOn.add(depNodeId);
        }
      }

      nodes.add(new DagNode<>(nodeId, goalStep, dependsOn, JoinType.ALL_OF));

      for (String effectKey : action.effects().keySet()) {
        effectToNodeId.put(effectKey, nodeId);
      }
    }

    return DagPlan.fromNodes(nodes);
  }

  private GoapWorldState buildOpenWorldState(JsonNode state, List<GoapAction> actions) {
    GoapWorldState worldState = GoapWorldState.openWorld(state);
    for (GoapAction action : actions) {
      for (String key : action.preconditions().keySet()) {
        if (worldState.get(key) == io.casehub.engine.plan.goap.Condition.UNKNOWN) {
          worldState = worldState.with(key, io.casehub.engine.plan.goap.Condition.FALSE);
        }
      }
    }
    return worldState;
  }

  private CaseDefinition extractDefinition(DecompositionContext<JsonNode> context) {
    if (context instanceof GoalDecompositionContext gdc) {
      return gdc.definition();
    }
    return null;
  }

  private Set<String> extractCapabilityNames(DecompositionContext<JsonNode> context) {
    if (context instanceof GoalDecompositionContext gdc) {
      return gdc.availableCapabilities().stream().map(c -> c.name()).collect(Collectors.toSet());
    }
    return Set.of();
  }

  private Set<String> resolveGoalConditions(CaseDefinition definition) {
    Map<String, Set<String>> mapping = definition.getGoalToEffectKeys();
    Set<String> allEffectKeys = new HashSet<>();
    for (Set<String> effectKeys : mapping.values()) {
      allEffectKeys.addAll(effectKeys);
    }
    return allEffectKeys;
  }

  private String resolveActionName(
      String stepId, DagPlan<TaskNode.LeafTask<JsonNode>> originalPlan) {
    if (originalPlan == null) return null;
    var node = originalPlan.nodes().get(stepId);
    if (node != null && node.task() instanceof GoalStep goalStep) {
      return goalStep.capabilityName();
    }
    return null;
  }

  private List<GoapAction> enrichActions(
      List<GoapAction> actions, DecompositionContext<JsonNode> context, CaseDefinition definition) {
    List<io.casehub.api.spi.routing.RetrievedExperience> experiences = extractExperiences(context);
    int minSamples =
        io.casehub.engine.planning.control.GoapCostEnricher.resolveMinCostSamples(
            definition != null ? definition.getCbrConfig() : null);
    return io.casehub.engine.planning.control.GoapCostEnricher.enrichWithLearnedCosts(
        actions, experiences, minSamples);
  }

  private DagPlan<TaskNode.LeafTask<JsonNode>> attachCbrContingencies(
      DagPlan<TaskNode.LeafTask<JsonNode>> plan,
      List<GoapAction> allActions,
      GoapWorldState worldState,
      Set<String> goalConditions,
      DecompositionContext<JsonNode> context,
      CaseDefinition definition) {

    List<io.casehub.api.spi.routing.RetrievedExperience> experiences = extractExperiences(context);
    if (experiences.isEmpty()) {
      return plan;
    }

    double threshold =
        definition.getAdaptationConfig() != null
            ? definition.getAdaptationConfig().effectiveContingencyThreshold()
            : io.casehub.api.model.AdaptationConfig.DEFAULT_CONTINGENCY_THRESHOLD;
    int minSamples =
        io.casehub.engine.planning.control.GoapCostEnricher.resolveMinCostSamples(
            definition.getCbrConfig());

    Set<String> actionNames =
        allActions.stream().map(GoapAction::name).collect(java.util.stream.Collectors.toSet());
    java.util.Map<String, Double> failureRates =
        io.casehub.api.spi.routing.ExperienceAnalyser.actionFailureRates(
            experiences, actionNames, minSamples);
    if (failureRates.isEmpty()) {
      return plan;
    }

    java.util.Map<String, DagNode<TaskNode.LeafTask<JsonNode>>> updatedNodes =
        new java.util.LinkedHashMap<>(plan.nodes());
    boolean changed = false;

    for (var entry : plan.nodes().entrySet()) {
      DagNode<TaskNode.LeafTask<JsonNode>> node = entry.getValue();
      if (node.contingency() != null) {
        continue;
      }
      String actionName = (node.task() instanceof GoalStep step) ? step.capabilityName() : null;
      if (actionName == null) {
        continue;
      }

      Double failureRate = failureRates.get(actionName);
      if (failureRate != null && failureRate >= threshold) {
        var config =
            new PlannerConfig(PlannerConfig.DEFAULT_MAX_ITERATIONS, Set.of(actionName), true, true);
        List<GoapAction> altPlan = planner.plan(worldState, goalConditions, allActions, config);
        if (!altPlan.isEmpty()) {
          DagPlan<TaskNode.LeafTask<JsonNode>> contingencyPlan = buildDagPlan(altPlan);
          if (contingencyPlan.exitNodeIds().size() == 1) {
            updatedNodes.put(
                entry.getKey(),
                new DagNode<>(
                    node.id(), node.task(), node.dependsOn(), node.joinType(), contingencyPlan));
            changed = true;
          }
        }
      }
    }

    return changed ? new DagPlan<>(updatedNodes) : plan;
  }

  private List<io.casehub.api.spi.routing.RetrievedExperience> extractExperiences(
      DecompositionContext<JsonNode> context) {
    if (context instanceof GoalDecompositionContext gdc) {
      return gdc.experiences();
    }
    return List.of();
  }
}
