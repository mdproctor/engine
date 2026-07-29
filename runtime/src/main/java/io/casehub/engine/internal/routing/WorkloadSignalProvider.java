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
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;

@ApplicationScoped
public class WorkloadSignalProvider implements RoutingSignalProvider {

  @Override
  public String id() {
    return "workload";
  }

  @Override
  public RoutingSignal evaluate(AgentRoutingContext context, List<AgentCandidate> eligible) {
    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    for (var candidate : eligible) {
      double score = 1.0 / (1.0 + candidate.runningJobs());
      signals.put(
          candidate.workerId(),
          new RoutingSignal.CandidateSignal.Score(
              score, "load %d".formatted(candidate.runningJobs())));
    }
    return new RoutingSignal(signals);
  }
}
