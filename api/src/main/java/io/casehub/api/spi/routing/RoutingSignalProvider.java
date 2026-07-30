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

import io.casehub.platform.api.routing.NamedStrategy;
import java.util.List;
import org.jspecify.annotations.Nullable;

public interface RoutingSignalProvider extends NamedStrategy {

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
  @Nullable RoutingSignal evaluate(AgentRoutingContext context, List<AgentCandidate> eligible);
}
