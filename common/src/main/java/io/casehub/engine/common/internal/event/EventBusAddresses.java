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

/** EventBus addresses for CaseHub events. */
public final class EventBusAddresses {

  private EventBusAddresses() {
    // Utility class
  }

  public static final String CASE_STARTED = "casehub.case.started";
  public static final String CASE_COMPLETED = "casehub.case.completed";
  public static final String CASE_FAULTED = "casehub.case.faulted";
  public static final String CASE_STATUS_CHANGED = "casehub.case.status.changed";

  public static final String CONTEXT_CHANGED = "casehub.context.changed";

  public static final String SIGNAL_RECEIVED = "casehub.signal.received";
  public static final String BULK_SIGNAL_RECEIVED = "casehub.signal.bulk.received";
  public static final String TYPED_SIGNAL_RECEIVED = "casehub.engine.typed-signal-received";

  public static final String WORKER_SCHEDULE = "casehub.worker.schedule";
  public static final String WORKER_EXECUTION_STARTED = "casehub.worker.start";
  public static final String WORKER_EXECUTION_FINISHED = "casehub.worker.finished";
  public static final String WORKER_RETRIES_EXHAUSTED = "casehub.worker.retries.exhausted";

  public static final String MILESTONE_REACHED = "casehub.milestone.reached";
  public static final String MILESTONE_ACTIVATED = "casehub.milestone.activated";
  public static final String MILESTONE_COMPLETED = "casehub.milestone.completed";
  public static final String MILESTONE_SLA_VIOLATED = "casehub.milestone.sla.violated";

  public static final String GOAL_REACHED = "casehub.goal.reached";

  public static final String SUBCASE_SCHEDULE = "casehub.subcase.schedule";

  public static final String HUMAN_TASK_SCHEDULE = "casehub.humantask.schedule";

  public static final String AGENT_ROUTING_ESCALATION = "casehub.agent.routing.escalation";

  // --- Action gate lifecycle ---

  /** Published by WorkflowExecutionCompletedHandler when GateRequired fires. */
  public static final String ACTION_GATE_SCHEDULE = "casehub.action.gate.schedule";

  /**
   * Published by ActionGateCompletionApplier (work-adapter) when a gate WorkItem is COMPLETED.
   * Handled by ActionGateApprovedHandler in the engine runtime.
   */
  public static final String ACTION_GATE_APPROVED = "casehub.action.gate.approved";

  /**
   * Published by ActionGateCompletionApplier (work-adapter) when a gate WorkItem is
   * REJECTED/CANCELLED. Handled by ActionGateRejectedHandler in the engine runtime and by
   * ActionGateRejectedPlanItemHandler in the blackboard module.
   */
  public static final String ACTION_GATE_REJECTED = "casehub.action.gate.rejected";

  /**
   * Published by ActionGateCompletionApplier (work-adapter) when a gate WorkItem expires. Handled
   * by ActionGateExpiredHandler in the engine runtime and by ActionGateExpiredPlanItemHandler in
   * the blackboard module.
   */
  public static final String ACTION_GATE_EXPIRED = "casehub.action.gate.expired";

  /**
   * Published by CaseStatusChangedHandler when a case transitions to a terminal state while a gate
   * is pending. Handled by ActionGateCancelledHandler in work-adapter to cancel the WorkItem.
   */
  public static final String ACTION_GATE_CANCELLED = "casehub.action.gate.cancelled";

  // --- Worker outcome lifecycle (semantic DECLINED/FAILED) ---

  /**
   * Published by WorkflowExecutionCompletedHandler when a worker returns a non-success outcome
   * (DECLINED or FAILED). Consumed by WorkerOutcomeResolvedHandler in the blackboard module for
   * PlanItem lifecycle management.
   */
  public static final String WORKER_OUTCOME_RESOLVED = "casehub.worker.outcome.resolved";

  /** Returns an event bus address scoped to a specific layer name. */
  public static String layerChanged(String layerName) {
    return "casehub.context.changed." + layerName;
  }

  /**
   * Published by ActionGateRejectedHandler and ActionGateExpiredHandler when a gate is resolved
   * negatively (rejected or expired). Consumed by the blackboard module to mark the associated
   * PlanItem FAULTED — enabling CompoundCompletionEvaluator to proceed. Distinct from
   * WORKER_RETRIES_EXHAUSTED which also faults the CaseInstance; gate faults leave the case
   * RUNNING.
   */
  public static final String ACTION_GATE_WORKER_FAULTED = "casehub.action.gate.worker.faulted";
}
