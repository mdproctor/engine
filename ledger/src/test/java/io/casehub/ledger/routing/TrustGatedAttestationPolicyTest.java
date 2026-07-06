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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.casehub.qhorus.api.spi.CommitmentContext;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustGatedAttestationPolicyTest {

  private TrustScoreSource source;
  private TrustRoutingPolicyProvider policyProvider;
  private TrustGatedAttestationPolicy policy;

  // threshold=0.7, minimumObservations=10, borderlineMargin=0.1
  private static final TrustRoutingPolicy ROUTING_POLICY =
      new TrustRoutingPolicy(0.7, 10, 0.1, 0.6, Map.of(), false, null);
  private static final String CAP = "security-review";
  private static final String ACTOR = "claude:reviewer@v1";
  private static final CommitmentContext CTX =
      new CommitmentContext(
          UUID.randomUUID().toString(), UUID.randomUUID(), "test-channel", UUID.randomUUID(), CAP);

  @BeforeEach
  void setUp() {
    source = mock(TrustScoreSource.class);
    policyProvider = mock(TrustRoutingPolicyProvider.class);
    policy = new TrustGatedAttestationPolicy(source, policyProvider);

    when(policyProvider.forCapability(any())).thenReturn(ROUTING_POLICY);
    when(source.capabilityScore(any(), any())).thenReturn(OptionalDouble.empty());
    when(source.decisionCount(any(), any())).thenReturn(0);
  }

  // ---- DONE + BOOTSTRAP ----

  @Test
  void done_bootstrap_noHistory_soundAtBaseConfidence() {
    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
    assertThat(result.get().confidence()).isEqualTo(0.7);
    assertThat(result.get().attestorId()).isEqualTo(ACTOR);
    assertThat(result.get().attestorType()).isEqualTo(ActorType.AGENT);
  }

  @Test
  void done_bootstrap_belowMinObservations_soundAtBaseConfidence() {
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.9));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(5); // below 10

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  // ---- DONE + QUALIFIED ----

  @Test
  void done_qualified_highTrust_soundAtBoostedConfidence() {
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.9));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
    // 0.7 * (1.0 + (0.9 - 0.7)) = 0.7 * 1.2 = 0.84
    assertThat(result.get().confidence()).isCloseTo(0.84, within(0.001));
  }

  @Test
  void done_qualified_justPastBorderline_soundAtModestBoost() {
    // 0.81 with threshold=0.7, margin=0.1: isBorderline(0.81) = |0.81-0.7|=0.11 > 0.1 = false
    // passesThresholdCheck(0.81) = 0.81 >= 0.7 && !isBorderline = true → QUALIFIED
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.81));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    // 0.7 * (1.0 + (0.81 - 0.7)) = 0.7 * 1.11 = 0.777
    assertThat(result.get().confidence()).isCloseTo(0.777, within(0.001));
  }

  @Test
  void done_qualified_cappedAtOne() {
    // Score 1.0: 0.7 * (1.0 + (1.0 - 0.7)) = 0.7 * 1.3 = 0.91 — below cap
    // Score with very high trust: policy threshold 0.1 → 0.7 * (1.0 + 0.89) = 1.323 → capped
    TrustRoutingPolicy lowThreshold =
        new TrustRoutingPolicy(0.1, 10, 0.01, 0.6, Map.of(), false, null);
    when(policyProvider.forCapability(CAP)).thenReturn(lowThreshold);
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.99));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(1.0);
  }

  // ---- DONE + BORDERLINE ----

  @Test
  void done_borderlineAbove_soundAtBaseConfidence() {
    // 0.75 with threshold=0.7, margin=0.1: |0.75-0.7|=0.05 <= 0.1 → borderline
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.75));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  @Test
  void done_borderlineBelow_soundAtBaseConfidence() {
    // 0.65 with threshold=0.7, margin=0.1: |0.65-0.7|=0.05 <= 0.1 → borderline
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.65));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  // ---- DONE + BELOW_THRESHOLD ----

  @Test
  void done_belowThreshold_soundAtScaledConfidence() {
    // 0.5 with threshold=0.7, margin=0.1: |0.5-0.7|=0.2 > 0.1 → not borderline
    // 0.5 < 0.7 → not passesThresholdCheck → BELOW_THRESHOLD
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.5));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
    // max(0.05, 0.7 * 0.5) = max(0.05, 0.35) = 0.35
    assertThat(result.get().confidence()).isCloseTo(0.35, within(0.001));
  }

  @Test
  void done_belowThreshold_nearZero_flooredAtMinConfidence() {
    when(source.capabilityScore(ACTOR, CAP)).thenReturn(OptionalDouble.of(0.01));
    when(source.decisionCount(ACTOR, CAP)).thenReturn(15);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, CTX);

    assertThat(result).isPresent();
    // max(0.05, 0.7 * 0.01) = max(0.05, 0.007) = 0.05
    assertThat(result.get().confidence()).isEqualTo(0.05);
  }

  // ---- DONE + fallback (null/empty/GLOBAL capabilityTag) ----

  @Test
  void done_nullContext_soundAtBaseConfidence() {
    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, null);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
    assertThat(result.get().confidence()).isEqualTo(0.7);
    assertThat(result.get().attestorId()).isEqualTo(ACTOR);
    assertThat(result.get().attestorType()).isEqualTo(ActorType.AGENT);
  }

  @Test
  void done_nullCapabilityTag_soundAtBaseConfidence() {
    CommitmentContext nullCapCtx =
        new CommitmentContext(
            UUID.randomUUID().toString(), UUID.randomUUID(), "ch", UUID.randomUUID(), null);

    Optional<AttestationOutcome> result =
        policy.attestationFor(MessageType.DONE, ACTOR, nullCapCtx);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  @Test
  void done_emptyCapabilityTag_soundAtBaseConfidence() {
    CommitmentContext emptyCapCtx =
        new CommitmentContext(
            UUID.randomUUID().toString(), UUID.randomUUID(), "ch", UUID.randomUUID(), "");

    Optional<AttestationOutcome> result =
        policy.attestationFor(MessageType.DONE, ACTOR, emptyCapCtx);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  @Test
  void done_globalCapabilityTag_soundAtBaseConfidence() {
    CommitmentContext globalCtx =
        new CommitmentContext(
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            "ch",
            UUID.randomUUID(),
            CapabilityTag.GLOBAL);

    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, ACTOR, globalCtx);

    assertThat(result).isPresent();
    assertThat(result.get().confidence()).isEqualTo(0.7);
  }

  // ---- FAILURE ----

  @Test
  void failure_flaggedAtBaseConfidence() {
    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.FAILURE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
    assertThat(result.get().confidence()).isEqualTo(0.6);
    assertThat(result.get().attestorId()).isEqualTo("system");
    assertThat(result.get().attestorType()).isEqualTo(ActorType.SYSTEM);
  }

  // ---- DECLINE ----

  @Test
  void decline_flaggedAtBaseConfidence() {
    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DECLINE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
    assertThat(result.get().confidence()).isEqualTo(0.4);
    assertThat(result.get().attestorId()).isEqualTo("system");
    assertThat(result.get().attestorType()).isEqualTo(ActorType.SYSTEM);
  }

  // ---- RESPONSE (wrong vocabulary) ----

  @Test
  void response_flaggedAtLowConfidence() {
    Optional<AttestationOutcome> result = policy.attestationFor(MessageType.RESPONSE, ACTOR, CTX);

    assertThat(result).isPresent();
    assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
    assertThat(result.get().confidence()).isEqualTo(0.3);
    assertThat(result.get().attestorId()).isEqualTo("system");
    assertThat(result.get().attestorType()).isEqualTo(ActorType.SYSTEM);
  }

  // ---- Non-discharge types ----

  @Test
  void query_returnsEmpty() {
    assertThat(policy.attestationFor(MessageType.QUERY, ACTOR, CTX)).isEmpty();
  }

  @Test
  void status_returnsEmpty() {
    assertThat(policy.attestationFor(MessageType.STATUS, ACTOR, CTX)).isEmpty();
  }

  @Test
  void command_returnsEmpty() {
    assertThat(policy.attestationFor(MessageType.COMMAND, ACTOR, CTX)).isEmpty();
  }

  @Test
  void event_returnsEmpty() {
    assertThat(policy.attestationFor(MessageType.EVENT, ACTOR, CTX)).isEmpty();
  }

  @Test
  void handoff_returnsEmpty() {
    assertThat(policy.attestationFor(MessageType.HANDOFF, ACTOR, CTX)).isEmpty();
  }
}
