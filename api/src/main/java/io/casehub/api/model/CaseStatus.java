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

public enum CaseStatus {
  /** Case is initializing — cached but event handlers have not yet completed. */
  STARTING,
  /** Case is actively executing. */
  RUNNING,
  /** Case is blocked waiting for an external event or signal. */
  WAITING,
  /** Case has been paused by an administrative action. */
  SUSPENDED,
  /** Case completed successfully. */
  COMPLETED,
  /** Case terminated due to an error. */
  FAULTED,
  /** Case was stopped before completion. */
  CANCELLED,
  /** Compensation in progress — completed case is having its effects undone. */
  COMPENSATING,
  /** All compensating bindings completed — case effects fully reversed. */
  COMPENSATED,
  /** Compensation attempted but a compensating step failed — intervention required. */
  COMPENSATION_FAULTED;

  /**
   * All terminal statuses, derived from {@link #isTerminal()}. Single source of truth for queries.
   */
  public static final java.util.List<CaseStatus> TERMINAL_STATUSES =
      java.util.Arrays.stream(values()).filter(CaseStatus::isTerminal).toList();

  /**
   * Returns {@code true} if this status represents a terminal (end) state from which the case
   * cannot transition further under normal lifecycle rules.
   */
  public boolean isTerminal() {
    return switch (this) {
      case COMPLETED, FAULTED, CANCELLED, COMPENSATED -> true;
      default -> false;
    };
  }

  /**
   * Returns {@code true} if this status represents an active state in which the case is still being
   * processed or awaiting input.
   */
  public boolean isActive() {
    return switch (this) {
      case STARTING, RUNNING, WAITING, SUSPENDED, COMPENSATING, COMPENSATION_FAULTED -> true;
      default -> false;
    };
  }
}
