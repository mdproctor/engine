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

import io.casehub.api.spi.routing.ExperienceAnalyser;
import io.casehub.api.spi.routing.HumanTaskCandidates;
import io.casehub.api.spi.routing.HumanTaskRoutingContext;
import io.casehub.api.spi.routing.HumanTaskRoutingResult;
import io.casehub.api.spi.routing.HumanTaskRoutingStrategy;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;

/**
 * CBR-based humanTask routing strategy that scores candidate users using historical plan trace
 * data. Matches steps by {@code bindingName} (humanTask traces have null {@code capabilityName}).
 *
 * <p>Enrichment only — groups and users pass through unchanged, with {@code candidateScores} added
 * for users that have matching plan trace data. Never blocks dispatch.
 *
 * <p>Resolved via {@code StrategyResolver} when {@code CaseDefinition.getHumanTaskRouting()}
 * returns {@code "cbr"}. Refs casehubio/engine#754.
 */
@ApplicationScoped
@Unremovable
public class CbrHumanTaskRoutingStrategy implements HumanTaskRoutingStrategy {

  @Override
  public String id() {
    return "cbr";
  }

  @Override
  public HumanTaskRoutingResult select(
      final HumanTaskRoutingContext context, final HumanTaskCandidates candidates) {
    if (context.experiences().isEmpty()) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    final Set<String> allUsers = candidates.allUsers();
    if (allUsers.isEmpty()) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    final String bindingName = context.bindingName();
    final Map<String, Double> scores =
        ExperienceAnalyser.workerSuccessRates(
            context.experiences(),
            allUsers,
            step -> bindingName.equals(step.bindingName()),
            ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);

    if (scores.isEmpty()) {
      return new HumanTaskRoutingResult.Unchanged();
    }

    return new HumanTaskRoutingResult.Enriched(candidates.groups(), allUsers, scores);
  }
}
