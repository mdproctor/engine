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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.internal.routing.EngineStrategyResolver;
import io.casehub.engine.internal.routing.GoalAbandonmentEvaluator;
import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.TaskNode;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DefaultGoalDecomposer implements io.casehub.engine.common.spi.GoalDecomposer {

  private static final Logger LOG = Logger.getLogger(DefaultGoalDecomposer.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject EngineStrategyResolver strategyResolver;
  @Inject GoalAbandonmentEvaluator abandonmentEvaluator;
  @Inject BlackboardRegistry blackboardRegistry;
  @Inject PlanItemStore planItemStore;
  @Inject EventLogRepository eventLogRepository;

  @ConfigProperty(name = "casehub.engine.decomposition.timeout-ms", defaultValue = "30000")
  long timeoutMs;

  @Override
  @SuppressWarnings("unchecked")
  public void decompose(
      CaseInstance instance, CaseDefinition definition, MutableCaseContext context) {
    if (definition.getDecompositionStrategy() == null) return;

    var existing = planItemStore.findByCaseId(instance.getUuid(), instance.tenancyId);
    boolean alreadyDecomposed = existing.stream().anyMatch(r -> r.parentCompoundId() != null);
    if (alreadyDecomposed) {
      LOG.debugf("Decomposition already materialized for caseId=%s — skipping", instance.getUuid());
      return;
    }

    var strategy =
        (DecompositionStrategy<JsonNode>)
            strategyResolver.resolve(
                DecompositionStrategy.class, definition.getDecompositionStrategy());

    var capabilityNames =
        definition.getCapabilities().stream().map(c -> c.name()).collect(Collectors.toSet());

    var casePlanModel = blackboardRegistry.getOrCreate(instance.getUuid(), instance.tenancyId);

    var scopedBindings = new ConcurrentHashMap<String, String>();

    for (var worker : definition.getWorkers()) {
      var descriptor = definition.agentDescriptorFor(worker.name()).orElse(null);
      if (descriptor == null) continue;

      var activeGoals = new ArrayList<>(abandonmentEvaluator.activeGoals(descriptor));
      if (activeGoals.isEmpty()) continue;

      activeGoals.sort(Comparator.comparing(AgentGoal::priority));

      for (var goal : activeGoals) {
        try {
          decomposeGoal(
              instance,
              definition,
              context,
              strategy,
              goal,
              capabilityNames,
              casePlanModel,
              scopedBindings);
        } catch (Exception e) {
          LOG.warnf(
              e,
              "Goal decomposition failed for goal=%s caseId=%s — continuing",
              goal.name(),
              instance.getUuid());
        }
      }
    }
  }

  private void decomposeGoal(
      CaseInstance instance,
      CaseDefinition definition,
      MutableCaseContext context,
      DecompositionStrategy<JsonNode> strategy,
      AgentGoal goal,
      Set<String> capabilityNames,
      io.casehub.engine.planning.plan.CasePlanModel casePlanModel,
      ConcurrentHashMap<String, String> scopedBindings) {

    var contextSnapshot = context.layer(ContextLayer.WORKING).asJsonNode();
    var decompositionContext =
        new GoalDecompositionContext(
            contextSnapshot,
            0,
            List.copyOf(definition.getCapabilities()),
            definition.getPlanningConstraints());

    var compoundTask = new TaskNode.CompoundTask<JsonNode>(goal.name(), goal.name(), List.of());

    DagPlan<TaskNode.LeafTask<JsonNode>> plan;
    try {
      plan =
          strategy
              .decompose(compoundTask, decompositionContext)
              .await()
              .atMost(Duration.ofMillis(timeoutMs));
    } catch (Exception e) {
      LOG.warnf(
          "Decomposition timed out or failed for goal=%s — graceful degradation", goal.name());
      return;
    }

    var validNodes = new ArrayList<DagNode<TaskNode.LeafTask<JsonNode>>>();
    var skipped = new ArrayList<String>();
    for (var node : plan.nodes().values()) {
      if (node.task() instanceof GoalStep step && capabilityNames.contains(step.capabilityName())) {
        validNodes.add(node);
      } else if (node.task() instanceof GoalStep step) {
        skipped.add(step.capabilityName());
        LOG.warnf(
            "Decomposition step references unknown capability '%s' — skipped",
            step.capabilityName());
      }
    }

    if (validNodes.isEmpty()) return;

    if (!isLinearChain(validNodes)) {
      LOG.warnf(
          "Decomposition produced non-linear plan for goal=%s — v1 supports sequential only",
          goal.name());
      return;
    }

    var availableBindings = new ArrayList<DagNode<TaskNode.LeafTask<JsonNode>>>();
    for (var node : validNodes) {
      var step = (GoalStep) node.task();
      var existing = scopedBindings.putIfAbsent(step.capabilityName(), goal.name());
      if (existing != null && !existing.equals(goal.name())) {
        LOG.warnf(
            "Binding '%s' already scoped by goal '%s' — excluded from '%s'",
            step.capabilityName(), existing, goal.name());
      } else {
        availableBindings.add(node);
      }
    }

    if (availableBindings.isEmpty()) return;

    var compoundBuilder =
        PlanItemDefinition.Compound.builder(goal.name())
            .completion(CompletionSemantics.all())
            .dispatchMode(DispatchMode.CHOREOGRAPHED);

    for (int i = 0; i < availableBindings.size(); i++) {
      var step = (GoalStep) availableBindings.get(i).task();
      var primitiveId = goal.name() + "-step-" + i;
      compoundBuilder.child(
          new PlanItemDefinition.Primitive(primitiveId, step.description(), null, null));
      compoundBuilder.binding(step.capabilityName());
    }

    var compound = compoundBuilder.build();
    casePlanModel.registerDefinition(compound);

    for (int i = 0; i < availableBindings.size(); i++) {
      var step = (GoalStep) availableBindings.get(i).task();
      planItemStore.save(
          PlanItemSaveRequest.primitive(
              instance.getUuid(),
              step.id(),
              step.capabilityName(),
              TaskStatus.PENDING,
              Instant.now(),
              TargetType.CAPABILITY,
              null,
              instance.tenancyId,
              step.description(),
              null,
              null),
          instance.tenancyId);
    }

    var eventLog = new EventLog();
    eventLog.setCaseId(instance.getUuid());
    eventLog.setEventType(CaseHubEventType.GOAL_DECOMPOSED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    var meta = OBJECT_MAPPER.createObjectNode();
    meta.put("goalName", goal.name());
    meta.put("strategyId", definition.getDecompositionStrategy());
    meta.put("stepCount", availableBindings.size());
    if (!skipped.isEmpty()) {
      meta.set("skippedSteps", OBJECT_MAPPER.valueToTree(skipped));
    }
    eventLog.setMetadata(meta);
    eventLogRepository.append(eventLog, instance.tenancyId);
  }

  private boolean isLinearChain(List<DagNode<TaskNode.LeafTask<JsonNode>>> nodes) {
    if (nodes.size() <= 1) return true;
    long entryCount = nodes.stream().filter(n -> n.dependsOn().isEmpty()).count();
    if (entryCount != 1) return false;
    for (var node : nodes) {
      if (node.dependsOn().size() > 1) return false;
    }
    return true;
  }
}
