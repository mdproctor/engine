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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.ExtensionTarget;
import io.casehub.api.model.Goal;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.ProvisionContext;
import io.casehub.api.model.SubCaseTarget;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.event.ExecutionOrigin;
import io.casehub.api.spi.ProvisioningException;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.CandidateSetContext;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.HumanTaskRoutingStrategy;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.common.internal.event.OutcomeDisposition;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.engine.CaseEvaluationSerializer;
import io.casehub.engine.internal.routing.AgentCandidateFactory;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  @Inject StrategyResolver strategyResolver;

  @Inject AgentCandidateFactory agentCandidateFactory;

  @Inject WorkerExecutionManager executionManager;

  @Inject CapabilityHealth capabilityHealth;

  @Inject WorkerContextProvider workerContextProvider;

  @Inject WorkerProvisioner workerProvisioner;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject LedgerTraceIdProvider traceIdProvider;

  @Inject CbrRetrievalService cbrRetrievalService;

  @Inject io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver;

  @Inject io.casehub.engine.internal.engine.SignalSettlementTracker settlementTracker;

  @Inject @io.quarkus.virtual.threads.VirtualThreads
  java.util.concurrent.ExecutorService virtualThreads;

  @Inject CaseEvaluationSerializer evaluationSerializer;

  @RunOnVirtualThread
  @ConsumeEvent(value = EventBusAddresses.CONTEXT_CHANGED)
  public void onCaseStateContextChangedEventHandler(final CaseContextChangedEvent event) {
    final CaseInstance caseInstance = event.instance();
    final CaseStatus state = caseInstance.getState();

    if (state != CaseStatus.RUNNING && state != CaseStatus.WAITING) {
      return;
    }

    final CaseContext contextSnapshot = event.contextSnapshot();
    final String changedLayer = event.changedLayer();

    if (changedLayer != null) {
      eventBus.publish(EventBusAddresses.layerChanged(changedLayer), event);
    }

    if (ContextLayer.EPISODIC.equals(changedLayer)) {
      return;
    }

    evaluationSerializer.submit(caseInstance.getUuid(), () -> evaluateAndDispatch(event));
  }

  private void evaluateAndDispatch(final CaseContextChangedEvent event) {
    final CaseInstance caseInstance = event.instance();
    final CaseContext contextSnapshot = event.contextSnapshot();
    final String changedLayer = event.changedLayer();

    final CaseMetaModel caseMetaModel = caseInstance.getCaseMetaModel();
    final CaseDefinition caseDefinition =
        caseMetaModel != null ? caseDefinitionRegistry.getCaseDefinition(caseMetaModel) : null;

    if (caseDefinition == null) {
      throw new RuntimeException("Case definition not found for caseId: " + caseInstance.getUuid());
    }

    LOG.infof("Handling CaseStateContextChangedEvent for caseId: %s", caseInstance.getUuid());

    final String triggerChannelId = event.triggerChannelId();
    final String triggerCorrelationId = event.triggerCorrelationId();
    final java.util.UUID signalId = event.signalId();
    final String traceId = traceIdProvider.currentTraceId().orElse(null);

    try {
      rules(
          caseInstance,
          contextSnapshot,
          caseDefinition,
          changedLayer,
          triggerChannelId,
          triggerCorrelationId,
          signalId,
          traceId);
      goals(caseInstance, contextSnapshot, caseDefinition);

      if (signalId != null) {
        settlementTracker.markFullyDispatched(signalId);
      }
      LOG.debugf("Rules+goals processed for caseId: %s", caseInstance.getUuid());
    } catch (Exception t) {
      LOG.errorf(t, "Failed handling context changed for caseId: %s", caseInstance.getUuid());
    }
  }

  private void rules(
      final CaseInstance caseInstance,
      final CaseContext contextSnapshot,
      final CaseDefinition definition,
      final String changedLayer,
      final String triggerChannelId,
      final String triggerCorrelationId,
      final java.util.UUID signalId,
      final String traceId) {
    final List<Binding> bindings = definition.getBindings();
    if (bindings == null || bindings.isEmpty()) {
      return;
    }

    final List<Worker> workers = definition.getWorkers();
    final List<Binding> eligible = new ArrayList<>();
    for (final Binding binding : bindings) {
      if (!(binding.getOn() instanceof ContextChangeTrigger cct)) {
        continue;
      }
      final String listenLayer = cct.getListenLayer();
      if (listenLayer != null && !listenLayer.equals(changedLayer)) {
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

    List<RetrievedExperience> experiences = cbrRetrievalService.retrieve(definition, caseInstance);

    final PlanExecutionContext planCtx =
        new PlanExecutionContext(
            caseInstance.getUuid(),
            definition,
            caseInstance.getCaseContext(),
            caseInstance.getState(),
            caseInstance.tenancyId,
            experiences,
            ExecutionOrigin.BINDING_DISPATCH,
            (io.casehub.api.model.RetryState) null);

    List<Binding> selected = loopControl.select(planCtx, eligible);
    if (selected.isEmpty()) {
      return;
    }

    if (selected.size() == 1) {
      publishByTarget(
          caseInstance,
          definition,
          workers,
          selected.get(0),
          triggerChannelId,
          triggerCorrelationId,
          signalId,
          experiences,
          traceId);
    } else {
      @SuppressWarnings("unchecked")
      java.util.concurrent.CompletableFuture<Void>[] futures =
          selected.stream()
              .map(
                  b ->
                      java.util.concurrent.CompletableFuture.runAsync(
                          () ->
                              publishByTarget(
                                  caseInstance,
                                  definition,
                                  workers,
                                  b,
                                  triggerChannelId,
                                  triggerCorrelationId,
                                  signalId,
                                  experiences,
                                  traceId),
                          virtualThreads))
              .toArray(java.util.concurrent.CompletableFuture[]::new);
      java.util.concurrent.CompletableFuture.allOf(futures).join();
    }
  }

  private void goals(
      final CaseInstance caseInstance,
      final CaseContext contextSnapshot,
      final CaseDefinition definition) {
    final List<Goal> goals = definition.getGoals();
    if (goals == null || goals.isEmpty()) {
      return;
    }

    for (final Goal goal : goals) {
      if (!expressionEngineRegistry.evaluate(goal.getCondition(), contextSnapshot)) {
        continue;
      }
      LOG.infof("Goal '%s' REACHED! Publishing GoalReachedEvent", goal.getName());
      eventBus.publish(EventBusAddresses.GOAL_REACHED, new GoalReachedEvent(caseInstance, goal));
    }
  }

  private void publishByTarget(
      final CaseInstance caseInstance,
      final CaseDefinition caseDefinition,
      final List<Worker> workers,
      final Binding binding,
      final String triggerChannelId,
      final String triggerCorrelationId,
      final java.util.UUID signalId,
      final List<RetrievedExperience> experiences,
      final String traceId) {
    if (binding.getContextWrite() != null && !binding.getContextWrite().isEmpty()) {
      binding
          .getContextWrite()
          .forEach((key, value) -> caseInstance.getCaseContext().set(key, value));
    }
    switch (binding.target()) {
      case CapabilityTarget ct ->
          publishWorkerSchedule(
              caseInstance,
              caseDefinition,
              workers,
              binding,
              ct.capability(),
              triggerChannelId,
              triggerCorrelationId,
              signalId,
              experiences,
              traceId);
      case SubCaseTarget st ->
          publishSubCaseSchedule(caseInstance, st.subCase(), binding.getName());
      case HumanTaskTarget ht ->
          publishHumanTaskSchedule(caseInstance, caseDefinition, binding, ht, experiences);
      case ExtensionTarget et ->
          LOG.warnf(
              "No handler for ExtensionTarget %s on binding '%s'",
              et.getClass().getName(), binding.getName());
    }
  }

  private void publishWorkerSchedule(
      final CaseInstance caseInstance,
      final CaseDefinition caseDefinition,
      final List<Worker> workers,
      final Binding binding,
      final Capability capability,
      final String triggerChannelId,
      final String triggerCorrelationId,
      final java.util.UUID signalId,
      final List<RetrievedExperience> experiences,
      final String traceId) {

    if (workers == null || workers.isEmpty()) {
      LOG.warnf("No workers defined; cannot schedule capability '%s'", capability.name());
      tryProvision(
              caseInstance,
              capability,
              binding.getName(),
              triggerChannelId,
              triggerCorrelationId,
              binding.getInputProjectionOverride(), traceId);
      return;
    }

    List<AgentCandidate> candidates =
        agentCandidateFactory.buildCandidates(
            caseInstance, caseDefinition, workers, capability, executionManager, capabilityHealth);

    if (candidates.isEmpty()) {
      LOG.warnf(
          "No eligible workers for capability '%s' (binding '%s') — all unavailable or no match",
          capability.name(), binding.getName());
      tryProvision(
              caseInstance,
              capability,
              binding.getName(),
              triggerChannelId,
              triggerCorrelationId,
              binding.getInputProjectionOverride(), traceId);
      return;
    }

    final JsonNode outcomeNode =
        caseInstance
            .getCaseContext()
            .layer(ContextLayer.WORKING)
            .asJsonNode()
            .path("_diagnostics")
            .path(binding.getName());
    if (outcomeNode.has("excludedAgents")) {
      final java.util.Set<String> excluded =
          java.util.stream.StreamSupport.stream(
                  outcomeNode.get("excludedAgents").spliterator(), false)
              .map(JsonNode::asText)
              .collect(java.util.stream.Collectors.toSet());
      candidates = candidates.stream().filter(c -> !excluded.contains(c.workerId())).toList();
      if (!excluded.isEmpty()) {
        LOG.debugf(
            "Filtered %d excluded agents for binding '%s': %s",
            excluded.size(), binding.getName(), excluded);
      }
      if (candidates.isEmpty()) {
        LOG.warnf(
            "All candidates excluded for capability '%s' binding '%s' — auto-exhausting",
            capability.name(), binding.getName());
        handleAllCandidatesExhausted(caseInstance, binding.getName(), capability.name());
        return;
      }
    }

    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            caseInstance.getUuid(),
            capability.name(),
            caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
            caseInstance.tenancyId,
            experiences);

    final AgentRoutingStrategy routingStrategy =
        strategyResolver.resolve(AgentRoutingStrategy.class, caseDefinition.getAgentRouting());
    final RoutingResult assignment = routingStrategy.select(ctx, candidates);

    switch (assignment) {
      case RoutingResult.Selected s -> {
        final var a = s.single();
        LOG.infof(
            "Agent selected: worker='%s' capability='%s' binding='%s' rationale='%s'",
            a.executorId(), capability.name(), binding.getName(), a.reason());
        scheduleWorker(
            caseInstance, workers, binding, capability, a.executorId(), signalId, experiences);
      }
      case RoutingResult.Unresolvable u -> {
        LOG.warnf(
            "AgentRoutingStrategy: no qualified agent for capability '%s' binding '%s'",
            capability.name(), binding.getName());
        tryProvision(
                caseInstance,
                capability,
                binding.getName(),
                triggerChannelId,
                triggerCorrelationId,
                binding.getInputProjectionOverride(), traceId);
      }
      case RoutingResult.Escalated e -> handleEscalation(caseInstance, e, binding);
    }
  }

  @SuppressWarnings("unchecked")
  private void handleAllCandidatesExhausted(
      final CaseInstance caseInstance, final String bindingName, final String capabilityName) {
    final Map<String, Object> existingOutcomes =
        (Map<String, Object>) caseInstance.getCaseContext().get("_diagnostics");
    if (existingOutcomes != null) {
      final ObjectNode outcomesRoot = MAPPER.valueToTree(existingOutcomes).deepCopy();
      if (outcomesRoot.has(bindingName)) {
        ((ObjectNode) outcomesRoot.get(bindingName)).put("status", "REROUTES_EXHAUSTED");
        final Map<String, Object> outcomesMap = MAPPER.convertValue(outcomesRoot, MAP_TYPE);
        caseInstance.getCaseContext().set("_diagnostics", outcomesMap);
      }
    }
    eventBus.publish(
        EventBusAddresses.WORKER_OUTCOME_RESOLVED,
        new WorkerOutcomeResolvedEvent(
            caseInstance, null, bindingName, capabilityName, OutcomeDisposition.EXHAUSTED));
  }

  private void scheduleWorker(
      final CaseInstance caseInstance,
      final List<Worker> workers,
      final Binding binding,
      final Capability capability,
      final String workerId,
      final java.util.UUID signalId,
      final List<RetrievedExperience> experiences) {

    final Worker selectedWorker =
        workers.stream().filter(w -> w.name().equals(workerId)).findFirst().orElse(null);
    if (selectedWorker == null) {
      LOG.errorf(
          "Strategy selected worker '%s' but it was not found in the case definition", workerId);
      return;
    }

    LOG.debugf(
        "Scheduling worker='%s' capability='%s' binding='%s'",
        workerId, capability.name(), binding.getName());

    if (signalId != null) {
      settlementTracker.incrementExpected(signalId);
    }

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(
            caseInstance,
            selectedWorker,
            capability,
            binding.getName(),
            binding.getInputProjectionOverride(),
            signalId,
            ExecutionOrigin.BINDING_DISPATCH,
            experiences));
  }

  private void handleEscalation(
      final CaseInstance caseInstance,
      final RoutingResult.Escalated escalation,
      final Binding binding) {
    LOG.infof(
        "Agent routing escalation: all candidates borderline for capability '%s' binding '%s'"
            + " caseId=%s — publishing escalation event",
        escalation.capabilityName(), binding.getName(), caseInstance.getUuid());

    eventBus.publish(
        EventBusAddresses.AGENT_ROUTING_ESCALATION,
        new AgentRoutingEscalationEvent(
            caseInstance.getUuid(),
            caseInstance.tenancyId,
            escalation.capabilityName(),
            binding.getName(),
            escalation.escalationReason()));
  }

  private void publishHumanTaskSchedule(
      final CaseInstance caseInstance,
      final CaseDefinition caseDefinition,
      final Binding binding,
      final HumanTaskTarget target,
      final List<RetrievedExperience> experiences) {
    final Map<String, Object> inputData = evaluateInputMapping(caseInstance, target);

    if (target.payloadType() != null && target.inputMapping() != null && !inputData.isEmpty()) {
      try {
        var bridge = bridgeResolver.resolveByType(target.payloadType());
        bridge.initialise(caseInstance.getCaseContext(), MAPPER.valueToTree(inputData));
      } catch (Exception e) {
        LOG.warnf(
            e,
            "Bridge validation failed for HumanTask binding '%s' caseId=%s — "
                + "inputMapping output does not match payloadType %s. PlanItem stays PENDING.",
            binding.getName(),
            caseInstance.getUuid(),
            target.payloadType().getName());
        return;
      }
    }

    final JsonNode caseContext =
        caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode();

    try {
      final Set<String> resolvedGroups =
          resolveCandidateSet(target.candidateGroups(), caseContext, "candidateGroups");
      final Set<String> resolvedUsers =
          resolveCandidateSet(target.candidateUsers(), caseContext, "candidateUsers");

      final HumanTaskRoutingStrategy humanTaskStrategy =
          strategyResolver.resolve(
              HumanTaskRoutingStrategy.class, caseDefinition.getHumanTaskRouting());
      final var routingCtx =
          new HumanTaskRoutingContext(
              caseInstance.getUuid(),
              binding.getName(),
              caseInstance.tenancyId,
              caseContext,
              experiences);
      final var htCandidates = new HumanTaskCandidates(resolvedGroups, resolvedUsers);

      final HumanTaskRoutingResult routingResult =
          humanTaskStrategy.select(routingCtx, htCandidates);

      final Set<String> finalGroups;
      final Set<String> finalUsers;
      final Map<String, Double> scores;
      switch (routingResult) {
        case HumanTaskRoutingResult.Enriched e -> {
          finalGroups = e.candidateGroups();
          finalUsers = e.candidateUsers();
          scores = e.candidateScores();
        }
        case HumanTaskRoutingResult.Unchanged u -> {
          finalGroups = resolvedGroups;
          finalUsers = resolvedUsers;
          scores = Map.of();
        }
        case HumanTaskRoutingResult.Escalated e -> {
          LOG.warnf(
              "HumanTask routing escalated for caseId=%s binding=%s: %s",
              caseInstance.getUuid(), binding.getName(), e.reason());
          finalGroups = resolvedGroups;
          finalUsers = resolvedUsers;
          scores = Map.of();
        }
      }

      final java.time.Instant caseBudgetDeadline =
          java.util.Optional.ofNullable(caseInstance.getPropagationContext())
              .flatMap(PropagationContext::getDeadline)
              .orElse(null);
      final java.time.Instant expiresAtDeadline = resolveExpiresAtDeadline(caseInstance, target);
      final String resolvedTitle =
          resolveStringExpression(caseInstance, target.titleExpression(), "titleExpression");
      final String resolvedScope =
          resolveStringExpression(caseInstance, target.scopeExpression(), "scopeExpression");
      final java.time.Duration resolvedExpiresIn = resolveExpiresInExpression(caseInstance, target);

      LOG.infof(
          "Publishing HumanTaskScheduleEvent: caseId=%s binding=%s template=%s deadline=%s expiresAtDeadline=%s",
          caseInstance.getUuid(),
          binding.getName(),
          target.templateRef(),
          caseBudgetDeadline,
          expiresAtDeadline);

      final String payloadTypeName =
          target.payloadType() != null ? target.payloadType().getName() : null;
      final String resolutionTypeName =
          target.resolutionType() != null ? target.resolutionType().getName() : null;

      eventBus.publish(
          EventBusAddresses.HUMAN_TASK_SCHEDULE,
          new HumanTaskScheduleEvent(
              caseInstance.getUuid(),
              caseInstance.tenancyId,
              binding.getName(),
              target,
              inputData,
              payloadTypeName,
              resolutionTypeName,
              finalGroups,
              finalUsers,
              caseBudgetDeadline,
              expiresAtDeadline,
              resolvedTitle,
              resolvedScope,
              resolvedExpiresIn,
              experiences,
              scores));
    } catch (Exception t) {
      LOG.warnf(
          t,
          "HumanTask candidate set resolution failed for caseId=%s binding=%s — PlanItem stays PENDING",
          caseInstance.getUuid(),
          binding.getName());
    }
  }

  private Set<String> resolveCandidateSet(
      final CandidateSetSpec spec, final JsonNode caseContext, final String fieldName) {
    if (spec == null) {
      return null;
    }
    return switch (spec) {
      case CandidateSetSpec.Inline inline ->
          inline.strategy().evaluate(new CandidateSetContext(caseContext));
      case CandidateSetSpec.Named named -> {
        final CandidateSetStrategy resolved =
            strategyResolver.resolve(CandidateSetStrategy.class, named.strategyId());
        yield resolved.evaluate(new CandidateSetContext(caseContext, named.config()));
      }
    };
  }

  private java.time.Instant resolveExpiresAtDeadline(
      final CaseInstance caseInstance, final HumanTaskTarget target) {
    if (target.expiresAtExpression() == null) {
      return null;
    }
    return expressionEngineRegistry
        .extractString(target.expiresAtExpression(), caseInstance.getCaseContext())
        .map(
            s -> {
              try {
                return java.time.Instant.parse(s);
              } catch (Exception e) {
                LOG.warnf(
                    "expiresAtExpression result '%s' is not a valid ISO-8601 instant — ignoring",
                    s);
                return null;
              }
            })
        .orElse(null);
  }

  private String resolveStringExpression(
      final CaseInstance caseInstance,
      final io.casehub.platform.api.expression.ExpressionEvaluator evaluator,
      final String fieldName) {
    if (evaluator == null) {
      return null;
    }
    return expressionEngineRegistry
        .extractString(evaluator, caseInstance.getCaseContext())
        .orElse(null);
  }

  private java.time.Duration resolveExpiresInExpression(
      final CaseInstance caseInstance, final HumanTaskTarget target) {
    if (target.expiresInExpression() == null) {
      return null;
    }
    return expressionEngineRegistry
        .extractString(target.expiresInExpression(), caseInstance.getCaseContext())
        .map(
            s -> {
              try {
                return java.time.Duration.parse(s);
              } catch (Exception e) {
                LOG.warnf(
                    "expiresInExpression result '%s' is not a valid ISO-8601 duration — ignoring",
                    s);
                return null;
              }
            })
        .orElse(null);
  }

  private Map<String, Object> evaluateInputMapping(
      final CaseInstance caseInstance, final HumanTaskTarget target) {
    if (target.inputMapping() == null) {
      return Map.of();
    }
    if (target.inputMapping() instanceof JQExpressionEvaluator jq) {
      try {
        final ValidationResult vr =
            jqEvaluator.eval(
                jq.expression(),
                caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode());
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

  private void tryProvision(
          final CaseInstance caseInstance,
          final Capability capability,
          final String bindingName,
          final String triggerChannelId,
          final String triggerCorrelationId,
          final String inputProjectionOverride, String traceId) {
    final String effectiveProjection =
        inputProjectionOverride != null ? inputProjectionOverride : capability.inputSchema();
    final Map<String, Object> inputData =
        evalJqAsMap(
            caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
            effectiveProjection);
    final WorkRequest workRequest = WorkRequest.of(capability.name(), inputData);

    try {
      final var workerContext =
          workerContextProvider.buildContext(
              null, caseInstance.getUuid(), workRequest, caseInstance.getPropagationContext());

      final ProvisionContext provisionContext =
          new ProvisionContext(
              caseInstance.getUuid(),
              caseInstance.tenancyId,
              capability.name(),
              workerContext,
              caseInstance.getPropagationContext(),
              triggerChannelId,
              triggerCorrelationId);

      final var caps = workerProvisioner.getCapabilities();
      workerProvisioner.provision(caps, provisionContext);

      try {
        lifecycleEvents
            .fireAsync(
                CaseLifecycleEvent.of(
                    caseInstance, "ProvisionWorker", "WorkerStarted", null, "System", traceId))
            .toCompletableFuture()
            .join();
      } catch (Exception t) {
        LOG.warnf(
            t,
            "CaseLifecycleEvent observer failed for caseId=%s event=WorkerStarted",
            caseInstance.getUuid());
      }
    } catch (ProvisioningException e) {
      LOG.warnf(
          e,
          "WorkerProvisioner failed for capability '%s' binding '%s' on case %s — auto-exhausting",
          capability.name(),
          bindingName,
          caseInstance.getUuid());
      handleAllCandidatesExhausted(caseInstance, bindingName, capability.name());
    }
  }

  private void publishSubCaseSchedule(
      final CaseInstance caseInstance,
      final io.casehub.api.model.SubCase subCase,
      final String bindingName) {
    Object childContext;
    io.casehub.api.model.SubCaseMapping mapping = subCase.inputMapping();
    switch (mapping) {
      case io.casehub.api.model.SubCaseMapping.Expression expr -> {
        Map<String, Object> result =
            evalJqAsMap(
                caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
                expr.expression());
        if (result.isEmpty()) {
          LOG.errorf(
              "SubCase inputMapping produced empty result for binding '%s' on case %s — not dispatching",
              bindingName, caseInstance.getUuid());
          return;
        }
        childContext = result;
      }
      case io.casehub.api.model.SubCaseMapping.Lambda lambda -> {
        try {
          childContext = lambda.fn().apply(caseInstance.getCaseContext());
        } catch (Exception e) {
          LOG.errorf(
              e,
              "SubCase inputMapping lambda failed for binding '%s' on case %s — not dispatching",
              bindingName,
              caseInstance.getUuid());
          return;
        }
      }
    }

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
        new SubCaseScheduleEvent(caseInstance, subCase, childContext, null, bindingName));
  }

  private Map<String, Object> evalJqAsMap(final JsonNode context, final String expression) {
    if (expression == null || expression.isBlank()) {
      return Map.of();
    }
    try {
      final ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
        return Map.of();
      }
      return MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return Map.of();
    }
  }
}
