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

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.JudgmentResponse;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.EscalationContext;
import io.casehub.api.spi.EscalationDecision;
import io.casehub.api.spi.VerificationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultJudgmentEscalatorTest {

  private final DefaultJudgmentEscalator escalator = new DefaultJudgmentEscalator();

  @Test
  void reYieldsOnInsufficientEvidence() {
    var ctx =
        ctx(new VerificationResult.InsufficientEvidence("missing", List.of("rationale")), 1, 3);
    var result = escalator.escalate(ctx);
    assertInstanceOf(EscalationDecision.ReYield.class, result);
    assertTrue(((EscalationDecision.ReYield) result).feedback().contains("rationale"));
  }

  @Test
  void escalatesOnTrustTooLow() {
    var ctx = ctx(new VerificationResult.TrustTooLow(0.8, 0.3), 1, 3);
    var result = escalator.escalate(ctx);
    assertInstanceOf(EscalationDecision.Escalate.class, result);
    assertTrue(((EscalationDecision.Escalate) result).reason().contains("0.3"));
  }

  @Test
  void faultsOnRejection() {
    var ctx = ctx(new VerificationResult.Rejected("Invalid analysis"), 1, 3);
    var result = escalator.escalate(ctx);
    assertInstanceOf(EscalationDecision.Fault.class, result);
    assertTrue(((EscalationDecision.Fault) result).reason().contains("Invalid analysis"));
  }

  @Test
  void faultsWhenMaxAttemptsReached() {
    var ctx = ctx(new VerificationResult.InsufficientEvidence("missing", List.of("x")), 3, 3);
    var result = escalator.escalate(ctx);
    assertInstanceOf(EscalationDecision.Fault.class, result);
    assertTrue(((EscalationDecision.Fault) result).reason().contains("Max escalation"));
  }

  @Test
  void idIsDefault() {
    assertEquals("default", escalator.id());
  }

  private static EscalationContext ctx(VerificationResult vr, int attempt, int maxAttempts) {
    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var response =
        new JudgmentResponse(
            Map.of("d", "v"), List.of(), new CallerIdentity("u", "human", null), Instant.now());
    return new EscalationContext(
        UUID.randomUUID(), "t1", "b1", target, response, vr, attempt, maxAttempts);
  }
}
