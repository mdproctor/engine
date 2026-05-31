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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.common.internal.event.MilestoneReachedEvent;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ExpressionEngineRegistry;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.routing.AgentCandidateFactory;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseContextChangedEventHandler {

  private static final Logger LOG = Logger.getLogger(CaseContextChangedEventHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Inject EventBus eventBus;

  @Inject JQEvaluator jqEvaluator;

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject LoopControl loopControl;

  @Inject AgentRoutingStrategy agentRoutingStrategy;

  @Inject WorkerExecutionManager executionManager;

  @Inject CapabilityHealth capabilityHealth;

  @Inject ReactiveWorkerContextProvider reactiveWorkerContextProvider;

  @Inject ReactiveWorkerProvisioner reactiveWorkerProvisioner;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.CONTEXT_CHANGED)
  public Uni<Void> onCaseStateContextChangedEventHandler(final CaseContextChangedEvent event) {
    final CaseInstance caseInstance = event.instance();
    final CaseStatus state = caseInstance.getState();

    // RUNNING and WAITING cases react to CONTEXT_CHANGED.
    // LoopControl handles binding dispatch per state (WAITING allowed when blackboard active).
    // SUSPENDED cases are paused by admin action; terminal cases (COMPLETED, FAULTED, CANCELLED)
    // are done — milestones, goals, and bindings must not evaluate for either.
    if (state != CaseStatus.RUNNING && state != CaseStatus.WAITING) {
      return Uni.createFrom().voidItem();
    }

    final JsonNode contextSnapshot = event.contextSnapshot();

    final CaseMetaModel caseMetaModel = caseInstance.getCaseMetaModel();
    final CaseDefinition caseDefinition =
        caseMetaModel != null ? caseDefinitionRegistry.getCaseDefinition(caseMetaModel) : null;

    if (caseDefinition == null) {
      return Uni.createFrom()
          .failure(
              new RuntimeException(
                  "Case definition not found for caseId: " + caseInstance.getUuid()));
    }

    LOG.infof("Handling CaseStateContextChangedEvent for caseId: %s", caseInstance.getUuid());

    return rules(caseInstance, contextSnapshot, caseDefinition)
        .chain(() -> milestones(caseInstance, contextSnapshot, caseDefinition))
        .chain(() -> goals(caseInstance, contextSnapshot, caseDefinition))
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
      final CaseInstance caseInstance,
      final JsonNode contextSnapshot,
      final CaseDefinition definition) {
    final List<Binding> bindings = definition.getBindings();
    if (bindings == null || bindings.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    final List<Worker> workers = definition.getWorkers();

    final List<Binding> eligible = new ArrayList<>();
    for (final Binding binding : bindings) {
      if (!(binding.getOn() instanceof ContextChangeTrigger cct)) {
        continue;
      }
      if (!expressionEngineRegistry.evaluate(cct.getFilter(), contextSnapshot)) {
        continue;
      }
      if (binding.getWhen() != null
          && !expressionEngineRegistry.evaluate(binding.getWhen(), contextSnapshot)) {
        continue;
      }
      eligible.add(binding);
    }

    final PlanExecutionContext planCtx =
        new PlanExecutionContext(
            caseInstance.getUuid(),
            definition,
            caseInstance.getCaseContext(),
            caseInstance.getState(),
            caseInstance.tenancyId);

    return loopControl
        .select(planCtx, eligible)
        .chain(
            selected -> {
              final List<Uni<Void>> unis = new ArrayList<>(selected.size());
              for (final Binding b : selected) {
                unis.add(publishByTarget(caseInstance, workers, b));
              }
              if (unis.isEmpty()) return Uni.createFrom().voidItem();
              return Uni.combine().all().unis(unis).discardItems();
            });
  }

  private Uni<Void> milestones(
      final CaseInstance caseInstance,
      final JsonNode contextSnapshot,
      final CaseDefinition definition) {
    final List<Milestone> milestones = definition.getMilestones();
    if (milestones == null || milestones.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    for (final Milestone milestone : milestones) {
      if (!expressionEngineRegistry.evaluate(
          milestone.getCompletionCriteria(), caseInstance.getCaseContext())) continue;

      LOG.infof("Milestone '%s' REACHED! Publishing MilestoneReachedEvent", milestone.getName());
      eventBus.publish(
          EventBusAddresses.MILESTONE_REACHED, new MilestoneReachedEvent(caseInstance, milestone));
    }

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> goals(
      final CaseInstance caseInstance,
      final JsonNode contextSnapshot,
      final CaseDefinition definition) {
    final List<Goal> goals = definition.getGoals();
    if (goals == null || goals.isEmpty()) {
      return Uni.createFrom().voidItem();
    }

    for (final Goal goal : goals) {
      if (!expressionEngineRegistry.evaluate(goal.getCondition(), caseInstance.getCaseContext()))
        continue;

      LOG.infof("Goal '%s' REACHED! Publishing GoalReachedEvent", goal.getName());
      eventBus.publish(EventBusAddresses.GOAL_REACHED, new GoalReachedEvent(caseInstance, goal));
    }

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> publishByTarget(
      final CaseInstance caseInstance, final List<Worker> workers, final Binding binding) {
    return switch (binding.target()) {
      case CapabilityTarget ct ->
          publishWorkerSchedule(caseInstance, workers, binding, ct.capability());
      case SubCaseTarget st ->
          publishSubCaseSchedule(caseInstance, st.subCase(), binding.getName());
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
      final CaseInstance caseInstance,
      final List<Worker> workers,
      final Binding binding,
      final Capability capability) {

    if (workers == null || workers.isEmpty()) {
      LOG.warnf("No workers defined; cannot schedule capability '%s'", capability.getName());
      return tryProvision(caseInstance, capability);
    }

    final List<AgentCandidate> candidates =
        AgentCandidateFactory.buildCandidates(
            caseInstance, workers, capability, executionManager, capabilityHealth);

    if (candidates.isEmpty()) {
      LOG.warnf(
          "No eligible workers for capability '%s' (binding '%s') — all unavailable or no match",
          capability.getName(), binding.getName());
      return tryProvision(caseInstance, capability);
    }

    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            caseInstance.getUuid(),
            capability.getName(),
            caseInstance.getCaseContext().asJsonNode());

    return agentRoutingStrategy
        .select(ctx, candidates)
        .chain(
            assignment ->
                switch (assignment) {
                  case AgentAssignment.Assigned a ->
                      scheduleWorker(caseInstance, workers, binding, capability, a.workerId());
                  case AgentAssignment.Unresolvable() -> {
                    LOG.warnf(
                        "AgentRoutingStrategy: no qualified agent for capability '%s' binding '%s'",
                        capability.getName(), binding.getName());
                    yield tryProvision(caseInstance, capability);
                  }
                  case AgentAssignment.EscalateToOversight e ->
                      handleEscalation(caseInstance, e, binding);
                });
  }

  private Uni<Void> scheduleWorker(
      final CaseInstance caseInstance,
      final List<Worker> workers,
      final Binding binding,
      final Capability capability,
      final String workerId) {

    final Worker selectedWorker =
        workers.stream().filter(w -> w.getName().equals(workerId)).findFirst().orElse(null);

    if (selectedWorker == null) {
      LOG.errorf(
          "Strategy selected worker '%s' but it was not found in the case definition", workerId);
      return Uni.createFrom().voidItem();
    }

    LOG.infof(
        "Agent selected: worker='%s' capability='%s' binding='%s'",
        workerId, capability.getName(), binding.getName());

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(caseInstance, selectedWorker, capability));

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> handleEscalation(
      final CaseInstance caseInstance,
      final AgentAssignment.EscalateToOversight escalation,
      final Binding binding) {

    LOG.infof(
        "Agent routing escalation: all candidates borderline for capability '%s' binding '%s'"
            + " caseId=%s — publishing escalation event",
        escalation.capabilityName(), binding.getName(), caseInstance.getUuid());

    eventBus.publish(
        EventBusAddresses.AGENT_ROUTING_ESCALATION,
        new AgentRoutingEscalationEvent(
            caseInstance.getUuid(), escalation.capabilityName(), binding.getName()));

    return Uni.createFrom().voidItem();
  }

  private Uni<Void> publishHumanTaskSchedule(
      final CaseInstance caseInstance, final Binding binding, final HumanTaskTarget target) {
    final Map<String, Object> inputData = evaluateInputMapping(caseInstance, target);
    final java.time.Instant caseBudgetDeadline =
        java.util.Optional.ofNullable(caseInstance.getPropagationContext())
            .flatMap(PropagationContext::getDeadline)
            .orElse(null);

    LOG.infof(
        "Publishing HumanTaskScheduleEvent: caseId=%s binding=%s template=%s deadline=%s",
        caseInstance.getUuid(), binding.getName(), target.templateRef(), caseBudgetDeadline);

    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(
            caseInstance.getUuid(),
            binding.getName(),
            target,
            inputData,
            caseBudgetDeadline,
            caseInstance.tenancyId));

    return Uni.createFrom().voidItem();
  }

  private Map<String, Object> evaluateInputMapping(
      final CaseInstance caseInstance, final HumanTaskTarget target) {
    if (target.inputMapping() == null) {
      return Map.of();
    }
    if (target.inputMapping() instanceof JQExpressionEvaluator jq) {
      try {
        final ValidationResult vr =
            jqEvaluator.eval(jq.expression(), caseInstance.getCaseContext().asJsonNode());
        if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
          LOG.warnf("inputMapping evaluation failed for HumanTaskTarget: %s", vr.error());
          return Map.of();
        }
        return MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
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

  private Uni<Void> tryProvision(final CaseInstance caseInstance, final Capability capability) {
    final String traceId = traceIdProvider.currentTraceId().orElse(null);
    return reactiveWorkerProvisioner
        .getCapabilities()
        .flatMap(
            caps -> {
              if (!caps.contains(capability.getName())) {
                return Uni.createFrom().voidItem();
              }
              final Map<String, Object> inputData =
                  evalJqAsMap(
                      caseInstance.getCaseContext().asJsonNode(), capability.getInputSchema());
              final WorkRequest workRequest = WorkRequest.of(capability.getName(), inputData);
              return reactiveWorkerContextProvider
                  .buildContext(null, caseInstance.getUuid(), workRequest)
                  .flatMap(
                      workerContext -> {
                        final ProvisionContext provisionContext =
                            new ProvisionContext(
                                caseInstance.getUuid(),
                                capability.getName(),
                                workerContext,
                                PropagationContext.createRoot(),
                                null, // triggerChannelId — see engine#231
                                null); // triggerCorrelationId — see engine#231
                        return reactiveWorkerProvisioner
                            .provision(caps, provisionContext)
                            .chain(
                                result ->
                                    Uni.createFrom()
                                        .completionStage(
                                            () ->
                                                lifecycleEvents.fireAsync(
                                                    new CaseLifecycleEvent(
                                                        caseInstance.getUuid(),
                                                        caseInstance.tenancyId,
                                                        "ProvisionWorker",
                                                        "WorkerStarted",
                                                        caseInstance.getState().name(),
                                                        null,
                                                        "System",
                                                        traceId)))
                                        .onFailure()
                                        .recoverWithItem(
                                            t -> {
                                              LOG.warnf(
                                                  t,
                                                  "CaseLifecycleEvent observer failed for caseId=%s event=WorkerStarted",
                                                  caseInstance.getUuid());
                                              return null;
                                            })
                                        .replaceWithVoid())
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
      final CaseInstance caseInstance,
      final io.casehub.api.model.SubCase subCase,
      final String bindingName) {
    final Map<String, Object> childContext =
        evalJqAsMap(caseInstance.getCaseContext().asJsonNode(), subCase.inputMapping());

    LOG.infof(
        "Publishing SubCaseScheduleEvent: parentCaseId=%s binding=%s subCase=%s/%s/%s waitForCompletion=%s",
        caseInstance.getUuid(),
        bindingName,
        subCase.namespace(),
        subCase.name(),
        subCase.version(),
        subCase.waitForCompletion());

    eventBus.publish(
        EventBusAddresses.SUBCASE_SCHEDULE,
        new SubCaseScheduleEvent(caseInstance, subCase, childContext, bindingName));

    return Uni.createFrom().voidItem();
  }

  private Map<String, Object> evalJqAsMap(final JsonNode context, final String expression) {
    if (expression == null || expression.isBlank()) return Map.of();
    try {
      final ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return Map.of();
      return MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return Map.of();
    }
  }
}
