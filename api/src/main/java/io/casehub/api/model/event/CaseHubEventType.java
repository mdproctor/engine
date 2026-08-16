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
package io.casehub.api.model.event;

public enum CaseHubEventType {
  CASE_STARTED,
  CASE_COMPLETED,
  CASE_FAULTED,
  CASE_CANCELLED,
  CASE_STATUS_CHANGED,

  TASK_CREATED,
  TASK_COMPLETED,
  TASK_FAILED,
  TASK_CANCELLED,

  WORKER_SCHEDULED,
  WORKER_EXECUTION_STARTED,
  WORKER_EXECUTION_COMPLETED,
  WORKER_EXECUTION_FAILED,
  WORKER_OUTCOME_DECLINED, // worker ran correctly but declined the work (semantic boundary)
  WORKER_OUTCOME_FAILED, // worker ran correctly but could not complete (semantic failure)
  WORKER_OUTCOME_EXPIRED, // worker timed out (engine-internal or commitment expiration)

  SCOPED_WORKER_OUTPUT, // scoped worker interim Success output applied to case context

  WORK_SUBMITTED, // orchestrated work submitted via WorkOrchestrator
  WORK_COMPLETED, // orchestrated work completed; case may resume from WAITING

  SIGNAL_RECEIVED,

  MILESTONE_REACHED,
  MILESTONE_ACTIVATED,
  MILESTONE_COMPLETED,
  MILESTONE_SLA_VIOLATED,

  GOAL_REACHED,
  GOAL_DECOMPOSED, // agent goal decomposed into ordered sub-step plan at case start
  PLAN_ADAPTED, // decomposed plan revised after worker completion
  GOAL_REVISED, // agent goal revised based on accumulated outcome signals
  GOAL_FORMED, // new agent goal created from reflection insights
  GOAL_PROPOSED, // new agent goal proposed but not registered (auto-approve=false)
  CONSTRAINTS_INFEASIBLE, // decomposition produced empty plan with active hard constraints

  SUBCASE_STARTED, // child case spawned by a SubCase binding
  SUBCASE_COMPLETED, // child case reached a terminal state; parent context updated

  WORKFLOW_STEP_DISPATCHED, // workflow step dispatched a casehub capability via WorkOrchestrator
  WORKFLOW_STEP_COMPLETED, // workflow step dispatch completed successfully
  WORKFLOW_STEP_FAILED, // workflow step dispatch failed (capability not found, routing error,
  // exhaustion)

  ACTION_GATE_PENDING, // worker declared a PlannedAction; gate pending human approval
  ACTION_GATE_APPROVED, // human approved the gate; deferred worker output applied
  ACTION_GATE_REJECTED, // human rejected the gate; worker treated as faulted
  ACTION_GATE_EXPIRED, // gate WorkItem expired before approval; worker treated as faulted
  ACTION_GATE_CANCELLED, // gate cancelled because the case reached a terminal state

  ORCHESTRATION_STARTED, // routing/orchestration phase began for a capability binding
  ORCHESTRATION_COMPLETED, // routing/orchestration phase completed successfully
  AGENT_ROUTED, // agent candidate selected via routing strategy
  AGENT_DISPATCHED, // agent dispatched for execution
  AGENT_COMPLETED, // agent execution completed
  AGENT_FAILED, // agent execution failed
  ORCHESTRATION_ESCALATED, // orchestration escalated due to exhaustion or circuit breaker

  PATTERN_CHECKPOINT, // pattern execution iteration checkpoint for crash recovery

  CONTEXT_SIGNAL_APPLIED, // SignalTarget payload written to case context
}
