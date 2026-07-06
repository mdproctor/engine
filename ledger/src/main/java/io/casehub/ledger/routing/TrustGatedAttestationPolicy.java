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

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalDouble;

@Alternative
@Priority(1)
@ApplicationScoped
public class TrustGatedAttestationPolicy implements CommitmentAttestationPolicy {

  static final double BASE_DONE_CONFIDENCE = 0.7;
  static final double BASE_FAILURE_CONFIDENCE = 0.6;
  static final double BASE_DECLINE_CONFIDENCE = 0.4;
  static final double BASE_RESPONSE_CONFIDENCE = 0.3;
  static final double MIN_CONFIDENCE_FLOOR = 0.05;

  private final TrustScoreSource source;
  private final TrustRoutingPolicyProvider policyProvider;

  @Inject
  public TrustGatedAttestationPolicy(
      final TrustScoreSource source, final TrustRoutingPolicyProvider policyProvider) {
    this.source = source;
    this.policyProvider = policyProvider;
  }

  @Override
  public Optional<AttestationOutcome> attestationFor(
      final MessageType terminalType,
      final String resolvedActorId,
      final CommitmentContext context) {

    return switch (terminalType) {
      case DONE -> Optional.of(attestDone(resolvedActorId, context));
      case FAILURE ->
          Optional.of(
              new AttestationOutcome(
                  AttestationVerdict.FLAGGED, BASE_FAILURE_CONFIDENCE, "system", ActorType.SYSTEM));
      case DECLINE ->
          Optional.of(
              new AttestationOutcome(
                  AttestationVerdict.FLAGGED, BASE_DECLINE_CONFIDENCE, "system", ActorType.SYSTEM));
      case RESPONSE ->
          Optional.of(
              new AttestationOutcome(
                  AttestationVerdict.FLAGGED,
                  BASE_RESPONSE_CONFIDENCE,
                  "system",
                  ActorType.SYSTEM));
      default -> Optional.empty();
    };
  }

  private AttestationOutcome attestDone(
      final String resolvedActorId, final CommitmentContext context) {
    if (context == null || !hasCapabilityTag(context)) {
      return soundAtConfidence(resolvedActorId, BASE_DONE_CONFIDENCE);
    }

    final String capabilityTag = context.capabilityTag();
    final OptionalDouble capScore = source.capabilityScore(resolvedActorId, capabilityTag);
    final int decCount = source.decisionCount(resolvedActorId, capabilityTag);
    final TrustRoutingPolicy routingPolicy = policyProvider.forCapability(capabilityTag);

    if (capScore.isEmpty() || routingPolicy.isBootstrap(decCount)) {
      return soundAtConfidence(resolvedActorId, BASE_DONE_CONFIDENCE);
    }

    final double score = capScore.getAsDouble();

    if (routingPolicy.isBorderline(score)) {
      return soundAtConfidence(resolvedActorId, BASE_DONE_CONFIDENCE);
    }

    if (routingPolicy.passesThresholdCheck(score)) {
      final double boosted = BASE_DONE_CONFIDENCE * (1.0 + (score - routingPolicy.threshold()));
      return soundAtConfidence(resolvedActorId, Math.min(1.0, boosted));
    }

    // BELOW_THRESHOLD
    final double scaled = BASE_DONE_CONFIDENCE * score;
    return soundAtConfidence(resolvedActorId, Math.max(MIN_CONFIDENCE_FLOOR, scaled));
  }

  private static boolean hasCapabilityTag(final CommitmentContext context) {
    final String tag = context.capabilityTag();
    return tag != null && !tag.isEmpty() && !CapabilityTag.GLOBAL.equals(tag);
  }

  private static AttestationOutcome soundAtConfidence(
      final String actorId, final double confidence) {
    return new AttestationOutcome(AttestationVerdict.SOUND, confidence, actorId, ActorType.AGENT);
  }
}
