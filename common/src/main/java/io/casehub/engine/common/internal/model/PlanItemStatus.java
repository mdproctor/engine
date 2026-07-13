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
 * Compatibility shim — bridges stale casehub-engine-work-adapter SNAPSHOT that still references
 * PlanItemStatus (renamed to TaskStatus in io.casehub.api.model). Remove once
 * casehub-engine-work-adapter is republished with the TaskStatus migration.
 *
 * @deprecated Use {@code io.casehub.api.model.TaskStatus} instead.
 */
@Deprecated(forRemoval = true)
public enum PlanItemStatus {
  PENDING,
  RUNNING,
  DELEGATED,
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
