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
  PLAN_DEEPENED, // leaf task promoted to compound after Knowledge failure exhaustion
  PLAN_CONCEDED, // compound abandoned by meta-reasoner cost-benefit decision
  CONTINGENCY_ACTIVATED, // pre-computed contingency sub-plan activated on node failure
  GOAL_REVISED, // agent goal revised based on accumulated outcome signals
  GOAL_FORMED, // new agent goal created from reflection insights
  GOAL_PROPOSED, // new agent goal proposed but not registered (auto-approve=false)
  GOAL_REMOVED, // agent goal removed via GoalRemovalService
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

  RECOVERY_ESCALATED, // recovery coordinator escalated failure to Level 2 (local patch)
  RECOVERY_REPLAN, // recovery coordinator escalated failure to Level 3 (full replan)

  REACT_CYCLE, // one reason-act-observe cycle within a ReAct worker execution

  JUDGMENT_YIELDED, // caller-agnostic judgment request dispatched via JudgmentScheduler
  JUDGMENT_RESPONDED, // judgment response received from caller (decision + evidence)
  JUDGMENT_VERIFIED, // judgment response verified (placeholder — blocks/qhorus wiring)
  JUDGMENT_ESCALATED, // judgment escalated to different caller (placeholder — blocks/qhorus wiring)

  OBSERVER_REGISTERED, // agent registered an EnvironmentObserver via WorkerRuntime
  OBSERVATION_DETECTED, // observer produced observations during evaluation cycle

  PHEROMONE_DEPOSITED, // pheromone signal deposited or reinforced in SignalRegistry
  PHEROMONE_EXPIRED, // pheromone signal crossed below effective-zero threshold

  INTEREST_REGISTERED, // agent registered a declarative interest via InterestSpace
  INTEREST_DEREGISTERED, // agent deregistered a declarative interest via InterestSpace

  RULE_REGISTERED, // agent registered a local rule via RuleSpace
  RULE_FIRED, // local rule condition matched and actions executed

  BUDGET_EXHAUSTED, // cumulative activity budget exceeded — case faulted
  CONVERGENCE_DETECTED, // all activity rates below threshold for stability window
  OUTPUT_CONVERGENCE_DETECTED, // per-binding output structural similarity exceeds threshold

  STIGMERGY_CASE_INITIALIZED, // case started with stigmergy coordination mode
  STIGMERGY_AGENT_JOINED, // stigmergy agent dispatched — entering JOINING state
  STIGMERGY_AGENT_ACTIVATED, // stigmergy agent setup complete — entering ACTIVE state
  STIGMERGY_AGENT_DEPARTED, // stigmergy agent voluntarily departed — entering DEPARTED state
  SIGNAL_CONSENSUS_DETECTED, // signal reinforced by N agents — pheromone trail formation
  COORDINATION_STORM_DETECTED, // activity rates exceed storm thresholds — pathological coordination
  INTEREST_CONVERGENCE_DETECTED, // collective attention focusing on specific keys

  SWARM_ROLE_EMERGED, // new role cluster detected from behavioral fingerprinting
  SWARM_ROLE_DISSOLVED, // role cluster no longer meets minimum membership
  SWARM_ROLE_SHIFT, // agent moved from one role cluster to another
  SWARM_TEAM_FORMED, // team affinity cluster detected from shared coordination
  SWARM_TEAM_DISSOLVED, // team cluster no longer exists
  SWARM_TEAM_SHIFT, // agent moved from one team cluster to another
  SWARM_PROGRESS // swarm progress scores changed significantly
}
