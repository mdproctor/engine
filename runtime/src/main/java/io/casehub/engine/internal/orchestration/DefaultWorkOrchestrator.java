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
package io.casehub.engine.internal.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.Assignment;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.WorkOrchestrator;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.routing.AgentCandidateFactory;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jboss.logging.Logger;

/**
 * Durable replacement for casehub-core's {@code TaskBroker}. Accepts a {@link WorkRequest}, selects
 * a worker via {@link AgentRoutingStrategy}, registers a {@link CompletableFuture} in {@link
 * PendingWorkRegistry}, and publishes a {@link WorkerScheduleEvent} to trigger execution.
 *
 * <p>{@link #submitAndWait} additionally suspends the case to {@code WAITING} and persists the
 * {@code waitingForWorkId} so the engine can resume after a JVM restart.
 */
@ApplicationScoped
public class DefaultWorkOrchestrator implements WorkOrchestrator {

  private static final Logger LOG = Logger.getLogger(DefaultWorkOrchestrator.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final AgentCandidateFactory agentCandidateFactory;
  private final AgentRoutingStrategy agentRoutingStrategy;
  private final WorkerExecutionManager executionManager;
  private final CapabilityHealth capabilityHealth;
  private final EventBus eventBus;
  private final PendingWorkRegistry pendingWorkRegistry;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;
  private final ReactiveEventLogRepository reactiveEventLogRepository;
  private final JQEvaluator jqEvaluator;
  private final CbrRetrievalService cbrRetrievalService;

  @Inject
  public DefaultWorkOrchestrator(
      final AgentCandidateFactory agentCandidateFactory,
      final AgentRoutingStrategy agentRoutingStrategy,
      final WorkerExecutionManager executionManager,
      final CapabilityHealth capabilityHealth,
      final EventBus eventBus,
      final PendingWorkRegistry pendingWorkRegistry,
      final CaseDefinitionRegistry caseDefinitionRegistry,
      final ReactiveCaseInstanceRepository reactiveCaseInstanceRepository,
      final ReactiveEventLogRepository reactiveEventLogRepository,
      final JQEvaluator jqEvaluator,
      final CbrRetrievalService cbrRetrievalService) {
    this.agentCandidateFactory = agentCandidateFactory;
    this.agentRoutingStrategy = agentRoutingStrategy;
    this.executionManager = executionManager;
    this.capabilityHealth = capabilityHealth;
    this.eventBus = eventBus;
    this.pendingWorkRegistry = pendingWorkRegistry;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.reactiveCaseInstanceRepository = reactiveCaseInstanceRepository;
    this.reactiveEventLogRepository = reactiveEventLogRepository;
    this.jqEvaluator = jqEvaluator;
    this.cbrRetrievalService = cbrRetrievalService;
  }

  /**
   * Submit work and return a future that completes when the worker finishes. Does not change the
   * case status — the case continues running while the work executes.
   */
  public CompletionStage<WorkResult> submit(
      final CaseInstance instance, final WorkRequest request) {
    return doSubmit(instance, request, false);
  }

  /**
   * Submit work and suspend the case to {@link CaseStatus#WAITING} until the work completes.
   * Persists {@code waitingForWorkId} on the case instance for JVM-restart durability.
   */
  public CompletionStage<WorkResult> submitAndWait(
      final CaseInstance instance, final WorkRequest request) {
    return doSubmit(instance, request, true);
  }

  private CompletionStage<WorkResult> doSubmit(
      final CaseInstance instance, final WorkRequest request, final boolean waitMode) {

    // 1. Find the capability from the case definition
    final CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    final Capability capability = findCapability(definition, request.capability());
    if (capability == null) {
      final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new IllegalArgumentException("No capability found for name: " + request.capability()));
      return failed;
    }

    // 2. Build AgentCandidate list — health-probed, Unavailable workers excluded
    final List<AgentCandidate> candidates =
        agentCandidateFactory.buildCandidates(
            instance,
            definition,
            definition.getWorkers(),
            capability,
            executionManager,
            capabilityHealth);

    // 3. Route via AgentRoutingStrategy (blocking await — not on Vert.x IO thread)
    final java.util.List<RetrievedExperience> experiences =
        cbrRetrievalService.retrieve(definition, instance);
    final AgentRoutingContext ctx =
        new AgentRoutingContext(
            instance.getUuid(),
            capability.name(),
            instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
            instance.tenancyId,
            experiences);
    final RoutingResult assignment = agentRoutingStrategy.select(ctx, candidates);

    switch (assignment) {
      case RoutingResult.Unresolvable u -> {
        final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(
            new IllegalStateException("No qualified agent for capability: " + capability.name()));
        return failed;
      }
      case RoutingResult.Escalated e -> {
        LOG.infof(
            "Agent routing escalated to oversight for capability '%s' caseId=%s",
            capability.name(), instance.getUuid());
        eventBus.publish(
            EventBusAddresses.AGENT_ROUTING_ESCALATION,
            new AgentRoutingEscalationEvent(
                instance.getUuid(),
                instance.tenancyId,
                e.capabilityName(),
                "(direct-orchestration)",
                e.escalationReason()));
        final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(
            new IllegalStateException(
                "Agent routing escalated to human oversight for capability: "
                    + capability.name()
                    + ". A QUERY has been posted to the oversight channel."));
        return failed;
      }
      case RoutingResult.Selected ignored -> {
        /* fall through — handled below */
      }
    }

    // 4. Resolve the selected Worker object — assignment is Selected; exhaustive switch guarantees
    // it
    final Assignment selected = ((RoutingResult.Selected) assignment).single();
    final Worker selectedWorker = findWorker(definition, selected.executorId());
    if (selectedWorker == null) {
      final CompletableFuture<WorkResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new IllegalStateException(
              "Selected worker not found in definition: " + selected.executorId()));
      return failed;
    }

    // 5. Build inputData and compute correlationKey (includes caseId to prevent cross-case
    // collision)
    final Map<String, Object> inputData =
        evalJqAsMap(
            instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
            capability.inputSchema());
    final String correlationKey =
        WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), selectedWorker.name(), capability.name(), inputData);

    // 6. Register future in PendingWorkRegistry
    final CompletableFuture<WorkResult> future = pendingWorkRegistry.register(correlationKey);

    // 7. Write WORK_SUBMITTED EventLog (fire-and-forget)
    final EventLog submittedLog =
        buildWorkSubmittedLog(instance, selectedWorker, capability, correlationKey);
    reactiveEventLogRepository
        .appendAndReturnId(submittedLog, instance.tenancyId)
        .subscribe()
        .with(
            id ->
                LOG.debugf(
                    "WORK_SUBMITTED persisted: caseId=%s worker=%s correlationKey=%s eventLogId=%d",
                    instance.getUuid(), selectedWorker.name(), correlationKey, id),
            t ->
                LOG.warnf(
                    t,
                    "Failed to persist WORK_SUBMITTED: caseId=%s worker=%s",
                    instance.getUuid(),
                    selectedWorker.name()));

    // 8. For waitMode: transition case to WAITING and persist
    if (waitMode) {
      instance.setState(CaseStatus.WAITING);
      instance.setWaitingForWorkId(correlationKey);

      final EventLog waitingLog = buildCaseStatusChangedLog(instance, CaseStatus.WAITING);
      reactiveCaseInstanceRepository
          .updateStateAndAppendEvent(instance, waitingLog, instance.tenancyId)
          .subscribe()
          .with(
              ignored ->
                  LOG.debugf(
                      "Case transitioned to WAITING: caseId=%s correlationKey=%s",
                      instance.getUuid(), correlationKey),
              t -> LOG.warnf(t, "Failed to persist WAITING state: caseId=%s", instance.getUuid()));
    }

    // 9. Publish WorkerScheduleEvent
    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(
            instance, selectedWorker, capability, null, null, null, null, experiences));

    LOG.infof(
        "Work submitted: caseId=%s worker=%s capability=%s correlationKey=%s waitMode=%b",
        instance.getUuid(), selectedWorker.name(), capability.name(), correlationKey, waitMode);

    return future;
  }

  private Capability findCapability(final CaseDefinition definition, final String capabilityName) {
    if (definition == null || definition.getCapabilities() == null) {
      return null;
    }
    return definition.getCapabilities().stream()
        .filter(c -> c.name().equals(capabilityName))
        .findFirst()
        .orElse(null);
  }

  private Worker findWorker(final CaseDefinition definition, final String workerName) {
    if (definition == null || definition.getWorkers() == null || workerName == null) {
      return null;
    }
    return definition.getWorkers().stream()
        .filter(w -> w.name().equals(workerName))
        .findFirst()
        .orElse(null);
  }

  private EventLog buildWorkSubmittedLog(
      final CaseInstance instance,
      final Worker worker,
      final Capability capability,
      final String correlationKey) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(worker.name());
    log.setEventType(CaseHubEventType.WORK_SUBMITTED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("capabilityName", capability.name())
            .put("correlationKey", correlationKey));
    return log;
  }

  private EventLog buildCaseStatusChangedLog(
      final CaseInstance instance, final CaseStatus newStatus) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setEventType(CaseHubEventType.CASE_STATUS_CHANGED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("newStatus", newStatus.name())
            .put("waitingForWorkId", instance.getWaitingForWorkId()));
    return log;
  }

  private Map<String, Object> evalJqAsMap(final JsonNode context, final String expression) {
    if (expression == null || expression.isBlank()) return Map.of();
    try {
      final ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return Map.of();
      return OBJECT_MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return Map.of();
    }
  }
}
