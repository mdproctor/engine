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
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingSignal;
import io.casehub.api.spi.routing.RoutingSignalProvider;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier.ClassifiedCandidate;
import io.casehub.ledger.routing.TrustCandidateClassifier.Phase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Trust-aware signal provider implementing the trust maturity model. Returns pure trust scores for
 * QUALIFIED candidates, neutral scores for BOOTSTRAP, and Exclude/Escalate for
 * BORDERLINE/EXCLUDED. Workload blending is handled by the compositor via {@code
 * WorkloadSignalProvider}.
 */
@ApplicationScoped
public class TrustSignalProvider implements RoutingSignalProvider {

  private final TrustCandidateClassifier classifier;
  private final TrustScoreSource source;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustSignalProvider(
      TrustCandidateClassifier classifier,
      TrustScoreSource source,
      TrustRoutingPolicyProvider policyProvider) {
    this.classifier = classifier;
    this.source = source;
    this.policyProvider = policyProvider;
  }

  @Override
  public String id() {
    return "trust";
  }

  @Override
  public @Nullable RoutingSignal evaluate(
      AgentRoutingContext context, List<AgentCandidate> eligible) {
    if (eligible.isEmpty()) {
      return null;
    }

    TrustRoutingPolicy policy = policyProvider.forCapability(context.capabilityName());
    List<ClassifiedCandidate> classified =
        classifier.classify(eligible, context.capabilityName(), policy, source);

    boolean hasQualified = classified.stream().anyMatch(c -> c.phase() == Phase.QUALIFIED);

    var signals = new LinkedHashMap<String, RoutingSignal.CandidateSignal>();
    for (ClassifiedCandidate cc : classified) {
      String workerId = cc.candidate().workerId();
      signals.put(workerId, toSignal(cc, policy, hasQualified, context.capabilityName()));
    }

    return new RoutingSignal(signals);
  }

  private RoutingSignal.CandidateSignal toSignal(
      ClassifiedCandidate cc,
      TrustRoutingPolicy policy,
      boolean hasQualified,
      String capabilityName) {
    return switch (cc.phase()) {
      case Phase.QUALIFIED -> {
        double trustScore = cc.trustScore().getAsDouble();
        yield new RoutingSignal.CandidateSignal.Score(
            trustScore, "trust %.2f (qualified)".formatted(trustScore));
      }
      case Phase.BOOTSTRAP -> {
        if (policy.bootstrapEscalationRequired() && !hasQualified) {
          yield new RoutingSignal.CandidateSignal.Escalate(
              EscalationReason.NO_QUALIFIED_AGENT,
              "bootstrap only — no qualified agents for capability '%s'"
                  .formatted(capabilityName));
        }
        if (policy.bootstrapEscalationRequired()) {
          yield new RoutingSignal.CandidateSignal.Exclude(
              "bootstrap excluded — qualified agents available");
        }
        yield new RoutingSignal.CandidateSignal.Score(0.5, "bootstrap (no trust data)");
      }
      case Phase.BORDERLINE ->
          new RoutingSignal.CandidateSignal.Escalate(
              EscalationReason.BORDERLINE_STALEMATE,
              "borderline trust for capability '%s'".formatted(capabilityName));
      case Phase.EXCLUDED_PHASE2B ->
          new RoutingSignal.CandidateSignal.Exclude("excluded (trust phase 2b)");
      case Phase.EXCLUDED_PHASE3 ->
          new RoutingSignal.CandidateSignal.Exclude("excluded (trust phase 3)");
    };
  }
}
