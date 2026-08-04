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
package io.casehub.engine.planning.plan;

import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskDescriptor;
import io.casehub.api.model.TaskStatus;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activation record for a {@link io.casehub.api.model.Binding} on the {@link CasePlanModel}
 * scheduling agenda.
 *
 * <p>Priority is assigned by {@link io.casehub.engine.planning.control.PlanningStrategy}. Status is
 * updated by {@link io.casehub.engine.planning.handler.PlanItemCompletionHandler} on worker
 * completion. Implements {@link Comparable} for priority-ordered sorting (higher priority first;
 * FIFO for equal priority). Implements {@link TaskDescriptor} as the shared behavioral interface
 * for any coordination model's work unit.
 */
public class PlanItem implements Comparable<PlanItem>, TaskDescriptor {

  private final String planItemId;
  private final String bindingName;
  private final ExecutorRef executor;
  private final int priority;
  private final AtomicReference<TaskStatus> status;
  private final Instant createdAt;
  private String parentStageId;
  private final BindingTarget target;
  private final String description;

  private PlanItem(
      String bindingName,
      ExecutorRef executor,
      int priority,
      BindingTarget target,
      String description) {
    this.planItemId = UUID.randomUUID().toString();
    this.bindingName = bindingName;
    this.executor = executor;
    this.priority = priority;
    this.status = new AtomicReference<>(TaskStatus.PENDING);
    this.createdAt = Instant.now();
    this.parentStageId = null;
    this.target = target;
    this.description = description;
  }

  private PlanItem(
      String planItemId,
      String bindingName,
      ExecutorRef executor,
      BindingTarget target,
      TaskStatus status,
      Instant createdAt,
      String description) {
    this.planItemId = planItemId;
    this.bindingName = bindingName;
    this.executor = executor;
    this.priority = 0;
    this.target = target;
    this.status = new AtomicReference<>(status);
    this.createdAt = createdAt;
    this.parentStageId = null;
    this.description = description;
  }

  public static PlanItem create(String bindingName, ExecutorRef executor, int priority) {
    return new PlanItem(bindingName, executor, priority, null, null);
  }

  public static PlanItem create(
      String bindingName, ExecutorRef executor, int priority, BindingTarget target) {
    return new PlanItem(bindingName, executor, priority, target, null);
  }

  public static PlanItem create(
      String bindingName,
      ExecutorRef executor,
      int priority,
      BindingTarget target,
      String description) {
    return new PlanItem(bindingName, executor, priority, target, description);
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
      @Nullable ExecutorRef executor,
      BindingTarget target,
      TaskStatus status,
      Instant createdAt) {
    return restore(planItemId, bindingName, executor, target, status, createdAt, null);
  }

  public static PlanItem restore(
      String planItemId,
      String bindingName,
      @Nullable ExecutorRef executor,
      BindingTarget target,
      TaskStatus status,
      Instant createdAt,
      String description) {
    if (status != TaskStatus.RUNNING && status != TaskStatus.DELEGATED) {
      throw new IllegalArgumentException(
          "restore() only valid for RUNNING or DELEGATED status, got: " + status);
    }
    return new PlanItem(planItemId, bindingName, executor, target, status, createdAt, description);
  }

  @Override
  public int compareTo(PlanItem other) {
    int cmp = Integer.compare(other.priority, this.priority); // higher first
    if (cmp != 0) return cmp;
    return this.createdAt.compareTo(other.createdAt); // earlier first
  }

  // ── TaskDescriptor implementation ─────────────────────────────────────────

  @Override
  public String id() {
    return planItemId;
  }

  @Override
  @Nullable
  public String description() {
    return description;
  }

  @Override
  @Nullable
  public ExecutorRef executor() {
    return executor;
  }

  @Override
  public TaskStatus status() {
    return status.get();
  }

  @Override
  public Instant createdAt() {
    return createdAt;
  }

  // ── Legacy accessors (prefer TaskDescriptor methods) ──────────────────────

  @Deprecated
  public String getPlanItemId() {
    return planItemId;
  }

  public String getBindingName() {
    return bindingName;
  }

  @Nullable
  public String executorName() {
    return executor != null ? executor.name() : null;
  }

  public int getPriority() {
    return priority;
  }

  public TaskStatus getStatus() {
    return status.get();
  }

  // ── State transitions ─────────────────────────────────────────────────────

  public boolean tryMarkRunning() {
    return status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING);
  }

  public boolean tryMarkDispatching() {
    return status.compareAndSet(TaskStatus.PENDING, TaskStatus.DISPATCHING);
  }

  public boolean revertDispatching() {
    return status.compareAndSet(TaskStatus.DISPATCHING, TaskStatus.PENDING);
  }

  public void markRunning() {
    if (!status.compareAndSet(TaskStatus.PENDING, TaskStatus.RUNNING)) {
      throw new IllegalStateException(
          "Cannot transition to RUNNING from " + status.get() + " (planItemId=" + planItemId + ")");
    }
  }

  public void markDelegated() {
    if (!status.compareAndSet(TaskStatus.DISPATCHING, TaskStatus.DELEGATED)) {
      throw new IllegalStateException(
          "Cannot transition to DELEGATED from "
              + status.get()
              + " (planItemId="
              + planItemId
              + ")");
    }
  }

  public void markCompleted() {
    while (true) {
      TaskStatus current = status.get();
      if (current != TaskStatus.RUNNING && current != TaskStatus.DELEGATED) {
        throw new IllegalStateException(
            "Cannot transition to COMPLETED from " + current + " (planItemId=" + planItemId + ")");
      }
      if (status.compareAndSet(current, TaskStatus.COMPLETED)) return;
    }
  }

  public void markFaulted() {
    while (true) {
      TaskStatus current = status.get();
      if (current.isTerminal()) {
        throw new IllegalStateException(
            "Cannot fault a terminal PlanItem (status="
                + current
                + ", planItemId="
                + planItemId
                + ")");
      }
      if (status.compareAndSet(current, TaskStatus.FAULTED)) return;
    }
  }

  public void markRejected() {
    if (!status.compareAndSet(TaskStatus.DELEGATED, TaskStatus.REJECTED)) {
      throw new IllegalStateException(
          "Cannot transition to REJECTED from "
              + status.get()
              + " (planItemId="
              + planItemId
              + ")");
    }
  }

  public void markObsolete() {
    while (true) {
      TaskStatus current = status.get();
      if (current.isTerminal()) {
        throw new IllegalStateException(
            "Cannot obsolete a terminal PlanItem (status="
                + current
                + ", planItemId="
                + planItemId
                + ")");
      }
      if (status.compareAndSet(current, TaskStatus.OBSOLETE)) return;
    }
  }

  public void markSuspended() {
    if (!status.compareAndSet(TaskStatus.DELEGATED, TaskStatus.SUSPENDED)) {
      throw new IllegalStateException(
          "Cannot suspend from " + status.get() + " (planItemId=" + planItemId + ")");
    }
  }

  public void markResumed() {
    if (!status.compareAndSet(TaskStatus.SUSPENDED, TaskStatus.DELEGATED)) {
      throw new IllegalStateException(
          "Cannot resume from " + status.get() + " (planItemId=" + planItemId + ")");
    }
  }

  public void markCancelled() {
    while (true) {
      TaskStatus current = status.get();
      if (current.isTerminal()) {
        throw new IllegalStateException(
            "Cannot cancel a terminal PlanItem (status="
                + current
                + ", planItemId="
                + planItemId
                + ")");
      }
      if (status.compareAndSet(current, TaskStatus.CANCELLED)) return;
    }
  }

  // ── Other accessors ───────────────────────────────────────────────────────

  public String getDescription() {
    return description;
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
