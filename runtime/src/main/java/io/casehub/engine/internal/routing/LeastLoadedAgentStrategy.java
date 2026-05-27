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

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Comparator;
import java.util.List;

/**
 * Default {@link AgentRoutingStrategy} — selects the candidate with the fewest active Quartz jobs.
 * Ties are broken by list order (deterministic). Health is not considered here; trust-aware
 * demotion is left to {@code TrustWeightedAgentStrategy} when deployed.
 *
 * <p>Yields automatically to any {@code @ApplicationScoped} or {@code @Alternative @Priority(N)}
 * implementation present on the classpath.
 */
@DefaultBean
@ApplicationScoped
public class LeastLoadedAgentStrategy implements AgentRoutingStrategy {

  @Override
  public AgentAssignment select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    return candidates.stream()
        .min(Comparator.comparingInt(AgentCandidate::runningJobs))
        .map(c -> new AgentAssignment(c.workerId()))
        .orElse(AgentAssignment.noOp());
  }
}
