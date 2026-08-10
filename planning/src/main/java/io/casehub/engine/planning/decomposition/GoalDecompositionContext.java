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
package io.casehub.engine.planning.decomposition;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.worker.api.Capability;
import java.util.List;
import java.util.Objects;

public record GoalDecompositionContext(
    JsonNode state,
    int depth,
    List<Capability> availableCapabilities,
    PlanningConstraints planningConstraints)
    implements DecompositionContext<JsonNode> {

  public GoalDecompositionContext(
      JsonNode state, int depth, List<Capability> availableCapabilities) {
    this(state, depth, availableCapabilities, null);
  }

  public GoalDecompositionContext {
    Objects.requireNonNull(state, "state");
    availableCapabilities = List.copyOf(availableCapabilities);
    if (planningConstraints == null) {
      planningConstraints = PlanningConstraints.unconstrained();
    }
  }

  @Override
  public PlanningConstraints constraints() {
    return planningConstraints;
  }
}
