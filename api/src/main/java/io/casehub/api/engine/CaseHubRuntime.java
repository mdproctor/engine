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
package io.casehub.api.engine;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface CaseHubRuntime {

  CompletionStage<UUID> startCase(CaseDefinition definition);

  CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData);

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      Object inputData,
      UUID parentCaseId,
      PropagationContext propagationContext);

  CompletionStage<UUID> startCase(
      CaseDefinition definition, Object inputData, Map<String, Object> semanticData);

  CompletionStage<UUID> startCase(
      CaseDefinition definition,
      Object inputData,
      Map<String, Object> semanticData,
      UUID parentCaseId,
      PropagationContext propagationContext);

  /**
   * Signals a case context update. The returned {@code CompletionStage} resolves when the signal
   * has been applied to the context, the event log written, and {@code CONTEXT_CHANGED} dispatched.
   * It does NOT guarantee that goal evaluation has completed — callers that need to await case
   * state transitions should use Awaitility on the case status.
   *
   * <p>Refs casehubio/engine#493.
   */
  CompletionStage<Void> signal(UUID caseId, String path, Object value);

  /**
   * Signals a case context update with caller-provided metadata that is stored in the EventLog
   * entry. Use for cross-case provenance linking — callers pass causedByCaseId and causedByEvent.
   */
  default CompletionStage<Void> signal(
      UUID caseId, String path, Object value, Map<String, Object> signalMetadata) {
    return signal(caseId, path, value);
  }

  /**
   * Signals a case context update with Qhorus trigger context for causal lineage.
   *
   * <p>When a Qhorus COMMAND triggers a context update (e.g. Claudony notifying the engine that a
   * worker has sent a response), the triggering COMMAND's {@code channelId} and {@code
   * correlationId} can be threaded through to {@code ProvisionContext} so the provisioner can
   * establish causal linkage in the ledger. Both fields are nullable — pass {@code null} when the
   * signal is not triggered by a Qhorus COMMAND.
   *
   * <p>Refs casehubio/engine#231, casehubio/engine#493, claudony#94.
   */
  default CompletionStage<Void> signal(
      UUID caseId,
      String path,
      Object value,
      String triggerChannelId,
      String triggerCorrelationId) {
    return signal(caseId, path, value);
  }

  /**
   * Atomically signals multiple context updates. The returned {@code CompletionStage} resolves when
   * all updates have been applied to the context, the event log written, and {@code
   * CONTEXT_CHANGED} dispatched.
   *
   * <p>Use this instead of multiple {@code signal()} calls to avoid intermediate state where only
   * some keys have been updated.
   *
   * <p>Refs casehubio/engine#483.
   */
  default CompletionStage<Void> signal(UUID caseId, Map<String, Object> updates) {
    throw new UnsupportedOperationException();
  }

  /**
   * Signals multiple context updates and waits for all triggered workers to complete. The returned
   * {@code CompletionStage} resolves with the final {@code CaseContext} when:
   *
   * <ul>
   *   <li>All updates have been applied
   *   <li>All capability bindings triggered by the context change have been dispatched
   *   <li>All dispatched workers have completed (success or failure)
   * </ul>
   *
   * <p>If no workers are dispatched (no bindings match), the future resolves immediately with the
   * updated context.
   *
   * <p>Times out with {@link SettlementTimeoutException} if settlement does not complete within the
   * specified duration.
   *
   * <p>Refs casehubio/engine#483.
   */
  default CompletionStage<CaseContext> signalAndAwait(
      UUID caseId, Map<String, Object> updates, Duration timeout) {
    throw new UnsupportedOperationException();
  }

  /**
   * Blocking variant of {@link #signalAndAwait(UUID, Map, Duration)}. Calls {@code
   * toCompletableFuture().join()} on the async version.
   *
   * <p>Refs casehubio/engine#483.
   */
  default CaseContext signalAndAwaitSync(
      UUID caseId, Map<String, Object> updates, Duration timeout) {
    return signalAndAwait(caseId, updates, timeout).toCompletableFuture().join();
  }

  default <T> CompletionStage<Void> signal(UUID caseId, SignalType<T> signalType, T payload) {
    throw new UnsupportedOperationException("Typed signals not supported by this runtime");
  }

  /**
   * Cancels a case. Valid from any non-terminal state (RUNNING, SUSPENDED, WAITING).
   *
   * @throws IllegalArgumentException if the case is not found
   * @throws IllegalStateException if the case is already in a terminal state
   */
  void cancelCase(UUID caseId);

  /**
   * Suspends a running case. No new workers will fire while the case is suspended.
   *
   * @throws IllegalArgumentException if the case is not found
   * @throws IllegalStateException if the case is not in RUNNING state
   */
  void suspendCase(UUID caseId);

  /**
   * Resumes a suspended case and re-evaluates context so eligible workers can fire.
   *
   * @throws IllegalArgumentException if the case is not found
   * @throws IllegalStateException if the case is not in SUSPENDED state
   */
  void resumeCase(UUID caseId);

  CompletionStage<Object> query(UUID caseId, String path);

  <T> CompletionStage<T> query(UUID caseId, String path, Class<T> clazz);

  /**
   * Retrieves all event log records for a case, ordered by sequence number ascending.
   *
   * @param caseId the case identifier
   * @return a CompletionStage containing the list of all event log records for the case
   * @throws IllegalArgumentException if the case is not found
   */
  CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId);

  /**
   * Retrieves event log records for a case filtered by event types, ordered by sequence number
   * ascending.
   *
   * @param caseId the case identifier
   * @param eventTypes set of event types to filter by; if null or empty, no filtering is applied
   * @return a CompletionStage containing the list of filtered event log records
   * @throws IllegalArgumentException if the case is not found
   */
  CompletionStage<List<CaseEventLogRecord>> eventLog(UUID caseId, Set<CaseHubEventType> eventTypes);

  /**
   * Retrieves event log records for a case filtered by event types and stream types, ordered by
   * sequence number ascending.
   *
   * @param caseId the case identifier
   * @param eventTypes set of event types to filter by; if null or empty, no filtering is applied
   * @param streamTypes set of stream types to filter by; if null or empty, no filtering is applied
   * @return a CompletionStage containing the list of filtered event log records
   * @throws IllegalArgumentException if the case is not found
   */
  CompletionStage<List<CaseEventLogRecord>> eventLog(
      UUID caseId, Set<CaseHubEventType> eventTypes, Set<EventStreamType> streamTypes);
}
