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
import org.jspecify.annotations.Nullable;

/**
 * Pluggable SPI for structured signal enrichment in agent routing strategies.
 *
 * <p>Implementations contribute per-candidate scoring data that helps algorithmic routing
 * strategies (e.g. {@code CbrAgentRoutingStrategy}) make better selection decisions. All discovered
 * implementations are composed by {@link RoutingSignalAssembler}.
 *
 * <p>This is the structured counterpart to {@link RoutingPromptSection}, which provides text
 * enrichment for LLM-based strategies. Signal providers return typed data; prompt sections return
 * prompt text.
 *
 * <p>Implement as {@code @ApplicationScoped} with optional {@code @Priority(N)} to control
 * evaluation order (lower values first). Return {@code null} when the provider has nothing to
 * contribute for the current context.
 *
 * <p>Implementations must be thread-safe — {@code signal()} may be called concurrently.
 */
public interface RoutingSignalProvider {

  /** Identifies this signal source (e.g. {@code "plan-composition"}). */
  String id();

  /**
   * Compute per-candidate scoring signals for the given routing context and eligible candidates.
   *
   * <p>All scores must be in [0.0, 1.0] — {@link RoutingSignalAssembler} clamps out-of-range values
   * and logs a warning.
   *
   * @param context the routing context carrying caseId, capabilityName, experiences, etc.
   * @param eligible the pre-filtered, health-probed candidate list
   * @return a signal with per-candidate scores, or {@code null} if this provider has nothing to
   *     contribute
   */
  @Nullable RoutingSignal signal(AgentRoutingContext context, List<AgentCandidate> eligible);
}
