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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.JudgmentCompletedEvent;
import io.casehub.engine.common.internal.event.JudgmentEscalatedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class JudgmentCompletedHandler {

  private static final Logger LOG = Logger.getLogger(JudgmentCompletedHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject io.casehub.platform.api.routing.StrategyResolver strategyResolver;
  @Inject io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor judgmentNodeExecutor;

  @ConsumeEvent(value = EventBusAddresses.JUDGMENT_COMPLETED)
  @RunOnVirtualThread
  public void onJudgmentCompleted(final JudgmentCompletedEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for judgment completion: caseId=%s — discarding",
          event.caseId());
      return;
    }
    if (instance.getState().isTerminal()) {
      LOG.warnf(
          "Judgment response on terminated case (state=%s): caseId=%s — discarding",
          instance.getState(), event.caseId());
      return;
    }

    final CaseDefinition def =
        caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (def == null) {
      LOG.warnf(
          "CaseDefinition not found for caseId=%s — discarding judgment response", event.caseId());
      return;
    }

    Binding binding =
        def.getBindings().stream()
            .filter(b -> b.getName().equals(event.bindingName()))
            .findFirst()
            .orElse(null);

    if (binding != null && binding.target() instanceof JudgmentTarget jt) {
      if (jt.verifierStrategy() != null) {
        io.casehub.api.spi.judgment.JudgmentVerifier verifier =
            strategyResolver.resolve(
                io.casehub.api.spi.judgment.JudgmentVerifier.class, jt.verifierStrategy());
        var callerIdentity =
            event.response().callerId() != null && event.response().callerType() != null
                ? io.casehub.api.spi.judgment.CallerIdentity.of(
                    event.response().callerId(), event.response().callerType())
                : null;
        io.casehub.api.spi.judgment.VerificationContext verificationCtx =
            new io.casehub.api.spi.judgment.VerificationContext(
                event.caseId(),
                instance.tenancyId,
                event.bindingName(),
                jt,
                Map.of(),
                def,
                event.response().decision(),
                toTypedEvidence(event.response().evidence()),
                callerIdentity,
                null);
        io.casehub.api.spi.judgment.VerificationResult result = verifier.verify(verificationCtx);
        writeVerifiedEventLog(instance, event, jt.verifierStrategy(), result);
        switch (result) {
          case io.casehub.api.spi.judgment.VerificationResult.Accepted a -> {}
          case io.casehub.api.spi.judgment.VerificationResult.InsufficientEvidence ie -> {
            eventBus.publish(
                EventBusAddresses.JUDGMENT_ESCALATED,
                new JudgmentEscalatedEvent(
                    event.caseId(),
                    event.bindingName(),
                    instance.tenancyId,
                    event.response(),
                    result));
            LOG.infof(
                "Judgment verification failed (insufficient evidence): caseId=%s binding=%s",
                event.caseId(), event.bindingName());
            return;
          }
          case io.casehub.api.spi.judgment.VerificationResult.TrustTooLow ttl -> {
            eventBus.publish(
                EventBusAddresses.JUDGMENT_ESCALATED,
                new JudgmentEscalatedEvent(
                    event.caseId(),
                    event.bindingName(),
                    instance.tenancyId,
                    event.response(),
                    result));
            LOG.infof(
                "Judgment verification failed (trust too low): caseId=%s binding=%s",
                event.caseId(), event.bindingName());
            return;
          }
          case io.casehub.api.spi.judgment.VerificationResult.Rejected r -> {
            instance
                .getCaseContext()
                .set(
                    "_diagnostics." + event.bindingName() + ".verificationRejected",
                    Map.of(
                        "reason",
                        r.reason(),
                        "callerId",
                        event.response().callerId() != null
                            ? event.response().callerId()
                            : "unknown"));
            eventBus.publish(
                EventBusAddresses.CONTEXT_CHANGED,
                new CaseContextChangedEvent(instance, instance.getCaseContext(), "working"));
            judgmentNodeExecutor.enqueue(
                event.caseId(),
                event.bindingName(),
                new io.casehub.engine.common.spi.JudgmentNodeResult.Faulted(
                    "Verification rejected: " + r.reason()));
            LOG.infof(
                "Judgment verification rejected: caseId=%s binding=%s reason=%s",
                event.caseId(), event.bindingName(), r.reason());
            return;
          }
        }
      }

      if (jt.outputMapping() != null) {
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("decision", event.response().decision());
        responseData.put("evidence", event.response().evidence());
        instance.getCaseContext().set(event.bindingName(), responseData);
      }
    }

    writeRespondedEventLog(instance, event);

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext(), "working"));

    judgmentNodeExecutor.enqueue(
        event.caseId(),
        event.bindingName(),
        new io.casehub.engine.common.spi.JudgmentNodeResult.Completed(event.response()));

    LOG.infof(
        "Judgment response applied: caseId=%s binding=%s decision=%s",
        event.caseId(), event.bindingName(), event.response().decision());
  }

  private void writeVerifiedEventLog(
      CaseInstance instance,
      JudgmentCompletedEvent event,
      String verifierStrategy,
      io.casehub.api.spi.judgment.VerificationResult result) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.JUDGMENT_VERIFIED);
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("bindingName", event.bindingName());
    metadata.put("verifierStrategy", verifierStrategy);
    String resultType =
        switch (result) {
          case io.casehub.api.spi.judgment.VerificationResult.Accepted a -> "ACCEPTED";
          case io.casehub.api.spi.judgment.VerificationResult.InsufficientEvidence ie ->
              "INSUFFICIENT_EVIDENCE";
          case io.casehub.api.spi.judgment.VerificationResult.TrustTooLow ttl -> "TRUST_TOO_LOW";
          case io.casehub.api.spi.judgment.VerificationResult.Rejected r -> "REJECTED";
        };
    metadata.put("result", resultType);
    if (result instanceof io.casehub.api.spi.judgment.VerificationResult.InsufficientEvidence ie) {
      metadata.put("feedback", ie.feedback());
      metadata.set("missingKeys", MAPPER.valueToTree(ie.missingKeys()));
    }
    if (result instanceof io.casehub.api.spi.judgment.VerificationResult.Rejected r) {
      metadata.put("feedback", r.reason());
    }
    if (event.response().callerId() != null) {
      metadata.put("callerId", event.response().callerId());
    }
    if (event.response().callerType() != null) {
      metadata.put("callerType", event.response().callerType());
    }
    log.setMetadata(metadata);
    eventLogRepository.append(log, instance.tenancyId);
  }

  private void writeRespondedEventLog(CaseInstance instance, JudgmentCompletedEvent event) {
    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.JUDGMENT_RESPONDED);
    ObjectNode metadata = MAPPER.createObjectNode();
    metadata.put("bindingName", event.bindingName());
    metadata.put("decision", event.response().decision());
    metadata.set("evidence", MAPPER.valueToTree(event.response().evidence()));
    if (event.response().callerId() != null) {
      metadata.put("callerId", event.response().callerId());
    }
    if (event.response().callerType() != null) {
      metadata.put("callerType", event.response().callerType());
    }
    log.setMetadata(metadata);
    eventLogRepository.append(log, instance.tenancyId);
  }

  private static List<io.casehub.api.spi.judgment.Evidence> toTypedEvidence(
      Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    return raw.entrySet().stream()
        .map(
            e ->
                io.casehub.api.spi.judgment.Evidence.of(
                    e.getKey(),
                    io.casehub.api.spi.judgment.EvidenceType.ATTESTATION,
                    String.valueOf(e.getValue())))
        .toList();
  }
}
