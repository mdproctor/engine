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

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import io.casehub.ledger.routing.TrustCandidateClassifier.ScoredCandidate;
import io.smallrye.mutiny.Uni;
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
 * Trust-aware {@link ImplementationRoutingStrategy} implementing the four-phase trust maturity
 * model (trust-maturity-model.md). Activates automatically when {@code casehub-engine-ledger} is on
 * the classpath — no configuration required.
 *
 * <p>Symmetric to {@link TrustWeightedAgentStrategy} but operates on implementation bindings
 * instead of agent workers. Implementations have no workload (in-process execution), so workload
 * score is always 1.0 and blending is effectively trust-only.
 *
 * <h3>Scoring per phase</h3>
 *
 * <ul>
 *   <li><b>BOOTSTRAP</b> (Phase 0/1): workload score = 1.0 (no runningJobs). All bootstrap
 *       candidates score equally → returns {@link ImplementationSelection.RunAll}.
 *   <li><b>QUALIFIED</b> (Phase 2/3 passed): blended score = trust × blendFactor + 1.0 ×
 *       (1-blendFactor). Since workloadScore is always 1.0, the blend simplifies but we preserve
 *       the formula for consistency with {@link TrustWeightedAgentStrategy}.
 *   <li><b>BORDERLINE</b> (Phase 2a): score 0.0. Excluded from scoring.
 *   <li><b>EXCLUDED_PHASE2B/3</b>: score 0.0.
 * </ul>
 *
 * <h3>Decision mapping</h3>
 *
 * <ul>
 *   <li>{@link AgentAssignment.Assigned} → {@link ImplementationSelection.Selected} with the
 *       winning binding.
 *   <li>{@link AgentAssignment.Unresolvable} → backstop: {@link ImplementationSelection.Selected}
 *       with first candidate (declaration order).
 *   <li>{@link AgentAssignment.EscalateToOversight} → backstop: implementations can't escalate to
 *       humans, so select first candidate.
 * </ul>
 *
 * <p>All in-memory work — returns {@code Uni.createFrom().item(result)}.
 *
 * <p>Refs casehubio/engine#625.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class TrustWeightedImplementationRoutingStrategy implements ImplementationRoutingStrategy {

  private final TrustCandidateClassifier classifier;
  private final TrustScoreSource source;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustWeightedImplementationRoutingStrategy(
      final TrustCandidateClassifier classifier,
      final TrustScoreSource source,
      final TrustRoutingPolicyProvider policyProvider) {
    this.classifier = classifier;
    this.source = source;
    this.policyProvider = policyProvider;
  }

  @Override
  public Uni<ImplementationSelection> select(
      final ImplementationRoutingContext context, final List<ImplementationCandidate> candidates) {
    if (candidates.isEmpty() || candidates.size() == 1) {
      return Uni.createFrom().item(new ImplementationSelection.RunAll());
    }

    final TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());

    // Build lookup: workerName → ImplementationCandidate
    final Map<String, ImplementationCandidate> byWorker =
        candidates.stream()
            .collect(Collectors.toMap(ImplementationCandidate::workerName, c -> c, (a, b) -> a));

    // Adapt ImplementationCandidate → AgentCandidate
    final List<AgentCandidate> agentCandidates =
        candidates.stream()
            .map(
                c ->
                    new AgentCandidate(
                        c.workerName(),
                        Set.of(c.capabilityName()),
                        0, // no workload concept for in-process implementations
                        AgentHealth.READY,
                        null))
            .toList();

    // Classify candidates using trust maturity model
    final List<ClassifiedCandidate> classified =
        classifier.classify(agentCandidates, context.capabilityName(), policy, source);

    // Score each candidate (filter excluded)
    final List<ScoredCandidate> scored = new ArrayList<>(classified.size());
    for (final ClassifiedCandidate cc : classified) {
      scored.add(new ScoredCandidate(cc, score(cc, policy, policy.fallbackBinding(), byWorker)));
    }

    // When all candidates score equally AND positively (e.g., all BOOTSTRAP with score=1.0),
    // run all instead of arbitrarily picking. Excluded candidates (score=0.0) fall through to
    // backstop.
    final boolean allEqualPositiveScores =
        scored.size() > 1
            && scored.stream().mapToDouble(ScoredCandidate::finalScore).distinct().count() == 1
            && scored.stream().allMatch(sc -> sc.finalScore() > 0.0);
    if (allEqualPositiveScores) {
      return Uni.createFrom().item(new ImplementationSelection.RunAll());
    }

    // Get decision from classifier
    final AgentAssignment assignment =
        classifier.decide(classified, scored, context.capabilityName());

    // Map AgentAssignment → ImplementationSelection
    final ImplementationSelection selection =
        switch (assignment) {
          case AgentAssignment.Assigned a -> {
            final ImplementationCandidate winner = byWorker.get(a.workerId());
            yield new ImplementationSelection.Selected(List.of(winner.bindingName()));
          }
          case AgentAssignment.Unresolvable ignored ->
              new ImplementationSelection.Selected(List.of(resolveFallback(policy, candidates)));
          case AgentAssignment.EscalateToOversight ignored ->
              new ImplementationSelection.Selected(List.of(resolveFallback(policy, candidates)));
        };

    return Uni.createFrom().item(selection);
  }

  private double score(
      final ClassifiedCandidate cc,
      final TrustRoutingPolicy policy,
      final String fallbackBinding,
      final Map<String, ImplementationCandidate> byWorker) {
    final boolean isFallback =
        fallbackBinding != null
            && byWorker.containsKey(cc.candidate().workerId())
            && fallbackBinding.equals(byWorker.get(cc.candidate().workerId()).bindingName());
    return switch (cc.phase()) {
      case Phase.BOOTSTRAP -> cc.workloadScore(); // always 1.0
      case Phase.QUALIFIED -> {
        final double t = cc.trustScore().getAsDouble();
        yield t * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
      }
      case Phase.BORDERLINE -> isFallback ? 0.01 : 0.0;
      case Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 -> 0.0;
    };
  }

  private String resolveFallback(
      final TrustRoutingPolicy policy, final List<ImplementationCandidate> candidates) {
    if (policy.fallbackBinding() != null) {
      for (final ImplementationCandidate c : candidates) {
        if (policy.fallbackBinding().equals(c.bindingName())) {
          return c.bindingName();
        }
      }
    }
    return candidates.get(0).bindingName();
  }
}
