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
package io.casehub.engine.internal.engine.handler;

import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.EscalationContext;
import io.casehub.api.spi.EscalationDecision;
import io.casehub.api.spi.JudgmentEscalator;
import io.casehub.api.spi.JudgmentVerifier;
import io.casehub.api.spi.VerificationContext;
import io.casehub.api.spi.VerificationResult;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.JudgmentResponseEvent;
// EventLog writing deferred to migration batch — requires CaseInstanceCache integration
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PendingJudgment;
import io.casehub.platform.api.routing.StrategyResolver;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JudgmentResponseHandler {

  private static final Logger LOG = Logger.getLogger(JudgmentResponseHandler.class);
  private static final int DEFAULT_MAX_ESCALATION_ATTEMPTS = 3;

  @Inject StrategyResolver strategyResolver;
  @Inject JudgmentScheduler judgmentScheduler;

  // EventLogRepository injection deferred to migration batch

  @RunOnVirtualThread
  @ConsumeEvent(value = EventBusAddresses.JUDGMENT_RESPONSE)
  public void onJudgmentResponse(final JudgmentResponseEvent event) {
    LOG.infof(
        "Processing judgment response: caseId=%s binding=%s judgmentId=%d caller=%s",
        event.caseId(),
        event.bindingName(),
        event.judgmentId(),
        event.response().callerIdentity().callerId());

    try {
      processResponse(event);
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to process judgment response for caseId=%s binding=%s",
          event.caseId(),
          event.bindingName());
    }
  }

  private void processResponse(final JudgmentResponseEvent event) {
    // TODO: look up PendingJudgment and JudgmentTarget from CaseInstance
    // For now, this handler establishes the event bus contract and verification pipeline.
    // Full wiring to CaseInstance.pendingJudgments requires CaseInstanceCache integration
    // which is part of the migration batch.

    LOG.infof(
        "Judgment response received for caseId=%s binding=%s — "
            + "verification and completion wiring deferred to migration batch",
        event.caseId(), event.bindingName());
  }

  void verifyAndApply(
      final JudgmentResponseEvent event,
      final JudgmentTarget target,
      final PendingJudgment pending,
      final int attemptCount) {

    final VerificationContext verificationCtx =
        new VerificationContext(
            event.caseId(),
            event.tenancyId(),
            event.bindingName(),
            target,
            null,
            pending.payload() instanceof JudgmentPayload.BindingPayload ? "binding" : "gate");

    final JudgmentVerifier verifier =
        strategyResolver.resolve(JudgmentVerifier.class, target.verifierStrategy());

    final VerificationResult result = verifier.verify(event.response(), verificationCtx);

    if (result instanceof VerificationResult.Accepted) {
      applyAccepted(event, target, pending);
    } else {
      handleVerificationFailure(event, target, pending, result, attemptCount);
    }
  }

  private void applyAccepted(
      final JudgmentResponseEvent event,
      final JudgmentTarget target,
      final PendingJudgment pending) {

    switch (pending.payload()) {
      case JudgmentPayload.BindingPayload bp -> {
        LOG.infof(
            "Judgment accepted (binding): caseId=%s binding=%s — applying output mapping",
            event.caseId(), event.bindingName());
        // Binding origin: apply outputMapping(response.decision) to context → CONTEXT_CHANGED
        // Full implementation requires CaseInstance access — wired in migration batch
      }
      case JudgmentPayload.GatePayload gp -> {
        LOG.infof(
            "Judgment accepted (gate): caseId=%s binding=%s — re-firing deferred output",
            event.caseId(), event.bindingName());
        // Gate origin: clear PendingJudgment, re-fire WorkflowExecutionCompleted.approved()
        // with gp.deferredOutput() — same as current ActionGateApprovedHandler
      }
    }
  }

  private void handleVerificationFailure(
      final JudgmentResponseEvent event,
      final JudgmentTarget target,
      final PendingJudgment pending,
      final VerificationResult result,
      final int attemptCount) {

    final JudgmentEscalator escalator =
        strategyResolver.resolve(JudgmentEscalator.class, target.escalatorStrategy());

    final EscalationContext escCtx =
        new EscalationContext(
            event.caseId(),
            event.tenancyId(),
            event.bindingName(),
            target,
            event.response(),
            result,
            attemptCount,
            DEFAULT_MAX_ESCALATION_ATTEMPTS);

    final EscalationDecision decision = escalator.escalate(escCtx);

    switch (decision) {
      case EscalationDecision.ReYield ry -> {
        LOG.infof(
            "Judgment re-yielded: caseId=%s binding=%s feedback='%s'",
            event.caseId(), event.bindingName(), ry.feedback());
        // Re-submit to JudgmentScheduler with feedback — requires rebuilding JudgmentRequest
      }
      case EscalationDecision.Escalate esc -> {
        LOG.infof(
            "Judgment escalated: caseId=%s binding=%s reason='%s'",
            event.caseId(), event.bindingName(), esc.reason());
        // Re-submit with overridden CallerConfig
      }
      case EscalationDecision.Fault f -> {
        LOG.warnf(
            "Judgment faulted: caseId=%s binding=%s reason='%s'",
            event.caseId(), event.bindingName(), f.reason());

        switch (pending.payload()) {
          case JudgmentPayload.BindingPayload bp -> {
            // Binding origin: fault the PlanItem
          }
          case JudgmentPayload.GatePayload gp -> {
            // Gate origin: write judgmentRejected signal, call workerStatusListener,
            // record routing outcome, trigger RecoveryCoordinator
          }
        }
      }
    }
  }
}
