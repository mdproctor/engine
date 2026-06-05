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
 * Result of {@link AgentRoutingStrategy#select}. A sealed type with three distinct outcomes:
 *
 * <ul>
 *   <li>{@link Assigned} — a specific worker was selected
 *   <li>{@link Unresolvable} — no candidate passed trust filters (none were borderline)
 *   <li>{@link EscalateToOversight} — all trust-eligible candidates are borderline, or the
 *       bootstrap fallback threshold was breached; human oversight is required per
 *       trust-maturity-model.md Phase 2. The {@code reason} field identifies why escalation was
 *       triggered — callers use it to populate {@link
 *       io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent} and to select the
 *       appropriate oversight handler.
 * </ul>
 *
 * <p>Callers must switch exhaustively on the sealed type. The previous {@code isNoOp()} pattern is
 * removed — the three outcomes are semantically distinct and the engine's response to each differs.
 */
public sealed interface AgentAssignment
    permits AgentAssignment.Assigned,
        AgentAssignment.Unresolvable,
        AgentAssignment.EscalateToOversight {

  /** A specific worker was selected for the capability. */
  record Assigned(String workerId) implements AgentAssignment {}

  /**
   * No candidate passed trust filters. None were borderline — the pool simply lacks qualified
   * agents. Engine falls to {@code tryProvision()}.
   */
  record Unresolvable() implements AgentAssignment {}

  /**
   * All trust-eligible candidates are borderline (score within {@code borderlineMargin} of {@code
   * threshold}), or the bootstrap fallback threshold was breached. Engine must route to human
   * oversight per trust-maturity-model.md Phase 2. The {@code reason} field identifies the specific
   * trigger condition.
   */
  record EscalateToOversight(String capabilityName, EscalationReason reason)
      implements AgentAssignment {}

  static AgentAssignment assign(final String workerId) {
    return new Assigned(workerId);
  }

  static AgentAssignment unresolvable() {
    return new Unresolvable();
  }

  static AgentAssignment escalate(final String capabilityName, final EscalationReason reason) {
    return new EscalateToOversight(capabilityName, reason);
  }
}
