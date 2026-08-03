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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Goal-aware routing signal provider that scores agents based on their goal engagement.
 *
 * <p>Agents with more active (non-abandoned) goals receive higher scores, indicating higher
 * engagement and relevance. Agents with all goals abandoned are excluded. Agents without goals or
 * without AgentDescriptors are skipped (absent from signal map — weight redistributed).
 */
@ApplicationScoped
public class GoalSignalProvider implements RoutingSignalProvider {

  private final Instance<GoalAbandonmentEvaluator> evaluator;

  @Inject
  public GoalSignalProvider(Instance<GoalAbandonmentEvaluator> evaluator) {
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
        // No descriptor — skip (absent from signal map)
        continue;
      }

      var descriptor = candidate.agentDescriptor();
      List<AgentGoal> totalGoals = descriptor.goals();

      if (totalGoals.isEmpty()) {
        // No goals declared — skip (absent from signal map)
        continue;
      }

      List<AgentGoal> activeGoals =
          evaluator.isResolvable()
              ? evaluator.get().activeGoals(descriptor)
              : totalGoals; // No evaluator — all goals considered active

      if (activeGoals.isEmpty()) {
        // All goals abandoned — exclude
        signals.put(
            candidate.workerId(), new RoutingSignal.CandidateSignal.Exclude("all goals abandoned"));
        continue;
      }

      // Score = fraction of non-abandoned goals (higher = more engaged)
      double score = (double) activeGoals.size() / totalGoals.size();
      String rationale = "%d/%d active goals".formatted(activeGoals.size(), totalGoals.size());
      signals.put(candidate.workerId(), new RoutingSignal.CandidateSignal.Score(score, rationale));
    }

    return signals.isEmpty() ? null : new RoutingSignal(signals);
  }
}
