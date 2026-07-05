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
package io.casehub.engine.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.casehub.engine.common.spi.WorkOrchestrator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jboss.logging.Logger;

/**
 * Single dispatch entrypoint for both YAML ({@link CasehubCallableTaskBuilder}) and Java FuncDSL
 * ({@link CasehubFlow}) paths.
 *
 * <p>Emits {@code WORKFLOW_STEP_DISPATCHED} before calling {@code WorkOrchestrator.submit()}. Emits
 * {@code WORKFLOW_STEP_COMPLETED} or {@code WORKFLOW_STEP_FAILED} via {@code whenComplete} — which
 * always fires regardless of outcome, ensuring every dispatched step has a terminal event. Logging
 * failures do not abort the dispatch result.
 */
@ApplicationScoped
public class CasehubDispatch {

  private static final Logger LOG = Logger.getLogger(CasehubDispatch.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final FlowExecutionRegistry registry;
  private final WorkOrchestrator orchestrator;
  private final ReactiveEventLogRepository reactiveEventLogRepository;
  private final CaseInstanceCache caseInstanceCache;
  private final CallableDispatchRegistry dispatchRegistry;

  @Inject
  public CasehubDispatch(
      final FlowExecutionRegistry registry,
      final WorkOrchestrator orchestrator,
      final ReactiveEventLogRepository reactiveEventLogRepository,
      final CaseInstanceCache caseInstanceCache,
      final CallableDispatchRegistry dispatchRegistry) {
    this.registry = registry;
    this.orchestrator = orchestrator;
    this.reactiveEventLogRepository = reactiveEventLogRepository;
    this.caseInstanceCache = caseInstanceCache;
    this.dispatchRegistry = dispatchRegistry;
  }

  @PostConstruct
  void register() {
    dispatchRegistry.register(
        "casehub:dispatch",
        (instanceId, args) -> {
          final String capability = (String) args.get("capability");
          if (capability == null) {
            throw new IllegalArgumentException(
                "casehub:dispatch step is missing required 'capability' argument");
          }
          return dispatch(instanceId, capability);
        });
  }

  public CompletableFuture<Map<String, Object>> dispatch(
      final String workflowInstanceId, final String capability) {
    final FlowExecution execution = registry.get(workflowInstanceId);
    appendStepLog(
        execution, capability, workflowInstanceId, CaseHubEventType.WORKFLOW_STEP_DISPATCHED, null);

    final CaseInstance instance = caseInstanceCache.get(execution.caseId());

    return orchestrator
        .submit(instance, WorkRequest.of(capability, Map.of()))
        .whenComplete(
            (result, ex) -> {
              // whenComplete always fires — success and failure both get a terminal event.
              // Logging failures here do not alter the completion propagated to quarkus-flow.
              if (ex != null) {
                appendStepLog(
                    execution,
                    capability,
                    workflowInstanceId,
                    CaseHubEventType.WORKFLOW_STEP_FAILED,
                    null);
              } else {
                appendStepLog(
                    execution,
                    capability,
                    workflowInstanceId,
                    CaseHubEventType.WORKFLOW_STEP_COMPLETED,
                    result);
              }
            })
        .thenApply(WorkResult::output)
        .toCompletableFuture();
  }

  private void appendStepLog(
      final FlowExecution execution,
      final String capability,
      final String workflowInstanceId,
      final CaseHubEventType eventType,
      final WorkResult result) {
    final CaseInstance instance = caseInstanceCache.get(execution.caseId());
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setWorkerId(execution.workerName());
    log.setEventType(eventType);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());

    // metadata — minimal; full payload kept out to avoid large event logs
    final ObjectNode meta = OBJECT_MAPPER.createObjectNode();
    meta.put("capability", capability);
    meta.put("workflowInstanceId", workflowInstanceId);
    meta.put("parentInputDataHash", execution.inputDataHash());
    if (result != null && result.output() != null && !result.output().isEmpty()) {
      meta.put("outputSummary", String.join(",", result.output().keySet()));
    }
    log.setMetadata(meta);

    reactiveEventLogRepository
        .appendAndReturnId(log, instance.tenancyId)
        .subscribe()
        .with(
            id ->
                LOG.debugf(
                    "Step log persisted: caseId=%s eventType=%s", instance.getUuid(), eventType),
            t ->
                LOG.warnf(
                    t,
                    "Failed to persist step log: caseId=%s eventType=%s",
                    instance.getUuid(),
                    eventType));
  }
}
