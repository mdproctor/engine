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
package io.casehub.engine.common.internal.model;

/**
 * Lifecycle states for a {@link io.casehub.blackboard.plan.PlanItem}.
 *
 * <p><b>Active states:</b>
 *
 * <ul>
 *   <li>PENDING — waiting to be scheduled
 *   <li>RUNNING — a Quartz job is actively executing this binding (CapabilityTarget only)
 *   <li>DELEGATED — control passed to an external actor (HumanTask, SubCase, Extension); engine
 *       waiting for completion signal. Non-terminal. Distinct from {@code WorkItemStatus.DELEGATED}
 *       (pre-acceptance hold within the task) and {@code CommitmentState.DELEGATED} (terminal
 *       obligation transfer)
 *   <li>SUSPENDED — external actor has paused work; slot remains occupied, work resumes without
 *       re-dispatch
 * </ul>
 *
 * <p><b>Terminal states:</b>
 *
 * <ul>
 *   <li>COMPLETED — work finished successfully
 *   <li>FAULTED — work did not complete successfully: system failure (worker exception, retry
 *       exhaustion), deadline breach (WorkItem EXPIRED), or gate resolution preventing the action
 *       from being applied (gate rejected or expired). Distinct from REJECTED (actor refused the
 *       work itself) and OBSOLETE (work became irrelevant)
 *   <li>REJECTED — external actor deliberately refused the work (human task refusal or M-of-N group
 *       threshold failure)
 *   <li>OBSOLETE — case context changed, making this work irrelevant. Not stopped by anyone — it
 *       stopped mattering. Distinct from CANCELLED (deliberate stop)
 *   <li>CANCELLED — deliberate stop by a human or system
 * </ul>
 *
 * <p>Stored as STRING in JPA — ordinal safety is not a concern.
 */
public enum PlanItemStatus {
  PENDING,
  RUNNING,
  DELEGATED,
  /** External actor has paused work. Slot remains occupied; work resumes without re-dispatch. */
  SUSPENDED,
  COMPLETED,
  FAULTED,
  REJECTED,
  OBSOLETE,
  CANCELLED;

  public boolean isTerminal() {
    return this == COMPLETED
        || this == FAULTED
        || this == REJECTED
        || this == OBSOLETE
        || this == CANCELLED;
  }

  public boolean isActive() {
    return this == PENDING || this == RUNNING || this == DELEGATED || this == SUSPENDED;
  }
}
