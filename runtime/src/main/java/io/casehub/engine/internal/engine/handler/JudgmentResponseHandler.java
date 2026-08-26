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
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PendingJudgment;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
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
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject EventLogRepository eventLogRepository;

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
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for judgment response: caseId=%s — discarding",
          event.caseId());
      return;
    }

    if (isTerminal(instance.getState())) {
      LOG.warnf(
          "Judgment response on terminated case (state=%s): caseId=%s — discarding",
          instance.getState(), event.caseId());
      instance.clearPendingJudgment(event.bindingName());
      return;
    }

    final PendingJudgment pending = instance.getPendingJudgment(event.bindingName());
    if (pending == null) {
      LOG.warnf(
          "No PendingJudgment for binding '%s' caseId=%s — discarding",
          event.bindingName(), event.caseId());
      return;
    }

    if (pending.judgmentId() != event.judgmentId()) {
      LOG.warnf(
          "PendingJudgment ID mismatch: caseId=%s binding=%s expected=%d got=%d — discarding",
          event.caseId(), event.bindingName(), pending.judgmentId(), event.judgmentId());
      return;
    }

    final var def = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (def == null) {
      LOG.errorf(
          "CaseDefinition not found for caseId=%s — cannot resolve JudgmentTarget", event.caseId());
      return;
    }

    final var binding =
        def.getBindings().stream()
            .filter(b -> b.getName().equals(event.bindingName()))
            .findFirst()
            .orElse(null);

    JudgmentTarget target = null;
    if (binding != null && binding.target() instanceof JudgmentTarget jt) {
      target = jt;
    }
    if (target == null && "__gate__".equals(event.bindingName())) {
      target = JudgmentTarget.forHuman().prompt("gate").build();
    }
    if (target == null) {
      LOG.errorf(
          "JudgmentTarget not found for binding '%s' caseId=%s — discarding",
          event.bindingName(), event.caseId());
      return;
    }

    verifyAndApply(event, target, pending, 1);
  }

  private static boolean isTerminal(final io.casehub.api.model.CaseStatus state) {
    return state == io.casehub.api.model.CaseStatus.COMPLETED
        || state == io.casehub.api.model.CaseStatus.FAULTED
        || state == io.casehub.api.model.CaseStatus.CANCELLED;
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
        // Full implementation requires WritableLayer access — future commit
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
      }
      case EscalationDecision.Escalate esc -> {
        LOG.infof(
            "Judgment escalated: caseId=%s binding=%s reason='%s'",
            event.caseId(), event.bindingName(), esc.reason());
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
            // Gate origin: write judgmentRejected signal, trigger recovery
          }
        }
      }
    }
  }
}
