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

import io.casehub.api.spi.judgment.EscalationContext;
import io.casehub.api.spi.judgment.EscalationDecision;
import io.casehub.api.spi.judgment.JudgmentEscalator;
import io.casehub.api.spi.judgment.VerificationResult;

public class ReYieldEscalator implements JudgmentEscalator {

  @Override
  public EscalationDecision escalate(EscalationContext ctx) {
    if (ctx.escalationCount() < ctx.maxEscalations()) {
      String feedback =
          switch (ctx.verificationResult()) {
            case VerificationResult.InsufficientEvidence ie -> ie.feedback();
            case VerificationResult.TrustTooLow ttl ->
                "Trust level too low: " + ttl.requiredLevel();
            default -> "Verification failed";
          };
      return new EscalationDecision.ReYield(feedback);
    }
    return new EscalationDecision.Fault("Max escalations reached (" + ctx.maxEscalations() + ")");
  }

  @Override
  public String id() {
    return "re-yield";
  }
}
