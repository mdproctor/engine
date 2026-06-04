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

  /** Published by QuartzWorkerExecutionJob when a workflow future completes exceptionally. */
  public static final String WORKFLOW_EXECUTION_FAILED = "casehub.workflow.execution.failed";
}
