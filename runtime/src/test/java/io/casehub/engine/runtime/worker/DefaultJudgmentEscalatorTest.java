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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.judgment.CallerConfig;
import io.casehub.api.spi.judgment.EscalationContext;
import io.casehub.api.spi.judgment.EscalationDecision;
import io.casehub.api.spi.judgment.VerificationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultJudgmentEscalatorTest {

  private final DefaultJudgmentEscalator escalator = new DefaultJudgmentEscalator();

  @Test
  void idIsDefault() {
    assertEquals("default", escalator.id());
  }

  @Test
  void insufficientEvidenceReYields() {
    var ctx =
        buildContext(
            new VerificationResult.InsufficientEvidence("need docs", List.of("docs")), 0, 5);
    var decision = escalator.escalate(ctx);

    assertInstanceOf(EscalationDecision.ReYield.class, decision);
    assertEquals("need docs", ((EscalationDecision.ReYield) decision).feedback());
  }

  @Test
  void trustTooLowEscalates() {
    var ctx = buildContext(new VerificationResult.TrustTooLow("high", "medium"), 0, 5);
    var decision = escalator.escalate(ctx);

    assertInstanceOf(EscalationDecision.Escalate.class, decision);
    var esc = (EscalationDecision.Escalate) decision;
    assertInstanceOf(CallerConfig.Human.class, esc.newCallerConfig());
    assertTrue(esc.reason().contains("Trust level too low"));
  }

  @Test
  void rejectedFaults() {
    var ctx = buildContext(new VerificationResult.Rejected("invalid judgment"), 0, 5);
    var decision = escalator.escalate(ctx);

    assertInstanceOf(EscalationDecision.Fault.class, decision);
    assertTrue(((EscalationDecision.Fault) decision).reason().contains("rejected"));
  }

  @Test
  void maxEscalationsFaults() {
    var ctx =
        buildContext(new VerificationResult.InsufficientEvidence("need docs", List.of()), 5, 5);
    var decision = escalator.escalate(ctx);

    assertInstanceOf(EscalationDecision.Fault.class, decision);
    assertTrue(((EscalationDecision.Fault) decision).reason().contains("Max escalations"));
  }

  @Test
  void belowMaxEscalationsStillEscalates() {
    var ctx = buildContext(new VerificationResult.TrustTooLow("high", "low"), 4, 5);
    var decision = escalator.escalate(ctx);

    assertInstanceOf(EscalationDecision.Escalate.class, decision);
  }

  private static EscalationContext buildContext(
      VerificationResult result, int escalationCount, int maxEscalations) {
    return new EscalationContext(
        UUID.randomUUID(),
        "tenant-1",
        "review-binding",
        JudgmentTarget.builder().prompt("Review this").build(),
        "approve",
        List.of(),
        result,
        escalationCount,
        maxEscalations,
        null,
        null,
        null);
  }
}
