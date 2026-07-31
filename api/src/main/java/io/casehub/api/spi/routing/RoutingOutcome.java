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
package io.casehub.api.spi.routing;

/**
 * Outcome of a routing decision — recorded by {@link RoutingOutcomeRecorder} to feed back into
 * routing strategies (e.g. CBR-enriched routing).
 *
 * <p>{@link #SUCCESS} and {@link #FAILURE} are recorded from the worker completion path in {@code
 * WorkflowExecutionCompletedHandler}. {@link #GATE_REJECTED} and {@link #GATE_EXPIRED} are recorded
 * directly from the gate resolution handlers. {@link #DECLINED}, {@link #CANCELLED}, and {@link
 * #OBSOLETE} are recorded from the task lifecycle terminal states.
 */
public enum RoutingOutcome {
  /** Worker completed successfully (including gate-approved re-dispatch). */
  SUCCESS,
  /** Worker returned a non-success outcome (Failed or Expired). */
  FAILURE,
  /** Worker's planned action was rejected by a human via the oversight gate. */
  GATE_REJECTED,
  /** Worker's planned action gate expired without review. */
  GATE_EXPIRED,
  /** Worker declined the assigned task. */
  DECLINED,
  /** Task was cancelled externally (not the worker's fault). */
  CANCELLED,
  /** Task became irrelevant before completion (not the worker's fault). */
  OBSOLETE
}
