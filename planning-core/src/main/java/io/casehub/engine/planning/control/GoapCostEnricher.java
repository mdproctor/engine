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
package io.casehub.engine.planning.control;

import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.routing.ExperienceAnalyser;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.plan.goap.CostFunction;
import io.casehub.engine.plan.goap.GoapAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class GoapCostEnricher {

  private static final int DEFAULT_MIN_COST_SAMPLES = 5;

  private GoapCostEnricher() {}

  public static List<GoapAction> enrichWithLearnedCosts(
      List<GoapAction> actions, List<RetrievedExperience> experiences, int minCostSamples) {
    if (experiences == null || experiences.isEmpty()) {
      return actions;
    }

    Set<String> actionNames = actions.stream().map(GoapAction::name).collect(Collectors.toSet());
    Map<String, Double> factors =
        ExperienceAnalyser.actionCostFactors(experiences, actionNames, minCostSamples);
    if (factors.isEmpty()) {
      return actions;
    }

    List<GoapAction> enriched = new ArrayList<>(actions.size());
    for (GoapAction action : actions) {
      Double factor = factors.get(action.name());
      if (factor == null || factor == 1.0) {
        enriched.add(action);
        continue;
      }
      final double f = factor;
      CostFunction learned =
          state -> {
            double base =
                action.costFunction() != null
                    ? action.costFunction().compute(state)
                    : action.cost();
            return base * f;
          };
      enriched.add(
          new GoapAction(
              action.name(),
              action.preconditions(),
              action.effects(),
              action.cost(),
              action.benefit(),
              action.softPreconditions(),
              learned));
    }
    return enriched;
  }

  public static int resolveMinCostSamples(CbrConfig cbrConfig) {
    if (cbrConfig != null && cbrConfig.minCostSamples() != null) {
      return cbrConfig.minCostSamples();
    }
    return DEFAULT_MIN_COST_SAMPLES;
  }
}
