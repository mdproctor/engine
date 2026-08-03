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
package io.casehub.engine.common.internal.event;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import java.util.Map;
import java.util.UUID;

/**
 * Published on {@link EventBusAddresses#WORKER_EXECUTION_FINISHED} when a worker function returns.
 *
 * <p>{@code outcome} carries the worker's semantic result: {@link WorkerOutcome.Success}, {@link
 * WorkerOutcome.Declined}, {@link WorkerOutcome.Failed}, or {@link WorkerOutcome.Expired}. The
 * completion handler extracts PlannedAction from {@code outcome} when it is {@code Success}.
 *
 * <p>The gate approval path re-publishes this event with {@code outcome=Success(null)} so the
 * normal completion machinery handles output application, PlanItem completion, and stage
 * autocomplete without re-classifying.
 *
 * @param signalId Settlement tracking ID for {@code signalAndAwait()}, or null. Threaded through
 *     from WorkerScheduleEvent via EventLog metadata so the completion handler can notify the
 *     tracker. Refs engine#483.
 * @param executorRef Nullable {@link io.casehub.api.model.ExecutorRef} containing executor
 *     identity. Threaded through from WorkerScheduleEvent for richer executor tracking. Refs
 *     engine#702.
 */
public record WorkflowExecutionCompleted(
    CaseInstance caseInstance,
    Worker worker,
    String idempotency,
    Map<String, Object> output,
    String bindingName,
    WorkerOutcome outcome,
    UUID signalId,
    String workerCredentialToken,
    io.casehub.api.model.ExecutorRef executorRef) {

  /** Convenience constructor for non-awaiting worker completions. */
  public WorkflowExecutionCompleted(
      CaseInstance caseInstance,
      Worker worker,
      String idempotency,
      Map<String, Object> output,
      String bindingName,
      WorkerOutcome outcome) {
    this(caseInstance, worker, idempotency, output, bindingName, outcome, null, null, null);
  }

  /** Convenience constructor with signalId but no credential token. */
  public WorkflowExecutionCompleted(
      CaseInstance caseInstance,
      Worker worker,
      String idempotency,
      Map<String, Object> output,
      String bindingName,
      WorkerOutcome outcome,
      UUID signalId) {
    this(caseInstance, worker, idempotency, output, bindingName, outcome, signalId, null, null);
  }

  /** Convenience constructor for the gate-re-fire path. */
  public static WorkflowExecutionCompleted approved(
      final CaseInstance caseInstance,
      final Worker worker,
      final String idempotency,
      final Map<String, Object> output,
      final String bindingName) {
    return new WorkflowExecutionCompleted(
        caseInstance,
        worker,
        idempotency,
        output,
        bindingName,
        WorkerOutcome.success(),
        null,
        null,
        null);
  }
}
