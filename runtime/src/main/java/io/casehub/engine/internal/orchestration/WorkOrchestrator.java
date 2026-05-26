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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.eidos.api.CapabilityHealth.ProbeContext;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.utils.WorkerExecutionKeys;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.work.api.AssignmentDecision;
import io.casehub.work.api.AssignmentTrigger;
import io.casehub.work.api.SelectionContext;
import io.casehub.work.api.WorkerCandidate;
import io.casehub.work.api.WorkloadProvider;
import io.casehub.work.core.strategy.LeastLoadedStrategy;
import io.casehub.work.core.strategy.WorkBroker;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jboss.logging.Logger;

/**
 * Durable replacement for casehub-core's {@code TaskBroker}. Accepts a {@link WorkRequest}, selects
 * a worker via {@link WorkBroker}, registers a {@link CompletableFuture} in {@link
 * PendingWorkRegistry}, and publishes a {@link WorkerScheduleEvent} to trigger execution.
 *
 * <p>{@link #submitAndWait} additionally suspends the case to {@code WAITING} and persists the
 * {@code waitingForWorkId} so the engine can resume after a JVM restart.
 */
@ApplicationScoped
public class WorkOrchestrator {

  private static final Logger LOG = Logger.getLogger(WorkOrchestrator.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final WorkBroker workBroker;
  private final LeastLoadedStrategy selectionStrategy;
  private final WorkloadProvider workloadProvider;
  private final EventBus eventBus;
  private final PendingWorkRegistry pendingWorkRegistry;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final CaseInstanceRepository caseInstanceRepository;
  private final EventLogRepository eventLogRepository;
  private final JQEvaluator jqEvaluator;
  private final CapabilityHealth capabilityHealth;

  @Inject
  public WorkOrchestrator(
      WorkBroker workBroker,
      LeastLoadedStrategy selectionStrategy,
      WorkloadProvider workloadProvider,
      EventBus eventBus,
      PendingWorkRegistry pendingWorkRegistry,
      CaseDefinitionRegistry caseDefinitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      EventLogRepository eventLogRepository,
      JQEvaluator jqEvaluator,
      CapabilityHealth capabilityHealth) {
    this.workBroker = workBroker;
    this.selectionStrategy = selectionStrategy;
    this.workloadProvider = workloadProvider;
    this.eventBus = eventBus;
    this.pendingWorkRegistry = pendingWorkRegistry;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.caseInstanceRepository = caseInstanceRepository;
    this.eventLogRepository = eventLogRepository;
    this.jqEvaluator = jqEvaluator;
    this.capabilityHealth = capabilityHealth;
  }

  /**
   * Submit work and return a future that completes when the worker finishes. Does not change the
   * case status — the case continues running while the work executes.
   */
  public CompletionStage<WorkResult> submit(CaseInstance instance, WorkRequest request) {
    return doSubmit(instance, request, false);
  }

  /**
   * Submit work and suspend the case to {@link CaseStatus#WAITING} until the work completes.
   * Persists {@code waitingForWorkId} on the case instance for JVM-restart durability.
   */
  public CompletionStage<WorkResult> submitAndWait(CaseInstance instance, WorkRequest request) {
    return doSubmit(instance, request, true);
  }

  private CompletionStage<WorkResult> doSubmit(
      CaseInstance instance, WorkRequest request, boolean waitMode) {

    // 1. Find the capability from the case definition
    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    Capability capability = findCapability(definition, request.capability());
    if (capability == null) {
      CompletableFuture<WorkResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new IllegalArgumentException("No capability found for name: " + request.capability()));
      return failed;
    }

    // 2. Find capable workers and build WorkerCandidate list
    List<WorkerCandidate> candidates = buildCandidates(definition, capability.getName());

    // 3. Build SelectionContext and call WorkBroker
    SelectionContext context =
        new SelectionContext(null, null, capability.getName(), null, null, null, null, null);
    AssignmentDecision decision =
        workBroker.apply(context, AssignmentTrigger.CREATED, candidates, selectionStrategy);

    // 4. No-op means no worker available
    if (decision.isNoOp()) {
      CompletableFuture<WorkResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new IllegalStateException("No worker available for capability: " + capability.getName()));
      return failed;
    }

    // 5. Find the selected worker
    String selectedWorkerName = decision.assigneeId();
    Worker selectedWorker = findWorker(definition, selectedWorkerName);
    if (selectedWorker == null) {
      CompletableFuture<WorkResult> failed = new CompletableFuture<>();
      failed.completeExceptionally(
          new IllegalStateException(
              "Selected worker not found in definition: " + selectedWorkerName));
      return failed;
    }

    // 6. Build inputData and compute correlationKey (includes caseId to prevent cross-case
    // collision)
    Map<String, Object> inputData =
        evalJqAsMap(instance.getCaseContext().asJsonNode(), capability.getInputSchema());
    String correlationKey =
        WorkerExecutionKeys.inputDataHash(
            instance.getUuid(), selectedWorker.getName(), capability.getName(), inputData);

    // 7. Register future in PendingWorkRegistry
    CompletableFuture<WorkResult> future = pendingWorkRegistry.register(correlationKey);

    // 8. Write WORK_SUBMITTED EventLog (fire-and-forget)
    EventLog submittedLog =
        buildWorkSubmittedLog(instance, selectedWorker, capability, correlationKey);
    eventLogRepository
        .appendAndReturnId(submittedLog)
        .subscribe()
        .with(
            id ->
                LOG.debugf(
                    "WORK_SUBMITTED persisted: caseId=%s worker=%s correlationKey=%s eventLogId=%d",
                    instance.getUuid(), selectedWorker.getName(), correlationKey, id),
            t ->
                LOG.warnf(
                    t,
                    "Failed to persist WORK_SUBMITTED: caseId=%s worker=%s",
                    instance.getUuid(),
                    selectedWorker.getName()));

    // 9. For waitMode: transition case to WAITING and persist
    if (waitMode) {
      instance.setState(CaseStatus.WAITING);
      instance.setWaitingForWorkId(correlationKey);

      EventLog waitingLog = buildCaseStatusChangedLog(instance, CaseStatus.WAITING);
      caseInstanceRepository
          .updateStateAndAppendEvent(instance, waitingLog)
          .subscribe()
          .with(
              ignored ->
                  LOG.debugf(
                      "Case transitioned to WAITING: caseId=%s correlationKey=%s",
                      instance.getUuid(), correlationKey),
              t -> LOG.warnf(t, "Failed to persist WAITING state: caseId=%s", instance.getUuid()));
    }

    // 10. Publish WorkerScheduleEvent
    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(instance, selectedWorker, capability));

    LOG.infof(
        "Work submitted: caseId=%s worker=%s capability=%s correlationKey=%s waitMode=%b",
        instance.getUuid(),
        selectedWorker.getName(),
        capability.getName(),
        correlationKey,
        waitMode);

    // 11. Return the future
    return future;
  }

  private Capability findCapability(CaseDefinition definition, String capabilityName) {
    if (definition == null || definition.getCapabilities() == null) {
      return null;
    }
    return definition.getCapabilities().stream()
        .filter(c -> c.getName().equals(capabilityName))
        .findFirst()
        .orElse(null);
  }

  private List<WorkerCandidate> buildCandidates(CaseDefinition definition, String capabilityName) {
    if (definition == null || definition.getWorkers() == null) {
      return List.of();
    }
    List<WorkerCandidate> candidates = new ArrayList<>();
    for (Worker worker : definition.getWorkers()) {
      if (worker.getCapabilities() == null) {
        continue;
      }
      boolean hasCapability =
          worker.getCapabilities().stream().anyMatch(c -> c.getName().equals(capabilityName));
      if (!hasCapability) {
        continue;
      }

      if (worker.hasDescriptor()) {
        CapabilityStatus status =
            capabilityHealth.probe(worker.agentDescriptor(), capabilityName, ProbeContext.of(null));
        if (status instanceof CapabilityStatus.Unavailable u) {
          LOG.warnf(
              "Worker '%s' unavailable for capability '%s': %s — excluded",
              worker.getName(), capabilityName, u.reason());
          continue;
        }
        if (status instanceof CapabilityStatus.EpistemicallyWeak ew) {
          LOG.infof(
              "Worker '%s' epistemically weak for '%s' (domain=%s, confidence=%.2f) — kept, preference demotion not yet implemented",
              worker.getName(), capabilityName, ew.domain(), ew.confidence());
        }
        if (status instanceof CapabilityStatus.Degraded d) {
          LOG.debugf(
              "Worker '%s' degraded for '%s': %s — %s",
              worker.getName(), capabilityName, d.reason(), d.detail());
        }
      }

      int workload = workloadProvider.getActiveWorkCount(worker.getName());
      candidates.add(
          new WorkerCandidate(worker.getName(), java.util.Set.of(capabilityName), workload));
    }
    return candidates;
  }

  private Worker findWorker(CaseDefinition definition, String workerName) {
    if (definition == null || definition.getWorkers() == null || workerName == null) {
      return null;
    }
    return definition.getWorkers().stream()
        .filter(w -> w.getName().equals(workerName))
        .findFirst()
        .orElse(null);
  }

  private EventLog buildWorkSubmittedLog(
      CaseInstance instance, Worker worker, Capability capability, String correlationKey) {
    EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(worker.getName());
    log.setEventType(CaseHubEventType.WORK_SUBMITTED);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setMetadata(
        OBJECT_MAPPER
            .createObjectNode()
            .put("capabilityName", capability.getName())
            .put("correlationKey", correlationKey));
    return log;
  }

  private EventLog buildCaseStatusChangedLog(CaseInstance instance, CaseStatus newStatus) {
    EventLog log = new EventLog();
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

  private Map<String, Object> evalJqAsMap(JsonNode context, String expression) {
    if (expression == null || expression.isBlank()) return Map.of();
    try {
      ValidationResult vr = jqEvaluator.eval(expression, context);
      if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) return Map.of();
      return OBJECT_MAPPER.convertValue(vr.output().get(0), MAP_TYPE);
    } catch (Exception e) {
      LOG.warnf(e, "jq evaluation failed for expression '%s'", expression);
      return Map.of();
    }
  }
}
