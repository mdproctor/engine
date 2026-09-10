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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.FailureCategory;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.routing.EngineStrategyResolver;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionStrategy;
import io.casehub.engine.plan.TaskNode;
import io.casehub.engine.planning.decomposition.GoalDecompositionContext;
import io.casehub.engine.planning.decomposition.GoalStep;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DeeperDecompositionHandler {

  private static final Logger LOG = Logger.getLogger(DeeperDecompositionHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int DEFAULT_MAX_DEPTH = 3;
  private static final int MIN_SUB_STEPS = 2;

  @Inject EngineStrategyResolver strategyResolver;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject PlanItemStore planItemStore;
  @Inject EventLogRepository eventLogRepository;
  @Inject Instance<CbrRetrievalService> cbrRetrievalServiceInstance;

  @SuppressWarnings("unchecked")
  public boolean tryDecompose(
      CaseInstance instance,
      CasePlanModel plan,
      PlanItem failedItem,
      FailureCategory.Knowledge category) {

    String bindingName = failedItem.getBindingName();

    Optional<String> parentOpt = plan.getParentOf(bindingName);
    if (parentOpt.isEmpty()) {
      LOG.debugf("Binding '%s' is not in a compound — cannot decompose deeper", bindingName);
      return false;
    }

    CaseDefinition definition;
    try {
      definition = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    } catch (Exception e) {
      LOG.warnf("Could not resolve CaseDefinition for deeper decomposition — skipping");
      return false;
    }

    if (definition.getDecompositionStrategy() == null) {
      LOG.debugf("No decomposition strategy configured — cannot decompose deeper");
      return false;
    }

    int depth = computeDepth(plan, bindingName);
    int maxDepth =
        definition.getMaxDecompositionDepth() != null
            ? definition.getMaxDecompositionDepth()
            : DEFAULT_MAX_DEPTH;
    if (depth >= maxDepth) {
      LOG.debugf(
          "Max decomposition depth reached (%d >= %d) for binding '%s' — faulting instead",
          depth, maxDepth, bindingName);
      return false;
    }

    DecompositionStrategy<com.fasterxml.jackson.databind.JsonNode> strategy;
    try {
      strategy =
          (DecompositionStrategy<com.fasterxml.jackson.databind.JsonNode>)
              strategyResolver.resolve(
                  DecompositionStrategy.class, definition.getDecompositionStrategy());
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Could not resolve decomposition strategy '%s'",
          definition.getDecompositionStrategy());
      return false;
    }

    var contextSnapshot =
        instance.getCaseContext() != null
            ? instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode()
            : OBJECT_MAPPER.createObjectNode();

    List<RetrievedExperience> experiences = List.of();
    if (cbrRetrievalServiceInstance.isResolvable() && definition.getCbrConfig() != null) {
      try {
        experiences = cbrRetrievalServiceInstance.get().retrieve(definition, instance);
      } catch (Exception e) {
        LOG.debugf("CBR retrieval for deeper decomposition failed — proceeding without");
      }
    }

    var decompositionContext =
        new GoalDecompositionContext(
            contextSnapshot,
            depth + 1,
            List.copyOf(definition.getCapabilities()),
            definition.getPlanningConstraints(),
            definition,
            experiences,
            category.reason(),
            category.missingContext());

    String goalDescription =
        failedItem.getDescription() != null ? failedItem.getDescription() : bindingName;
    var compoundTask =
        new TaskNode.CompoundTask<com.fasterxml.jackson.databind.JsonNode>(
            goalDescription, goalDescription, List.of());

    DagPlan<TaskNode.LeafTask<com.fasterxml.jackson.databind.JsonNode>> dagPlan;
    try {
      dagPlan = strategy.decompose(compoundTask, decompositionContext);
    } catch (Exception e) {
      LOG.warnf(e, "Deeper decomposition failed for binding '%s' — faulting instead", bindingName);
      return false;
    }

    if (dagPlan == null || dagPlan.nodes().size() < MIN_SUB_STEPS) {
      LOG.warnf(
          "Deeper decomposition returned fewer than %d steps for binding '%s' — faulting instead",
          MIN_SUB_STEPS, bindingName);
      return false;
    }

    var resolvedSteps = resolveBindings(dagPlan, definition, bindingName);
    if (resolvedSteps.isEmpty()) {
      LOG.warnf(
          "No resolved bindings for deeper decomposition of '%s' — faulting instead", bindingName);
      return false;
    }

    var compoundBuilder =
        PlanItemDefinition.Compound.builder(bindingName)
            .id(bindingName)
            .completion(CompletionSemantics.all())
            .dispatchMode(DispatchMode.CHOREOGRAPHED);

    for (var resolved : resolvedSteps) {
      compoundBuilder.child(
          new PlanItemDefinition.Primitive(
              resolved.primitiveId(),
              resolved.description(),
              ExecutorRef.of(resolved.capabilityName(), null),
              null));
      compoundBuilder.binding(resolved.bindingName());
    }

    var compound = compoundBuilder.build();
    plan.promoteToCompound(bindingName, compound);

    for (var resolved : resolvedSteps) {
      planItemStore.save(
          PlanItemSaveRequest.primitive(
              instance.getUuid(),
              resolved.stepId(),
              resolved.bindingName(),
              TaskStatus.PENDING,
              Instant.now(),
              TargetType.CAPABILITY,
              null,
              instance.tenancyId,
              resolved.description(),
              null,
              null),
          instance.tenancyId);

      var planItem =
          PlanItem.create(
              resolved.bindingName(), ExecutorRef.of(resolved.capabilityName(), null), 0);
      plan.addPlanItem(planItem);
    }

    writeEventLog(
        instance.getUuid(),
        instance.tenancyId,
        bindingName,
        definition.getDecompositionStrategy(),
        resolvedSteps.size(),
        depth + 1,
        maxDepth,
        category);

    LOG.infof(
        "Deeper decomposition: binding '%s' promoted to compound with %d sub-steps (depth %d/%d)",
        bindingName, resolvedSteps.size(), depth + 1, maxDepth);

    return true;
  }

  private int computeDepth(CasePlanModel plan, String bindingName) {
    int depth = 0;
    String current = bindingName;
    while (true) {
      Optional<String> parent = plan.getParentOf(current);
      if (parent.isEmpty()) break;
      current = parent.get();
      var def = plan.getDefinition(current);
      if (def instanceof PlanItemDefinition.Compound) depth++;
    }
    return depth;
  }

  private record ResolvedStep(
      String stepId,
      String primitiveId,
      String bindingName,
      String capabilityName,
      String description) {}

  private List<ResolvedStep> resolveBindings(
      DagPlan<TaskNode.LeafTask<com.fasterxml.jackson.databind.JsonNode>> dagPlan,
      CaseDefinition definition,
      String parentBindingName) {
    var resolved = new ArrayList<ResolvedStep>();
    int index = 0;
    for (var entry : dagPlan.nodes().entrySet()) {
      var node = entry.getValue();
      if (!(node.task() instanceof GoalStep step)) continue;

      var bindings = definition.findBindingsByCapability(step.capabilityName());
      if (bindings.isEmpty()) {
        LOG.warnf(
            "Deeper decomposition step references unknown capability '%s' — skipped",
            step.capabilityName());
        continue;
      }
      if (bindings.size() > 1) {
        LOG.warnf(
            "Capability '%s' has %d bindings — using first ('%s')",
            step.capabilityName(), bindings.size(), bindings.get(0).getName());
      }

      Binding binding = bindings.get(0);
      String primitiveId = parentBindingName + "-sub-" + index;
      resolved.add(
          new ResolvedStep(
              step.id(),
              primitiveId,
              binding.getName(),
              step.capabilityName(),
              step.description()));
      index++;
    }
    return resolved;
  }

  private void writeEventLog(
      UUID caseId,
      String tenancyId,
      String bindingName,
      String strategyId,
      int subStepCount,
      int currentDepth,
      int maxDepth,
      FailureCategory.Knowledge category) {
    var eventLog = new EventLog();
    eventLog.setCaseId(caseId);
    eventLog.setEventType(CaseHubEventType.PLAN_DEEPENED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());

    ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("bindingName", bindingName);
    meta.put("strategyId", strategyId);
    meta.put("subStepCount", subStepCount);
    meta.put("currentDepth", currentDepth);
    meta.put("maxDepth", maxDepth);
    meta.put("failureReason", category.reason());
    if (category.missingContext() != null) {
      meta.put("failureMissingContext", category.missingContext());
    }
    eventLog.setMetadata(meta);
    eventLogRepository.append(eventLog, tenancyId);
  }
}
