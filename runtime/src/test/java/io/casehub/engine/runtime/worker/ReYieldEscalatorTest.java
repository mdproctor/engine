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
package io.casehub.engine.internal.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.judgment.EscalationContext;
import io.casehub.api.spi.judgment.EscalationDecision;
import io.casehub.api.spi.judgment.VerificationResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReYieldEscalatorTest {

  @Test
  void underMax_returnsReYield() {
    var escalator = new ReYieldEscalator();
    var ctx =
        buildContext(
            1,
            3,
            new VerificationResult.InsufficientEvidence(
                "missing rationale", java.util.List.of("rationale")));
    EscalationDecision decision = escalator.escalate(ctx);
    assertThat(decision).isInstanceOf(EscalationDecision.ReYield.class);
    assertThat(((EscalationDecision.ReYield) decision).feedback()).contains("missing rationale");
  }

  @Test
  void atMax_returnsFault() {
    var escalator = new ReYieldEscalator();
    var ctx =
        buildContext(
            3,
            3,
            new VerificationResult.InsufficientEvidence(
                "missing rationale", java.util.List.of("rationale")));
    EscalationDecision decision = escalator.escalate(ctx);
    assertThat(decision).isInstanceOf(EscalationDecision.Fault.class);
    assertThat(((EscalationDecision.Fault) decision).reason()).contains("Max escalations reached");
  }

  @Test
  void trustTooLow_returnsReYieldWithLevel() {
    var escalator = new ReYieldEscalator();
    var ctx = buildContext(0, 3, new VerificationResult.TrustTooLow("senior", "junior"));
    EscalationDecision decision = escalator.escalate(ctx);
    assertThat(decision).isInstanceOf(EscalationDecision.ReYield.class);
    assertThat(((EscalationDecision.ReYield) decision).feedback()).contains("senior");
  }

  @Test
  void idIsReYield() {
    assertThat(new ReYieldEscalator().id()).isEqualTo("re-yield");
  }

  private EscalationContext buildContext(
      int escalationCount, int maxEscalations, VerificationResult result) {
    return new EscalationContext(
        UUID.randomUUID(),
        "test-tenant",
        "review-binding",
        JudgmentTarget.builder().prompt("test").build(),
        "reject",
        java.util.List.of(),
        result,
        escalationCount,
        maxEscalations,
        CaseDefinition.builder().namespace("test").name("test-case").version("1.0.0").build(),
        null,
        null);
  }
}
