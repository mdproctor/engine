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

import jakarta.annotation.Nullable;
import java.util.List;

public sealed interface RoutingResult
    permits RoutingResult.Selected, RoutingResult.Unresolvable, RoutingResult.Escalated {

  record Selected(List<Assignment> assignments) implements RoutingResult {
    public Selected {
      if (assignments.isEmpty()) {
        throw new IllegalArgumentException("Selected must have at least one assignment");
      }
      assignments = List.copyOf(assignments);
    }

    public Assignment single() {
      if (assignments.size() != 1) {
        throw new IllegalStateException("Expected single assignment, got " + assignments.size());
      }
      return assignments.getFirst();
    }
  }

  record Unresolvable(String reason) implements RoutingResult {}

  record Escalated(
      @Nullable String capabilityName, EscalationReason escalationReason, String reason)
      implements RoutingResult {}

  static RoutingResult assigned(Assignment assignment) {
    return new Selected(List.of(assignment));
  }

  static RoutingResult assigned(String executorId, String reason) {
    return new Selected(List.of(new Assignment(executorId, null, reason)));
  }

  static RoutingResult assigned(List<Assignment> assignments) {
    return new Selected(assignments);
  }

  static RoutingResult unresolvable(String reason) {
    return new Unresolvable(reason);
  }

  static RoutingResult escalate(String capabilityName, EscalationReason reason, String rationale) {
    return new Escalated(capabilityName, reason, rationale);
  }
}
