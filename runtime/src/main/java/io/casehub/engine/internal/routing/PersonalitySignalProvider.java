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

import io.casehub.api.model.CognitiveDemand;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.DispositionHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Scores candidates by cognitive function alignment between the task's demand profile and the
 * agent's effective personality weights (base + accumulated activations from JPAF reinforcement).
 */
@ApplicationScoped
public class PersonalitySignalProvider implements RoutingSignalProvider {

  private static final Logger LOG = Logger.getLogger(PersonalitySignalProvider.class);
  static final String[] FUNCTIONS = {"Ti", "Te", "Fi", "Fe", "Si", "Se", "Ni", "Ne"};

  private final DispositionHealth dispositionHealth;

  @Inject
  public PersonalitySignalProvider(DispositionHealth dispositionHealth) {
    this.dispositionHealth = dispositionHealth;
  }

  @Override
  public String id() {
    return "personality";
  }

  @Override
  public @Nullable RoutingSignal evaluate(
      AgentRoutingContext context, List<AgentCandidate> eligible) {
    CognitiveDemand demand = context.cognitiveDemand();
    if (demand == null) return null;

    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    for (var candidate : eligible) {
      if (candidate.agentDescriptor() == null) continue;
      var disposition = candidate.agentDescriptor().disposition();
      if (disposition == null || disposition.dispositionProfile().isEmpty()) continue;

      var status =
          dispositionHealth.probe(
              candidate.agentDescriptor(),
              new CapabilityHealth.ProbeContext(context.capabilityName(), Map.of()));
      Map<String, Double> effectiveWeights = extractWeights(status);

      double similarity = cosineSimilarity(demand.functionWeights(), effectiveWeights);
      signals.put(
          candidate.workerId(),
          new RoutingSignal.CandidateSignal.Score(
              similarity, "personality alignment %.3f".formatted(similarity)));
    }

    return signals.isEmpty() ? null : new RoutingSignal(signals);
  }

  static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
    double dot = 0.0, normA = 0.0, normB = 0.0;
    for (String fn : FUNCTIONS) {
      double va = a.getOrDefault(fn, 0.0);
      double vb = b.getOrDefault(fn, 0.0);
      dot += va * vb;
      normA += va * va;
      normB += vb * vb;
    }
    double denom = Math.sqrt(normA) * Math.sqrt(normB);
    return denom == 0.0 ? 0.0 : dot / denom;
  }

  private Map<String, Double> extractWeights(DispositionHealth.DispositionStatus status) {
    return switch (status) {
      case DispositionHealth.DispositionStatus.Aligned a -> a.effectiveWeights();
      case DispositionHealth.DispositionStatus.Drifted d -> d.effectiveWeights();
      case DispositionHealth.DispositionStatus.EvolutionPending e -> e.effectiveWeights();
    };
  }
}
