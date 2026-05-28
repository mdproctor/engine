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

import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Engine-owned SPI for agent worker selection. Replaces the borrowed {@code
 * WorkerSelectionStrategy} from casehub-work.
 *
 * <p>Implement as {@code @ApplicationScoped @Alternative @Priority(N)} where N > 0 to override
 * {@link io.casehub.engine.internal.routing.LeastLoadedAgentStrategy}. Higher priority wins.
 *
 * <p>Implementations that do only in-memory work (e.g. trust scoring against a local cache) should
 * return {@code Uni.createFrom().item(result)}. Implementations that make blocking calls (e.g. an
 * embedding service) must return a reactive chain that executes on a worker thread — never blocking
 * the Vert.x IO thread.
 *
 * <p>Implementations MUST be thread-safe — {@code select()} may be called concurrently.
 *
 * <p>Known implementations:
 *
 * <ul>
 *   <li>{@code LeastLoadedAgentStrategy} — {@code @DefaultBean}, prefers fewest running Quartz jobs
 *   <li>{@code TrustWeightedAgentStrategy} — {@code @Alternative @Priority(1)}, trust maturity
 *       model
 *   <li>{@code SemanticAgentRoutingStrategy} — {@code @Alternative @Priority(2)}, optional module
 *       {@code casehub-engine-ai}, embedding-based semantic re-ranking over trust-qualified
 *       candidates
 * </ul>
 */
public interface AgentRoutingStrategy {

  /**
   * Select a worker from the pre-filtered candidate list.
   *
   * <p>Candidates are pre-filtered: {@code Unavailable} workers are never passed here. {@code
   * EPISTEMICALLY_WEAK} and {@code DEGRADED} workers are included — implementations may apply
   * preference demotion via {@link AgentCandidate#health()}.
   *
   * @param context routing context carrying caseId, capabilityName, and caseContext
   * @param candidates non-empty list of eligible candidates (filtered, health-probed)
   * @return a {@code Uni} resolving to one of: {@link AgentAssignment.Assigned}, {@link
   *     AgentAssignment.Unresolvable}, or {@link AgentAssignment.EscalateToOversight}
   */
  Uni<AgentAssignment> select(AgentRoutingContext context, List<AgentCandidate> candidates);
}
