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
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
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
  private final TrustScoreCache cache;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustWeightedAgentStrategy(
      final TrustCandidateClassifier classifier,
      final TrustScoreCache cache,
      final TrustRoutingPolicyProvider policyProvider) {
    this.classifier = classifier;
    this.cache = cache;
    this.policyProvider = policyProvider;
  }

  @Override
  public Uni<AgentAssignment> select(
      final AgentRoutingContext context, final List<AgentCandidate> candidates) {
    if (candidates.isEmpty()) {
      return Uni.createFrom().item(AgentAssignment.unresolvable());
    }

    final TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());
    final List<ClassifiedCandidate> classified =
        classifier.classify(candidates, context.capabilityName(), policy, cache);

    final List<ScoredCandidate> scored = new ArrayList<>(classified.size());
    for (final ClassifiedCandidate cc : classified) {
      scored.add(new ScoredCandidate(cc, score(cc, policy)));
    }

    return Uni.createFrom().item(classifier.decide(classified, scored, context.capabilityName()));
  }

  private double score(final ClassifiedCandidate cc, final TrustRoutingPolicy policy) {
    return switch (cc.phase()) {
      case Phase.BOOTSTRAP -> cc.workloadScore();
      case Phase.QUALIFIED -> {
        final double t = cc.trustScore().getAsDouble();
        yield t * policy.blendFactor() + cc.workloadScore() * (1.0 - policy.blendFactor());
      }
      case Phase.BORDERLINE, Phase.EXCLUDED_PHASE2B, Phase.EXCLUDED_PHASE3 -> 0.0;
    };
  }
}
