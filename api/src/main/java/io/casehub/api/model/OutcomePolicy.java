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
 * Policy for handling semantic worker outcomes (DECLINED, FAILED, EXPIRED) on a per-binding basis.
 *
 * <p>Each outcome type maps to an {@link OutcomeAction}: {@code REROUTE} writes failure state and
 * re-dispatches to a different agent; {@code FAULT} marks the case FAULTED immediately.
 *
 * <p>{@code onExpired} is declared for forward compatibility — the EXPIRED signal is not wired in
 * the initial implementation. See engine#513.
 *
 * @param onDecline action when a worker returns {@link WorkerOutcome.Declined}
 * @param onFailure action when a worker returns {@link WorkerOutcome.Failed}
 * @param onExpired action when a worker's commitment expires (not yet wired)
 * @param maxRerouteAttempts maximum dispatch+outcome cycles before writing REROUTES_EXHAUSTED
 */
public record OutcomePolicy(
    OutcomeAction onDecline,
    OutcomeAction onFailure,
    OutcomeAction onExpired,
    int maxRerouteAttempts) {

  public OutcomePolicy() {
    this(OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 3);
  }
}
