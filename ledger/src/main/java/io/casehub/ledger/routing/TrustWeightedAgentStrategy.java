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
package io.casehub.ledger.routing;

import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.ExperienceAnalyser;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trust-aware {@link AgentRoutingStrategy} implementing the four-phase trust maturity model
 * (trust-maturity-model.md). Activates automatically when {@code casehub-engine-ledger} is on the
 * classpath — no configuration required.
 *
 * <h3>Scoring per phase</h3>
 *
 * <ul>
 *   <li><b>BOOTSTRAP</b> (Phase 0/1): availability score = 1/(1+runningJobs). Gastown parity.
 *   <li><b>QUALIFIED</b> (Phase 2/3 passed): blended score = trust × blendFactor + workload ×
 *       (1-blendFactor).
 *   <li><b>BORDERLINE</b> (Phase 2a): score 0.0. When ALL non-bootstrap candidates are borderline,
 *       returns {@link AgentAssignment.EscalateToOversight} per trust-maturity-model.md Phase 2.
 *   <li><b>EXCLUDED_PHASE2B/3</b>: score 0.0.
 * </ul>
 *
 * <h3>Mixed-pool policy</h3>
 *
 * A BOOTSTRAP candidate (positive availability score) always outscores a BORDERLINE candidate
 * (score 0.0). New agents with no track record are preferred over established agents with
 * borderline trust, and the bootstrap candidate is selected rather than escalating to oversight.
 *
 * <p>All in-memory work — returns {@code Uni.createFrom().item(result)}.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TrustWeightedAgentStrategy implements AgentRoutingStrategy {

  private final TrustCandidateClassifier classifier;
  private final TrustScoreSource source;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustWeightedAgentStrategy(
      final TrustCandidateClassifier classifier,
      final TrustScoreSource source,
      final TrustRoutingPolicyProvider policyProvider) {
    this.classifier = classifier;
    this.source = source;
    this.policyProvider = policyProvider;
  }

  @Override
  public String id() {
    return "trust-weighted";
  }

  @Override
  public RoutingResult select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return RoutingResult.unresolvable("no candidates available");
    }

    final TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());
    final List<ClassifiedCandidate> classified =
        classifier.classify(candidates, context.capabilityName(), policy, source);

    if (policy.bootstrapEscalationRequired()) {
      final boolean hasQualified = classified.stream().anyMatch(c -> c.phase() == Phase.QUALIFIED);
      final boolean hasBootstrap = classified.stream().anyMatch(c -> c.phase() == Phase.BOOTSTRAP);
      if (!hasQualified && hasBootstrap) {
        return RoutingResult.escalate(
            context.capabilityName(),
            EscalationReason.NO_QUALIFIED_AGENT,
            "bootstrap only — no qualified agents for capability '%s'"
                .formatted(context.capabilityName()));
      }
    }

    final List<ClassifiedCandidate> eligible =
        policy.bootstrapEscalationRequired()
            ? classified.stream().filter(c -> c.phase() != Phase.BOOTSTRAP).toList()
            : classified;

    final Map<String, Double> cbrScores;
    if (policy.cbrWeight() > 0.0 && !context.experiences().isEmpty()) {
      final Set<String> qualifiedWorkerIds =
          eligible.stream()
              .filter(c -> c.phase() == Phase.QUALIFIED)
              .map(c -> c.candidate().workerId())
              .collect(Collectors.toSet());
      cbrScores =
          ExperienceAnalyser.workerSuccessRates(
              context.experiences(),
              qualifiedWorkerIds,
              context.capabilityName(),
              ExperienceAnalyser.DEFAULT_OUTCOME_WEIGHTS);
    } else {
      cbrScores = Map.of();
    }

    final List<ScoredCandidate> scored = new ArrayList<>(eligible.size());
    for (final ClassifiedCandidate cc : eligible) {
      final double finalScore = score(cc, policy, cbrScores);
      scored.add(
          new ScoredCandidate(cc, finalScore, buildRationale(cc, finalScore, policy, cbrScores)));
    }

    return classifier.decide(eligible, scored, context.capabilityName());
  }

  private String buildRationale(
      final ClassifiedCandidate cc,
      final double finalScore,
      final TrustRoutingPolicy policy,
      final Map<String, Double> cbrScores) {
    final String workerId = cc.candidate().workerId();
    return switch (cc.phase()) {
      case Phase.BOOTSTRAP ->
          "selected %s: availability %.2f (bootstrap)".formatted(workerId, cc.workloadScore());
      case Phase.QUALIFIED -> {
        final double trustScore = cc.trustScore().getAsDouble();
        if (policy.cbrWeight() > 0.0 && cbrScores.containsKey(workerId)) {
          final double cbrBonus = cbrScores.get(workerId);
          yield "selected %s: trust %.2f, cbr_bonus %.2f, blended %.2f (threshold %.2f, cbrWeight %.2f)"
              .formatted(
                  workerId,
                  trustScore,
                  cbrBonus,
                  finalScore,
                  policy.threshold(),
                  policy.cbrWeight());
        }
        yield "selected %s: trust %.2f, blended %.2f (threshold %.2f)"
            .formatted(workerId, trustScore, finalScore, policy.threshold());
      }
      case Phase.BORDERLINE, Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 ->
          "excluded %s: phase %s".formatted(workerId, cc.phase());
    };
  }

  private double score(
      final ClassifiedCandidate cc,
      final TrustRoutingPolicy policy,
      final Map<String, Double> cbrScores) {
    return switch (cc.phase()) {
      case Phase.BOOTSTRAP -> cc.workloadScore();
      case Phase.QUALIFIED -> {
        final double t = cc.trustScore().getAsDouble();
        final double trustBlend =
            t * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
        if (policy.cbrWeight() > 0.0 && cbrScores.containsKey(cc.candidate().workerId())) {
          final double cbrBonus = cbrScores.get(cc.candidate().workerId());
          yield trustBlend * (1.0 - policy.cbrWeight()) + cbrBonus * policy.cbrWeight();
        }
        yield trustBlend;
      }
      case Phase.BORDERLINE, Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 -> 0.0;
    };
  }
}
