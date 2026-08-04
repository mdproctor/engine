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
package io.casehub.api.model;

/**
 * Shared lifecycle states for any coordination model's unit of work.
 *
 * <p><b>Active states:</b>
 *
 * <ul>
 *   <li>PENDING — work defined, not yet started
 *   <li>RUNNING — actively executing
 *   <li>DELEGATED — control passed to external actor; waiting for completion signal
 *   <li>SUSPENDED — execution paused; slot occupied, resumes without re-dispatch
 * </ul>
 *
 * <p><b>Terminal states:</b>
 *
 * <ul>
 *   <li>COMPLETED — finished successfully
 *   <li>FAULTED — failed (system failure, deadline breach, or gate rejection)
 *   <li>REJECTED — actor deliberately refused the work
 *   <li>OBSOLETE — context changed, work became irrelevant
 *   <li>CANCELLED — deliberate stop by human or system
 * </ul>
 *
 * <p>Stored as STRING in JPA — ordinal safety is not a concern.
 */
public enum TaskStatus {
  PENDING,
  DISPATCHING,
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
    return this == PENDING
        || this == DISPATCHING
        || this == RUNNING
        || this == DELEGATED
        || this == SUSPENDED;
  }
}
