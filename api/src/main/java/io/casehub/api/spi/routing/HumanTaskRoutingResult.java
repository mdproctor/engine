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

import java.util.Map;
import java.util.Set;

/**
 * Sealed result type from {@link HumanTaskRoutingStrategy#select}. Follows the convention of {@link
 * RoutingResult} (Selected | Unresolvable | Escalated) and {@link ImplementationSelection}
 * (Selected | RunAll | RunNone).
 *
 * <p>{@code candidateScores} keys are from {@code candidateUsers} only — group scoring requires
 * group membership resolution which is out of scope (engine#757).
 */
public sealed interface HumanTaskRoutingResult
    permits HumanTaskRoutingResult.Enriched,
        HumanTaskRoutingResult.Unchanged,
        HumanTaskRoutingResult.Escalated {

  record Enriched(
      Set<String> candidateGroups, Set<String> candidateUsers, Map<String, Double> candidateScores)
      implements HumanTaskRoutingResult {
    public Enriched {
      candidateGroups = Set.copyOf(candidateGroups);
      candidateUsers = Set.copyOf(candidateUsers);
      candidateScores = Map.copyOf(candidateScores);
    }
  }

  record Unchanged() implements HumanTaskRoutingResult {}

  record Escalated(String reason) implements HumanTaskRoutingResult {}
}
