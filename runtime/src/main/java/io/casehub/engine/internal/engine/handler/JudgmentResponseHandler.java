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
import io.casehub.api.model.event.CaseHubEventType;
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
  @Inject io.vertx.mutiny.core.eventbus.EventBus eventBus;

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

    verifyAndApply(instance, event, target, pending, 1);
  }

  private static boolean isTerminal(final io.casehub.api.model.CaseStatus state) {
    return state == io.casehub.api.model.CaseStatus.COMPLETED
        || state == io.casehub.api.model.CaseStatus.FAULTED
        || state == io.casehub.api.model.CaseStatus.CANCELLED;
  }

  void verifyAndApply(
      final CaseInstance instance,
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

    writeEventLog(instance, event, CaseHubEventType.JUDGMENT_VERIFIED);

    if (result instanceof VerificationResult.Accepted) {
      applyAccepted(instance, event, target, pending);
    } else {
      handleVerificationFailure(instance, event, target, pending, result, attemptCount);
    }
  }

  private void applyAccepted(
      final CaseInstance instance,
      final JudgmentResponseEvent event,
      final JudgmentTarget target,
      final PendingJudgment pending) {

    writeEventLog(instance, event, CaseHubEventType.JUDGMENT_RESPONDED);
    instance.clearPendingJudgment(event.bindingName());

    switch (pending.payload()) {
      case JudgmentPayload.BindingPayload bp -> {
        LOG.infof(
            "Judgment accepted (binding): caseId=%s binding=%s — applying output mapping",
            event.caseId(), event.bindingName());

        if (target.outputMapping() != null && event.response().decision() != null) {
          @SuppressWarnings("unchecked")
          var decisionMap =
              event.response().decision() instanceof java.util.Map
                  ? (java.util.Map<String, Object>) event.response().decision()
                  : java.util.Map.of("decision", event.response().decision());
          decisionMap.forEach((k, v) -> instance.getCaseContext().set(k, v));
        }

        eventBus.publish(
            EventBusAddresses.CONTEXT_CHANGED,
            new io.casehub.engine.common.internal.event.CaseContextChangedEvent(
                instance, instance.getCaseContext(), null));
      }
      case JudgmentPayload.GatePayload gp -> {
        LOG.infof(
            "Judgment accepted (gate): caseId=%s binding=%s — re-firing deferred output",
            event.caseId(), event.bindingName());

        instance
            .getCaseContext()
            .set(
                "judgmentApproved",
                java.util.Map.of(
                    "gateId", gp.gateId(),
                    "approvedBy", event.response().callerIdentity().callerId()));

        var pendingGate = instance.getPendingActionGate();
        if (pendingGate != null && pendingGate.gateId() == gp.gateId()) {
          instance.setPendingActionGate(null);
          var worker = findWorker(instance, pendingGate.workerId());
          if (worker != null) {
            eventBus.publish(
                EventBusAddresses.WORKER_EXECUTION_FINISHED,
                io.casehub.engine.common.internal.event.WorkflowExecutionCompleted.approved(
                    instance,
                    worker,
                    pendingGate.idempotency(),
                    pendingGate.deferredOutput(),
                    pendingGate.bindingName()));
          }
        }
      }
    }
  }

  private io.casehub.worker.api.Worker findWorker(
      final CaseInstance instance, final String workerId) {
    var def = caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (def == null) return null;
    return def.getWorkers().stream()
        .filter(w -> w.name().equals(workerId))
        .findFirst()
        .orElse(null);
  }

  private void writeEventLog(
      final CaseInstance instance,
      final JudgmentResponseEvent event,
      final CaseHubEventType eventType) {
    var log = new io.casehub.engine.common.internal.history.EventLog();
    log.setCaseId(event.caseId());
    log.setStreamType(io.casehub.api.model.event.EventStreamType.CASE);
    log.setTimestamp(java.time.Instant.now());
    log.setEventType(eventType);
    eventLogRepository.append(log, event.tenancyId());
  }

  private void handleVerificationFailure(
      final CaseInstance instance,
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

        writeEventLog(instance, event, CaseHubEventType.JUDGMENT_REJECTED);
        instance.clearPendingJudgment(event.bindingName());

        switch (pending.payload()) {
          case JudgmentPayload.BindingPayload bp -> {
            eventBus.publish(
                EventBusAddresses.WORKER_OUTCOME_RESOLVED,
                new io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent(
                    instance,
                    null,
                    event.bindingName(),
                    null,
                    io.casehub.engine.common.internal.event.OutcomeDisposition.FAULT,
                    null));
          }
          case JudgmentPayload.GatePayload gp -> {
            instance
                .getCaseContext()
                .set(
                    "judgmentRejected",
                    java.util.Map.of(
                        "gateId", gp.gateId(),
                        "reason", f.reason()));
            eventBus.publish(
                EventBusAddresses.CONTEXT_CHANGED,
                new io.casehub.engine.common.internal.event.CaseContextChangedEvent(
                    instance, instance.getCaseContext(), null));
          }
        }
      }
    }
  }
}
