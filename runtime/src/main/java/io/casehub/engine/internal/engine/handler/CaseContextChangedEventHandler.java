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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.Goal;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.Worker;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.ReactiveWorkerContextProvider;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.engine.internal.event.CaseContextChangedEvent;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.GoalReachedEvent;
import io.casehub.engine.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.internal.event.MilestoneReachedEvent;
import io.casehub.engine.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.internal.event.WorkerScheduleEvent;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.spi.CaseDefinitionRegistry;
import io.casehub.engine.spi.ExpressionEngineRegistry;
import io.casehub.work.api.AssignmentDecision;
import io.casehub.work.api.AssignmentTrigger;
import io.casehub.work.api.SelectionContext;
import io.casehub.work.api.WorkerCandidate;
import io.casehub.work.api.WorkloadProvider;
import io.casehub.work.core.strategy.LeastLoadedStrategy;
import io.casehub.work.core.strategy.WorkBroker;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseContextChangedEventHandler {

  private static final Logger LOG = Logger.getLogger(CaseContextChangedEventHandler.class);

  @Inject EventBus eventBus;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject LoopControl loopControl;

  @Inject WorkBroker workBroker;

  @Inject LeastLoadedStrategy selectionStrategy;

  @Inject WorkloadProvider workloadProvider;

  @Inject ReactiveWorkerContextProvider reactiveWorkerContextProvider;

  @Inject ReactiveWorkerProvisioner reactiveWorkerProvisioner;

  @ConsumeEvent(value = EventBusAddresses.CONTEXT_CHANGED)
  public Uni<Void> onCaseStateContextChangedEventHandler(CaseContextChangedEvent event) {
    CaseInstance caseInstance = event.instance();
    JsonNode contextSnapshot = event.contextSnapshot();
    if (!caseInstance.getState().equals(CaseStatus.RUNNING)) {
      return Uni.createFrom().voidItem();
    }

    CaseMetaModel caseMetaModel = caseInstance.getCaseMetaModel();
    CaseDefinition caseefinition =
        caseMetaModel != null ? caseDefinitionRegistry.getCaseDefinition(caseMetaModel) : null;

    if (caseefinition == null) {
      return Uni.createFrom()
          .failure(
              new RuntimeException(
                  "Case definition not found for caseId: " + caseInstance.getUuid()));
    }

    LOG.infof("Handling CaseStateContextChangedEvent for caseId: %s", caseInstance.getUuid());

    return rules(caseInstance, contextSnapshot, caseefinition)
        .chain(() -> milestones(caseInstance, contextSnapshot, caseefinition))
        .chain(() -> goals(caseInstance, contextSnapshot, caseefinition))
        .invoke(
            () ->
                LOG.debugf(
                    "Rules+milestones+goals processed for caseId: %s", caseInstance.getUuid()))
        .onFailure()
        .invoke(
            t ->
                LOG.errorf(
                    t, "Failed handling context changed for caseId: %s", caseInstance.getUuid()));
  }

  private Uni<Void> rules(
      CaseInstance caseInstance, JsonNode contextSnapshot, CaseDefinition definition) {
    List<Binding> bindings = definition.getBindings();
    if (bindings == null || bindings.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    List<Worker> workers = definition.getWorkers();

    // Evaluate trigger conditions to find eligible rules
    List<Binding> eligible = new ArrayList<>();
    for (Binding binding : bindings) {
      if (!(binding.getOn() instanceof ContextChangeTrigger cct)) {
        continue;
      }

      if (!expressionEngineRegistry.evaluate(cct.getFilter(), contextSnapshot)) {
        continue;
      }

      eligible.add(binding);
    }

    PlanExecutionContext planCtx =
        new PlanExecutionContext(caseInstance.getUuid(), definition, caseInstance.getCaseContext());

    return loopControl
        .select(planCtx, eligible)
        .chain(
            selected -> {
              List<Uni<Void>> unis = new ArrayList<>(selected.size());
              for (Binding b : selected) {
                unis.add(publishByTarget(caseInstance, workers, b));
              }
              if (unis.isEmpty()) return Uni.createFrom().voidItem();
              return Uni.combine().all().unis(unis).discardItems();
            });
  }

  private Uni<Void> milestones(
      CaseInstance caseInstance, JsonNode contextSnapshot, CaseDefinition definition) {
    List<Milestone> milestones = definition.getMilestones();
    if (milestones == null || milestones.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    for (Milestone milestone : milestones) {
      if (!expressionEngineRegistry.evaluate(
          milestone.getCompletionCriteria(), caseInstance.getCaseContext())) continue;

      LOG.infof("Milestone '%s' REACHED! Publishing MilestoneReachedEvent", milestone.getName());

      eventBus.publish(
          EventBusAddresses.MILESTONE_REACHED, new MilestoneReachedEvent(caseInstance, milestone));
    }

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> goals(
      CaseInstance caseInstance, JsonNode contextSnapshot, CaseDefinition definition) {
    List<Goal> goals = definition.getGoals();
    if (goals == null || goals.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    for (Goal goal : goals) {
      if (!expressionEngineRegistry.evaluate(goal.getCondition(), caseInstance.getCaseContext()))
        continue;

      LOG.infof("Goal '%s' REACHED! Publishing GoalReachedEvent", goal.getName());

      eventBus.publish(EventBusAddresses.GOAL_REACHED, new GoalReachedEvent(caseInstance, goal));
    }

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> publishByTarget(
      CaseInstance caseInstance, List<Worker> workers, Binding binding) {
    return switch (binding.target()) {
      case CapabilityTarget ct ->
          publishWorkerSchedule(caseInstance, workers, binding, ct.capability());
      case SubCaseTarget st -> publishSubCaseSchedule(caseInstance, st.subCase());
      case HumanTaskTarget ht -> publishHumanTaskSchedule(caseInstance, binding, ht);
      case ExtensionTarget et -> {
        LOG.warnf(
            "No handler for ExtensionTarget %s on binding '%s'",
            et.getClass().getName(), binding.getName());
        yield Uni.createFrom().voidItem();
      }
    };
  }

  private Uni<Void> publishWorkerSchedule(
      CaseInstance caseInstance, List<Worker> workers, Binding binding, Capability capability) {

    if (workers == null || workers.isEmpty()) {
      LOG.warnf("No workers defined; cannot schedule capability '%s'", capability.getName());
      return tryProvision(caseInstance, capability);
    }

    List<WorkerCandidate> candidates =
        workers.stream()
            .filter(w -> w.getCapabilities() != null)
            .filter(
                w ->
                    w.getCapabilities().stream()
                        .anyMatch(c -> c.getName().equals(capability.getName())))
            .map(
                w ->
                    WorkerCandidate.of(w.getName())
                        .withActiveWorkItemCount(workloadProvider.getActiveWorkCount(w.getName())))
            .toList();

    if (candidates.isEmpty()) {
      LOG.warnf(
          "No workers match capability '%s' for binding '%s'",
          capability.getName(), binding.getName());
      return tryProvision(caseInstance, capability);
    }

    // requiredCapabilities is null: WorkerCandidate.of() creates candidates with an empty
    // capabilities set (no capability tracking on candidates), so passing the capability name
    // would cause WorkBroker to filter out all pre-screened candidates. Capability matching
    // is already done above when building the candidates list.
    SelectionContext ctx =
        new SelectionContext(
            capability.getName(),
            null,
            null, // see above: capability filtering already done on candidates
            null,
            null,
            null,
            null,
            null);

    AssignmentDecision decision =
        workBroker.apply(ctx, AssignmentTrigger.CREATED, candidates, selectionStrategy);

    if (decision.isNoOp()) {
      LOG.warnf(
          "WorkBroker returned no assignment for capability '%s' binding '%s'",
          capability.getName(), binding.getName());
      return Uni.createFrom().voidItem();
    }

    String selectedId = decision.assigneeId();
    if (selectedId == null) {
      LOG.errorf(
          "WorkBroker returned null assigneeId for non-noOp decision on capability '%s' — skipping",
          capability.getName());
      return Uni.createFrom().voidItem();
    }

    Worker selectedWorker =
        workers.stream().filter(w -> w.getName().equals(selectedId)).findFirst().orElse(null);

    if (selectedWorker == null) {
      LOG.errorf(
          "WorkBroker selected worker '%s' but it was not found in the case definition",
          selectedId);
      return Uni.createFrom().voidItem();
    }

    LOG.infof(
        "WorkBroker selected '%s' for capability '%s' (binding '%s')",
        selectedId, capability.getName(), binding.getName());

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(caseInstance, selectedWorker, capability));

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> publishHumanTaskSchedule(
      CaseInstance caseInstance, Binding binding, HumanTaskTarget target) {
    Map<String, Object> inputData = evaluateInputMapping(caseInstance, target);
    java.time.Instant caseBudgetDeadline =
        java.util.Optional.ofNullable(caseInstance.getPropagationContext())
            .flatMap(PropagationContext::getDeadline)
            .orElse(null);

    LOG.infof(
        "Publishing HumanTaskScheduleEvent: caseId=%s binding=%s template=%s deadline=%s",
        caseInstance.getUuid(), binding.getName(), target.templateRef(), caseBudgetDeadline);

    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(
            caseInstance.getUuid(), binding.getName(), target, inputData, caseBudgetDeadline));

    return Uni.createFrom().voidItem();
  }

  private Map<String, Object> evaluateInputMapping(
      CaseInstance caseInstance, HumanTaskTarget target) {
    if (target.inputMapping() == null) {
      return Map.of();
    }
    if (target.inputMapping() instanceof JQExpressionEvaluator jq) {
      try {
        return caseInstance.getCaseContext().evalObjectTemplate(jq.expression());
      } catch (Exception e) {
        LOG.warnf(e, "inputMapping evaluation failed for HumanTaskTarget — using empty input");
        return Map.of();
      }
    }
    LOG.warnf(
        "Unsupported inputMapping evaluator type '%s' — using empty input",
        target.inputMapping().getClass().getName());
    return Map.of();
  }

  private Uni<Void> tryProvision(CaseInstance caseInstance, Capability capability) {
    return reactiveWorkerProvisioner
        .getCapabilities()
        .flatMap(
            caps -> {
              if (!caps.contains(capability.getName())) {
                return Uni.createFrom().voidItem();
              }
              Map<String, Object> inputData =
                  caseInstance.getCaseContext().evalObjectTemplate(capability.getInputSchema());
              WorkRequest workRequest = WorkRequest.of(capability.getName(), inputData);
              return reactiveWorkerContextProvider
                  .buildContext(null, caseInstance.getUuid(), workRequest)
                  .flatMap(
                      workerContext -> {
                        ProvisionContext provisionContext =
                            new ProvisionContext(
                                caseInstance.getUuid(),
                                capability.getName(),
                                workerContext,
                                PropagationContext.createRoot(),
                                null, // triggerChannelId — see engine#231 to thread Qhorus trigger
                                // context through
                                null); // triggerCorrelationId — see engine#231
                        return reactiveWorkerProvisioner
                            .provision(caps, provisionContext)
                            .replaceWithVoid();
                      });
            })
        .onFailure(ProvisioningException.class)
        .invoke(
            e ->
                LOG.warnf(
                    e,
                    "WorkerProvisioner failed for capability '%s' on case %s — binding remains eligible",
                    capability.getName(),
                    caseInstance.getUuid()));
  }

  private Uni<Void> publishSubCaseSchedule(
      CaseInstance caseInstance, io.casehub.api.model.SubCase subCase) {
    Map<String, Object> childContext =
        caseInstance.getCaseContext().evalObjectTemplate(subCase.inputMapping());

    LOG.infof(
        "Publishing SubCaseScheduleEvent: parentCaseId=%s subCase=%s/%s/%s waitForCompletion=%s",
        caseInstance.getUuid(),
        subCase.namespace(),
        subCase.name(),
        subCase.version(),
        subCase.waitForCompletion());

    eventBus.publish(
        EventBusAddresses.SUBCASE_SCHEDULE,
        new SubCaseScheduleEvent(caseInstance, subCase, childContext));

    return Uni.createFrom().voidItem();
  }
}
