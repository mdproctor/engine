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

import io.casehub.api.model.TaskStatus;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class PlanItemExecutionState {

  private final String planItemId;
  private final AtomicReference<TaskStatus> status;

  public PlanItemExecutionState(String planItemId) {
    this(planItemId, TaskStatus.PENDING);
  }

  private PlanItemExecutionState(String planItemId, TaskStatus initialStatus) {
    this.planItemId = Objects.requireNonNull(planItemId, "planItemId required");
    this.status = new AtomicReference<>(Objects.requireNonNull(initialStatus, "status required"));
  }

  public static PlanItemExecutionState restore(String planItemId, TaskStatus status) {
    return new PlanItemExecutionState(planItemId, status);
  }

  public String planItemId() {
    return planItemId;
  }

  public TaskStatus getStatus() {
    return status.get();
  }

  public boolean isTerminal() {
    return status.get().isTerminal();
  }

  public boolean tryTransition(TaskStatus from, TaskStatus to) {
    if (from.isTerminal()) {
      return false;
    }
    return status.compareAndSet(from, to);
  }

  public void forceTransition(TaskStatus to) {
    while (true) {
      TaskStatus current = status.get();
      if (current.isTerminal()) {
        throw new IllegalStateException(
            "Cannot transition from terminal status "
                + current
                + " (planItemId="
                + planItemId
                + ")");
      }
      if (status.compareAndSet(current, to)) {
        return;
      }
    }
  }
}
