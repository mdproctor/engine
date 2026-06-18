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

import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerOutcome;
import io.casehub.api.spi.PlannedAction;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.Map;

/**
 * Published on {@link EventBusAddresses#WORKER_EXECUTION_FINISHED} when a worker function returns.
 *
 * <p>{@code outcome} carries the worker's semantic result: {@link WorkerOutcome.Success}, {@link
 * WorkerOutcome.Declined}, {@link WorkerOutcome.Failed}, or {@link WorkerOutcome.Expired}. The
 * completion handler branches on this before checking {@code plannedAction}.
 *
 * <p>{@code plannedAction} is non-null only when the worker returned a {@link
 * io.casehub.api.model.WorkerResult} with a declared action. The engine's completion handler forks
 * on this field: null means the normal path; non-null triggers {@link
 * io.casehub.api.spi.ReactiveActionRiskClassifier#classify(PlannedAction)} before applying output.
 *
 * <p>The gate approval path re-publishes this event with {@code plannedAction=null} and {@code
 * outcome=Success} so the normal completion machinery handles output application, PlanItem
 * completion, and stage autocomplete without re-classifying.
 */
public record WorkflowExecutionCompleted(
    CaseInstance caseInstance,
    Worker worker,
    String idempotency,
    Map<String, Object> output,
    PlannedAction plannedAction,
    String bindingName,
    WorkerOutcome outcome) {

  /** Convenience constructor for the gate-re-fire path — plannedAction is always null. */
  public static WorkflowExecutionCompleted approved(
      final CaseInstance caseInstance,
      final Worker worker,
      final String idempotency,
      final Map<String, Object> output,
      final String bindingName) {
    return new WorkflowExecutionCompleted(
        caseInstance, worker, idempotency, output, null, bindingName, WorkerOutcome.success());
  }
}
