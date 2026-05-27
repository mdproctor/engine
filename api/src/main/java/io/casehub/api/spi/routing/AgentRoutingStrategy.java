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

import java.util.List;

/**
 * Engine-owned SPI for agent worker selection. Replaces the borrowed {@code
 * WorkerSelectionStrategy} from casehub-work, which was designed for human task routing and carried
 * semantics incompatible with agent scheduling (WorkItem counts, claim-first model, null-padded
 * SelectionContext).
 *
 * <p>Implement as {@code @ApplicationScoped @Alternative @Priority(N)} where N > 0 to override
 * {@link io.casehub.engine.internal.routing.LeastLoadedAgentStrategy}. Higher priority wins.
 *
 * <p>Known implementations:
 *
 * <ul>
 *   <li>{@code LeastLoadedAgentStrategy} — {@code @DefaultBean}, prefers fewest running Quartz jobs
 *   <li>{@code TrustWeightedAgentStrategy} — {@code @Alternative @Priority(1)}, trust maturity
 *       model
 * </ul>
 */
@FunctionalInterface
public interface AgentRoutingStrategy {

  /**
   * Select a worker from the pre-filtered candidate list.
   *
   * <p>Candidates are pre-filtered: {@code Unavailable} workers are never passed here. {@code
   * EPISTEMICALLY_WEAK} and {@code DEGRADED} workers are included — implementations may apply
   * preference demotion via {@link AgentCandidate#health()}.
   *
   * @param context routing context carrying caseId and capabilityName
   * @param candidates non-empty list of eligible candidates (filtered, health-probed)
   * @return assignment decision; use {@link AgentAssignment#noOp()} when no candidate qualifies
   */
  AgentAssignment select(AgentRoutingContext context, List<AgentCandidate> candidates);
}
