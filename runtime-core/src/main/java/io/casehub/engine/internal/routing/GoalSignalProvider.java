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
import io.casehub.eidos.api.AgentGoal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public class GoalSignalProvider implements RoutingSignalProvider {

  private final Optional<GoalAbandonmentEvaluator> evaluator;

  public GoalSignalProvider(Optional<GoalAbandonmentEvaluator> evaluator) {
    this.evaluator = evaluator;
  }

  @Override
  public String id() {
    return "goal";
  }

  @Override
  public @Nullable RoutingSignal evaluate(
      AgentRoutingContext context, List<AgentCandidate> eligible) {
    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();

    for (var candidate : eligible) {
      if (candidate.agentDescriptor() == null) {
        continue;
      }

      var descriptor = candidate.agentDescriptor();
      List<AgentGoal> totalGoals = descriptor.goals();

      if (totalGoals.isEmpty()) {
        continue;
      }

      List<AgentGoal> activeGoals =
          evaluator.isPresent() ? evaluator.get().activeGoals(descriptor) : totalGoals;

      if (activeGoals.isEmpty()) {
        signals.put(
            candidate.workerId(), new RoutingSignal.CandidateSignal.Exclude("all goals abandoned"));
        continue;
      }

      double score = (double) activeGoals.size() / totalGoals.size();
      String rationale = "%d/%d active goals".formatted(activeGoals.size(), totalGoals.size());
      signals.put(candidate.workerId(), new RoutingSignal.CandidateSignal.Score(score, rationale));
    }

    return signals.isEmpty() ? null : new RoutingSignal(signals);
  }
}
