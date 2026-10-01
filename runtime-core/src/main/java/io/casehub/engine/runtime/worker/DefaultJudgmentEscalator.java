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

import io.casehub.api.spi.judgment.CallerConfig;
import io.casehub.api.spi.judgment.EscalationContext;
import io.casehub.api.spi.judgment.EscalationDecision;
import io.casehub.api.spi.judgment.JudgmentEscalator;
import io.casehub.api.spi.judgment.VerificationResult;

/**
 * Heuristic escalation strategy. Replaces {@link FaultEscalator} as the default.
 *
 * <ul>
 *   <li>Max escalations reached → Fault
 *   <li>InsufficientEvidence → ReYield with feedback
 *   <li>TrustTooLow → Escalate to Human with higher trust requirement
 *   <li>Rejected → Fault (explicit rejection is terminal)
 * </ul>
 *
 * <p>Refs engine#1011, engine#994.
 */
public class DefaultJudgmentEscalator implements JudgmentEscalator {

  @Override
  public EscalationDecision escalate(EscalationContext ctx) {
    if (ctx.escalationCount() >= ctx.maxEscalations()) {
      return new EscalationDecision.Fault("Max escalations reached (" + ctx.maxEscalations() + ")");
    }

    return switch (ctx.verificationResult()) {
      case VerificationResult.InsufficientEvidence ie ->
          new EscalationDecision.ReYield(ie.feedback());
      case VerificationResult.TrustTooLow ttl ->
          new EscalationDecision.Escalate(
              CallerConfig.human(),
              "Trust level too low — escalating to human with minimum trust: "
                  + ttl.requiredLevel());
      case VerificationResult.Rejected r ->
          new EscalationDecision.Fault("Judgment rejected: " + r.reason());
      case VerificationResult.Accepted a ->
          new EscalationDecision.Fault("Unexpected escalation on accepted result");
    };
  }

  @Override
  public String id() {
    return "default";
  }
}
