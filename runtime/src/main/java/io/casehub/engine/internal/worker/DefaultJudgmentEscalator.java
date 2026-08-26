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

import io.casehub.api.spi.EscalationContext;
import io.casehub.api.spi.EscalationDecision;
import io.casehub.api.spi.JudgmentEscalator;
import io.casehub.api.spi.VerificationResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class DefaultJudgmentEscalator implements JudgmentEscalator {

  @Override
  public String id() {
    return "default";
  }

  @Override
  public EscalationDecision escalate(EscalationContext context) {
    if (context.attemptCount() >= context.maxAttempts()) {
      return new EscalationDecision.Fault(
          "Max escalation attempts reached (" + context.maxAttempts() + ")");
    }

    if (context.verificationResult() instanceof VerificationResult.InsufficientEvidence ie) {
      return new EscalationDecision.ReYield(
          "Please provide the missing evidence: " + String.join(", ", ie.missingRequirements()));
    }

    if (context.verificationResult() instanceof VerificationResult.TrustTooLow ttl) {
      return new EscalationDecision.Escalate(
          null, "Caller trust " + ttl.actual() + " below required " + ttl.required());
    }

    if (context.verificationResult() instanceof VerificationResult.Rejected r) {
      return new EscalationDecision.Fault("Judgment rejected: " + r.reason());
    }

    return new EscalationDecision.Fault("Unrecognized verification failure");
  }
}
