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
package io.casehub.blackboard.plan;

import io.casehub.api.model.BindingTarget;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Activation record for a {@link io.casehub.api.model.Binding} on the {@link CasePlanModel}
 * scheduling agenda.
 *
 * <p>Priority is assigned by {@link io.casehub.blackboard.control.PlanningStrategy}. Status is
 * updated by {@link io.casehub.blackboard.handler.PlanItemCompletionHandler} on worker completion.
 * Implements {@link Comparable} for priority-ordered sorting (higher priority first; FIFO for equal
 * priority). See casehubio/engine#76.
 */
public class PlanItem implements Comparable<PlanItem> {

  private final String planItemId;
  private final String bindingName;
  private final String workerName;
  private final int priority;
  private volatile PlanItemStatus status;
  private final Instant createdAt;
  private String parentStageId; // null means no parent stage
  private final BindingTarget target;

  private PlanItem(String bindingName, String workerName, int priority, BindingTarget target) {
    this.planItemId = UUID.randomUUID().toString();
    this.bindingName = bindingName;
    this.workerName = workerName;
    this.priority = priority;
    this.status = PlanItemStatus.PENDING;
    this.createdAt = Instant.now();
    this.parentStageId = null;
    this.target = target;
  }

  /** For restoration from persistent store. Allows setting a specific planItemId and status. */
  private PlanItem(
      String planItemId,
      String bindingName,
      BindingTarget target,
      PlanItemStatus status,
      Instant createdAt) {
    this.planItemId = planItemId;
    this.bindingName = bindingName;
    this.workerName = null;
    this.priority = 0;
    this.target = target;
    this.status = status;
    this.createdAt = createdAt;
    this.parentStageId = null;
  }

  public static PlanItem create(String bindingName, String workerName, int priority) {
    return new PlanItem(bindingName, workerName, priority, null);
  }

  public static PlanItem create(
      String bindingName, String workerName, int priority, BindingTarget target) {
    return new PlanItem(bindingName, workerName, priority, target);
  }

  /**
   * Restores a PlanItem from persistent store after a JVM restart.
   *
   * <p>Only RUNNING and DELEGATED items are valid for restoration. PENDING items are re-created by
   * evaluation; terminal items must not re-enter the live plan.
   *
   * @throws IllegalArgumentException if status is not RUNNING or DELEGATED
   */
  public static PlanItem restore(
      String planItemId,
      String bindingName,
      BindingTarget target,
      PlanItemStatus status,
      Instant createdAt) {
    if (status != PlanItemStatus.RUNNING && status != PlanItemStatus.DELEGATED) {
      throw new IllegalArgumentException(
          "restore() only valid for RUNNING or DELEGATED status, got: " + status);
    }
    return new PlanItem(planItemId, bindingName, target, status, createdAt);
  }

  @Override
  public int compareTo(PlanItem other) {
    int cmp = Integer.compare(other.priority, this.priority); // higher first
    if (cmp != 0) return cmp;
    return this.createdAt.compareTo(other.createdAt); // earlier first
  }

  public String getPlanItemId() {
    return planItemId;
  }

  public String getBindingName() {
    return bindingName;
  }

  public String getWorkerName() {
    return workerName;
  }

  public int getPriority() {
    return priority;
  }

  public PlanItemStatus getStatus() {
    return status;
  }

  /** Transitions PENDING → RUNNING. For CapabilityTarget only — a Quartz job is executing. */
  public void markRunning() {
    if (status != PlanItemStatus.PENDING) {
      throw new IllegalStateException(
          "Cannot transition to RUNNING from " + status + " (planItemId=" + planItemId + ")");
    }
    status = PlanItemStatus.RUNNING;
  }

  /**
   * Transitions PENDING → DELEGATED. For SubCaseTarget, HumanTaskTarget, ExtensionTarget — control
   * has passed to an external actor and the engine is waiting for a completion signal.
   */
  public void markDelegated() {
    if (status != PlanItemStatus.PENDING) {
      throw new IllegalStateException(
          "Cannot transition to DELEGATED from " + status + " (planItemId=" + planItemId + ")");
    }
    status = PlanItemStatus.DELEGATED;
  }

  /** Transitions RUNNING or DELEGATED → COMPLETED. */
  public void markCompleted() {
    if (status != PlanItemStatus.RUNNING && status != PlanItemStatus.DELEGATED) {
      throw new IllegalStateException(
          "Cannot transition to COMPLETED from " + status + " (planItemId=" + planItemId + ")");
    }
    status = PlanItemStatus.COMPLETED;
  }

  /** Transitions to FAULTED from any active state. Throws if already terminal. */
  public void markFaulted() {
    if (status.isTerminal()) {
      throw new IllegalStateException(
          "Cannot fault a terminal PlanItem (status="
              + status
              + ", planItemId="
              + planItemId
              + ")");
    }
    status = PlanItemStatus.FAULTED;
  }

  /**
   * Transitions DELEGATED → REJECTED.
   *
   * <p>DELEGATED-only: only human task refusals ({@code WorkItemStatus.REJECTED}) and M-of-N group
   * threshold failures ({@code GroupStatus.REJECTED} on human task SpawnGroups) reach this path.
   * CapabilityTarget PlanItems are always RUNNING — they fault via retry exhaustion, never via
   * rejection. If a group-of-capability-targets path is added in future, this guard must be
   * revisited to allow RUNNING → REJECTED.
   */
  public void markRejected() {
    if (status != PlanItemStatus.DELEGATED) {
      throw new IllegalStateException(
          "Cannot transition to REJECTED from " + status + " (planItemId=" + planItemId + ")");
    }
    status = PlanItemStatus.REJECTED;
  }

  /** Obsoletes from any active state. Throws if already terminal. */
  public void markObsolete() {
    if (status.isTerminal()) {
      throw new IllegalStateException(
          "Cannot obsolete a terminal PlanItem (status="
              + status
              + ", planItemId="
              + planItemId
              + ")");
    }
    status = PlanItemStatus.OBSOLETE;
  }

  /** Cancels from any active state. Throws if already terminal. */
  public void markCancelled() {
    if (status.isTerminal()) {
      throw new IllegalStateException(
          "Cannot cancel a terminal PlanItem (status="
              + status
              + ", planItemId="
              + planItemId
              + ")");
    }
    status = PlanItemStatus.CANCELLED;
  }

  public BindingTarget getTarget() {
    return target;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Optional<String> getParentStageId() {
    return Optional.ofNullable(parentStageId);
  }

  public void setParentStageId(String stageId) {
    this.parentStageId = stageId;
  }
}
