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
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalAssembler;
import io.quarkus.arc.DefaultBean;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

@DefaultBean
@ApplicationScoped
@Unremovable
public class ComposableAgentRoutingStrategy implements AgentRoutingStrategy {

  private static final Logger LOG = Logger.getLogger(ComposableAgentRoutingStrategy.class);
  private final RoutingSignalAssembler assembler;

  @Inject
  public ComposableAgentRoutingStrategy(RoutingSignalAssembler assembler) {
    this.assembler = assembler;
  }

  @Override
  public String id() {
    return "composable";
  }

  @Override
  public RoutingResult select(AgentRoutingContext context, List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return RoutingResult.unresolvable("no candidates available");
    }

    Map<String, RoutingSignal> allSignals = assembler.assemble(context, candidates);
    Map<String, Double> weights = resolveWeights(context, allSignals.keySet());

    Map<String, Double> scores = new LinkedHashMap<>();
    List<String> excludedReasons = new ArrayList<>();
    EscalationReason escalationReason = null;
    String escalationRationale = null;

    for (AgentCandidate candidate : candidates) {
      String workerId = candidate.workerId();
      boolean excluded = false;

      Map<String, Double> candidateScores = new LinkedHashMap<>();
      double totalWeight = 0.0;

      for (var entry : weights.entrySet()) {
        String providerId = entry.getKey();
        double weight = entry.getValue();
        RoutingSignal signal = allSignals.get(providerId);
        if (signal == null) continue;

        RoutingSignal.CandidateSignal cs = signal.candidates().get(workerId);
        if (cs == null) continue;

        switch (cs) {
          case RoutingSignal.CandidateSignal.Score s -> {
            candidateScores.put(providerId, s.value());
            totalWeight += weight;
          }
          case RoutingSignal.CandidateSignal.Exclude e -> {
            excluded = true;
            excludedReasons.add(workerId + ": " + e.reason());
          }
          case RoutingSignal.CandidateSignal.Escalate e -> {
            excluded = true;
            escalationReason = e.reason();
            escalationRationale = e.rationale();
          }
        }
        if (excluded) break;
      }

      if (excluded) continue;

      if (candidateScores.isEmpty()) {
        scores.put(workerId, 0.5);
      } else {
        double blended = 0.0;
        for (var scoreEntry : candidateScores.entrySet()) {
          double normalizedWeight = weights.get(scoreEntry.getKey()) / totalWeight;
          blended += scoreEntry.getValue() * normalizedWeight;
        }
        scores.put(workerId, blended);
      }
    }

    if (scores.isEmpty()) {
      if (escalationReason != null) {
        return RoutingResult.escalate(
            context.capabilityName(), escalationReason, escalationRationale);
      }
      return RoutingResult.unresolvable(String.join("; ", excludedReasons));
    }

    String bestWorkerId =
        scores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow();
    double bestScore = scores.get(bestWorkerId);

    return RoutingResult.assigned(
        bestWorkerId,
        "composable score %.3f from %d providers".formatted(bestScore, weights.size()));
  }

  private Map<String, Double> resolveWeights(
      AgentRoutingContext context, Set<String> discoveredProviders) {
    Map<String, Double> perCase = context.routingSignalWeights();
    if (perCase != null && !perCase.isEmpty()) {
      return perCase;
    }
    Map<String, Double> equal = new LinkedHashMap<>();
    double w = discoveredProviders.isEmpty() ? 1.0 : 1.0 / discoveredProviders.size();
    for (String id : discoveredProviders) {
      equal.put(id, w);
    }
    return equal;
  }
}
