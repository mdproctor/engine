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
package io.casehub.engine.internal.routing;

import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.RoutingResult;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;

/**
 * Default {@link AgentRoutingStrategy} — selects the candidate with the fewest active Quartz jobs.
 * Ties are broken by list order (deterministic). Health is not considered here; trust-aware
 * demotion is left to {@code TrustWeightedAgentStrategy} when deployed.
 */
@DefaultBean
@ApplicationScoped
@Unremovable
public class LeastLoadedAgentStrategy implements AgentRoutingStrategy {

  @Override
  public String id() {
    return "least-loaded";
  }

  @Override
  public RoutingResult select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return RoutingResult.unresolvable("no candidates available");
    }

    final List<AgentCandidate> sorted =
        candidates.stream().sorted(Comparator.comparingInt(AgentCandidate::runningJobs)).toList();

    final AgentCandidate best = sorted.get(0);
    final String rationale;
    if (sorted.size() >= 2) {
      final AgentCandidate second = sorted.get(1);
      rationale =
          "selected %s: load %d (vs next: %s load %d)"
              .formatted(
                  best.workerId(), best.runningJobs(), second.workerId(), second.runningJobs());
    } else {
      rationale =
          "selected %s: load %d (sole candidate)".formatted(best.workerId(), best.runningJobs());
    }
    return RoutingResult.assigned(best.workerId(), rationale);
  }
}
