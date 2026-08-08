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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.AdaptationConfig;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CompoundCompletedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.plan.adaptation.AdaptationCause;
import io.casehub.engine.plan.adaptation.AdaptationContext;
import io.casehub.engine.plan.adaptation.AdaptationSignal;
import io.casehub.engine.plan.adaptation.AdaptationTrigger;
import io.casehub.engine.plan.adaptation.CompletedStep;
import io.casehub.engine.plan.adaptation.PlanRevisionStrategy;
import io.casehub.engine.plan.adaptation.PlanStepDescriptor;
import io.casehub.engine.plan.adaptation.RevisedPlan;
import io.casehub.engine.plan.adaptation.RevisionContext;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.CompletionSemantics;
import io.casehub.engine.planning.plan.DispatchMode;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.platform.api.routing.StrategyResolver;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DefaultPlanAdaptationEvaluator implements PlanAdaptationEvaluator {

  private static final Logger LOG = Logger.getLogger(DefaultPlanAdaptationEvaluator.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final BlackboardRegistry registry;
  private final PlanItemStore planItemStore;
  private final EventLogRepository eventLogRepository;
  private final CaseInstanceRepository caseInstanceRepository;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final StrategyResolver strategyResolver;
  private final Instance<?> agentMemoryRetriever;
  private final Semaphore semaphore;
  private final long timeoutMs;
  private final ConcurrentHashMap<String, ReentrantLock> compoundLocks = new ConcurrentHashMap<>();

  @Inject
  public DefaultPlanAdaptationEvaluator(
      BlackboardRegistry registry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      CaseInstanceRepository caseInstanceRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      Instance<?> agentMemoryRetriever,
      @ConfigProperty(name = "casehub.engine.adaptation.max-concurrent", defaultValue = "3")
          int maxConcurrent,
      @ConfigProperty(name = "casehub.engine.decomposition.timeout-ms", defaultValue = "30000")
          long timeoutMs) {
    this.registry = registry;
    this.planItemStore = planItemStore;
    this.eventLogRepository = eventLogRepository;
    this.caseInstanceRepository = caseInstanceRepository;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.strategyResolver = strategyResolver;
    this.agentMemoryRetriever = agentMemoryRetriever;
    this.semaphore = new Semaphore(maxConcurrent);
    this.timeoutMs = timeoutMs;
  }

  @Override
  public void evaluateAdaptation(
      UUID caseId, String tenancyId, String completedBindingName, TaskStatus completedStatus) {
    Optional<CasePlanModel> planOpt = registry.get(caseId);
    if (planOpt.isEmpty()) return;

    CasePlanModel plan = planOpt.get();

    Optional<String> parentOpt = plan.getParentOf(completedBindingName);
    if (parentOpt.isEmpty()) {
      LOG.debugf(
          "Binding '%s' is not in a decomposed compound — skipping adaptation",
          completedBindingName);
      return;
    }

    String compoundId = parentOpt.get();

    CaseInstance instance = caseInstanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
      LOG.warnf("CaseInstance not found for caseId=%s — skipping adaptation", caseId);
      return;
    }

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    AdaptationConfig config = definition.getAdaptationConfig();
    if (config == null) {
      LOG.debugf("No adaptation config for case %s — skipping", caseId);
      return;
    }

    int generationAtEvent = plan.getAdaptationGeneration(compoundId);

    boolean acquired;
    try {
      acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warnf("Adaptation interrupted while acquiring semaphore for case %s", caseId);
      return;
    }
    if (!acquired) {
      LOG.warnf(
          "Adaptation semaphore timeout for case %s compound %s — skipping", caseId, compoundId);
      return;
    }

    try {
      ReentrantLock lock =
          compoundLocks.computeIfAbsent(caseId + ":" + compoundId, k -> new ReentrantLock());
      lock.lock();
      try {
        int currentGeneration = plan.getAdaptationGeneration(compoundId);
        if (currentGeneration != generationAtEvent) {
          LOG.debugf(
              "Adaptation generation changed (%d → %d) for compound %s — concurrent adaptation already ran",
              generationAtEvent, currentGeneration, compoundId);
          return;
        }

        performAdaptation(
            caseId,
            tenancyId,
            completedBindingName,
            completedStatus,
            compoundId,
            plan,
            instance,
            definition,
            config,
            currentGeneration);
      } finally {
        lock.unlock();
      }
    } finally {
      semaphore.release();
    }
  }

  private void performAdaptation(
      UUID caseId,
      String tenancyId,
      String completedBindingName,
      TaskStatus completedStatus,
      String compoundId,
      CasePlanModel plan,
      CaseInstance instance,
      CaseDefinition definition,
      AdaptationConfig config,
      int currentGeneration) {

    List<CompletedStep> completedSteps = buildCompletedSteps(plan, compoundId);
    List<PlanStepDescriptor> pendingSteps =
        buildStepDescriptors(plan, compoundId, TaskStatus.PENDING);
    List<PlanStepDescriptor> runningSteps =
        buildStepDescriptors(plan, compoundId, TaskStatus.RUNNING);

    var currentContext =
        instance.getCaseContext() != null
            ? instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode()
            : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

    var adaptationContext =
        new AdaptationContext(
            caseId,
            tenancyId,
            compoundId,
            compoundId,
            completedSteps,
            pendingSteps,
            runningSteps,
            currentContext,
            definition,
            completedStatus,
            completedBindingName,
            currentGeneration);

    AdaptationTrigger trigger = strategyResolver.resolve(AdaptationTrigger.class, config.trigger());
    AdaptationSignal signal = trigger.evaluate(adaptationContext);
    if (signal == AdaptationSignal.SKIP) {
      LOG.debugf(
          "Trigger '%s' returned SKIP for compound %s — no adaptation",
          config.trigger(), compoundId);
      return;
    }

    AdaptationCause cause = buildCause(completedBindingName, completedStatus);

    var revisionContext =
        new RevisionContext(
            adaptationContext,
            cause,
            definition.getCapabilities() != null ? definition.getCapabilities() : List.of(),
            List.of());

    PlanRevisionStrategy revision =
        strategyResolver.resolve(PlanRevisionStrategy.class, config.revision());

    RevisedPlan revisedPlan;
    try {
      revisedPlan = revision.revise(revisionContext).await().atMost(Duration.ofMillis(timeoutMs));
    } catch (Exception e) {
      LOG.warnf(
          e,
          "Plan revision failed for compound %s in case %s — existing plan continues",
          compoundId,
          caseId);
      return;
    }

    if (revisedPlan == null || revisedPlan.steps().isEmpty()) {
      LOG.warnf(
          "Plan revision returned empty steps for compound %s — existing plan continues",
          compoundId);
      return;
    }

    applyRevision(
        caseId,
        tenancyId,
        compoundId,
        plan,
        definition,
        config,
        currentGeneration,
        revisedPlan,
        pendingSteps,
        runningSteps,
        completedSteps);
  }

  private void applyRevision(
      UUID caseId,
      String tenancyId,
      String compoundId,
      CasePlanModel plan,
      CaseDefinition definition,
      AdaptationConfig config,
      int currentGeneration,
      RevisedPlan revisedPlan,
      List<PlanStepDescriptor> pendingSteps,
      List<PlanStepDescriptor> runningSteps,
      List<CompletedStep> completedSteps) {

    var obsoletedPlanItemIds = new ArrayList<String>();
    for (var pending : pendingSteps) {
      plan.getPlanItemByBindingName(pending.capabilityName())
          .ifPresent(
              item -> {
                planItemStore.updateStatus(item.getPlanItemId(), TaskStatus.OBSOLETE, tenancyId);
                obsoletedPlanItemIds.add(item.getPlanItemId());
              });
    }

    var compoundBuilder =
        PlanItemDefinition.Compound.builder(compoundId)
            .id(compoundId)
            .completion(CompletionSemantics.all())
            .dispatchMode(DispatchMode.CHOREOGRAPHED);

    for (var completed : completedSteps) {
      compoundBuilder.binding(completed.capabilityName());
    }
    for (var running : runningSteps) {
      compoundBuilder.binding(running.capabilityName());
    }

    var materializedIds = new ArrayList<String>();
    for (int i = 0; i < revisedPlan.steps().size(); i++) {
      var step = revisedPlan.steps().get(i);
      var primitiveId = compoundId + "-adapted-" + i;
      compoundBuilder.child(
          new PlanItemDefinition.Primitive(
              primitiveId, step.description(), ExecutorRef.of(step.capabilityName(), null), null));
      compoundBuilder.binding(step.capabilityName());
      materializedIds.add(step.id());
    }

    int newGeneration = currentGeneration + 1;
    var newCompound = compoundBuilder.build();
    plan.replaceCompound(compoundId, newCompound, newGeneration);

    for (int i = 0; i < revisedPlan.steps().size(); i++) {
      var step = revisedPlan.steps().get(i);
      planItemStore.save(
          PlanItemSaveRequest.primitive(
              caseId,
              step.id(),
              step.capabilityName(),
              TaskStatus.PENDING,
              Instant.now(),
              TargetType.CAPABILITY,
              null,
              tenancyId,
              step.description(),
              null,
              null),
          tenancyId);
    }

    writeEventLog(
        caseId,
        tenancyId,
        compoundId,
        config,
        revisedPlan,
        pendingSteps.size(),
        obsoletedPlanItemIds,
        materializedIds,
        newGeneration);
  }

  private AdaptationCause buildCause(String bindingName, TaskStatus status) {
    if (status == TaskStatus.COMPLETED) {
      return new AdaptationCause.StepCompleted(bindingName, bindingName, Map.of());
    } else {
      return new AdaptationCause.StepFailed(bindingName, status.name());
    }
  }

  private List<CompletedStep> buildCompletedSteps(CasePlanModel plan, String compoundId) {
    var steps = new ArrayList<CompletedStep>();
    PlanItemDefinition def = plan.getDefinition(compoundId);
    if (!(def instanceof PlanItemDefinition.Compound compound)) return steps;

    for (String bindingName : compound.scopedBindings().keySet()) {
      plan.findPlanItemByBindingName(bindingName)
          .ifPresent(
              item -> {
                if (item.getStatus() == TaskStatus.COMPLETED) {
                  steps.add(
                      new CompletedStep(
                          bindingName,
                          bindingName,
                          item.getDescription() != null ? item.getDescription() : bindingName,
                          Map.of(),
                          Instant.now()));
                }
              });
    }
    return steps;
  }

  private List<PlanStepDescriptor> buildStepDescriptors(
      CasePlanModel plan, String compoundId, TaskStatus filterStatus) {
    var steps = new ArrayList<PlanStepDescriptor>();
    PlanItemDefinition def = plan.getDefinition(compoundId);
    if (!(def instanceof PlanItemDefinition.Compound compound)) return steps;

    for (String bindingName : compound.scopedBindings().keySet()) {
      plan.findPlanItemByBindingName(bindingName)
          .ifPresent(
              item -> {
                if (item.getStatus() == filterStatus) {
                  steps.add(
                      new PlanStepDescriptor(
                          bindingName,
                          item.getDescription() != null ? item.getDescription() : bindingName,
                          bindingName));
                }
              });
    }
    return steps;
  }

  private void writeEventLog(
      UUID caseId,
      String tenancyId,
      String compoundId,
      AdaptationConfig config,
      RevisedPlan revisedPlan,
      int previousStepCount,
      List<String> obsoletedPlanItemIds,
      List<String> materializedIds,
      int newGeneration) {
    var eventLog = new EventLog();
    eventLog.setCaseId(caseId);
    eventLog.setEventType(CaseHubEventType.PLAN_ADAPTED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());

    var meta = OBJECT_MAPPER.createObjectNode();
    meta.put("goalName", compoundId);
    meta.put("compoundId", compoundId);
    meta.put("triggerStrategy", config.trigger());
    meta.put("revisionStrategy", config.revision());
    meta.put("previousStepCount", previousStepCount);
    meta.put("newStepCount", revisedPlan.steps().size());
    meta.set("obsoletedSteps", OBJECT_MAPPER.valueToTree(obsoletedPlanItemIds));
    meta.set("materializedSteps", OBJECT_MAPPER.valueToTree(materializedIds));
    meta.put("adaptationGeneration", newGeneration);
    if (revisedPlan.rationale() != null) {
      meta.put("rationale", revisedPlan.rationale());
    }
    eventLog.setMetadata(meta);

    eventLogRepository.append(eventLog, tenancyId);
  }

  @ConsumeEvent(value = EventBusAddresses.COMPOUND_COMPLETED, blocking = true)
  public void onCompoundCompleted(CompoundCompletedEvent event) {
    String key = event.caseId() + ":" + event.compoundId();
    compoundLocks.remove(key);
  }

  public void cleanLocksForCase(UUID caseId) {
    compoundLocks.keySet().removeIf(k -> k.startsWith(caseId + ":"));
  }
}
