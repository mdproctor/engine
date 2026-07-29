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
import io.casehub.api.spi.routing.ExperienceAnalyser;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
public class ExperienceSignalProvider implements RoutingSignalProvider {

  @Override
  public String id() {
    return "experience";
  }

  @Override
  public @Nullable RoutingSignal evaluate(AgentRoutingContext context, List<AgentCandidate> eligible) {
    if (context.experiences() == null || context.experiences().isEmpty()) {
      return null;
    }
    Set<String> workerIds =
        eligible.stream().map(AgentCandidate::workerId).collect(Collectors.toSet());
    Map<String, Double> rates =
        ExperienceAnalyser.workerSuccessRates(
            context.experiences(),
            workerIds,
            context.capabilityName(),
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    if (rates.isEmpty()) {
      return null;
    }
    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    for (var entry : rates.entrySet()) {
      signals.put(
          entry.getKey(),
          new RoutingSignal.CandidateSignal.Score(
              entry.getValue(), "experience rate %.2f".formatted(entry.getValue())));
    }
    return new RoutingSignal(signals);
  }
}
