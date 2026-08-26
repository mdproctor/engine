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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CallerIdentity;
import io.casehub.api.model.JudgmentResponse;
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.spi.EscalationContext;
import io.casehub.api.spi.EscalationDecision;
import io.casehub.api.spi.JudgmentEscalator;
import io.casehub.api.spi.JudgmentVerifier;
import io.casehub.api.spi.VerificationContext;
import io.casehub.api.spi.VerificationResult;
import io.casehub.engine.common.internal.event.JudgmentResponseEvent;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PendingJudgment;
import io.casehub.platform.api.routing.StrategyResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JudgmentResponseHandlerTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";

  private StrategyResolver strategyResolver;
  private JudgmentScheduler judgmentScheduler;
  private JudgmentVerifier verifier;
  private JudgmentEscalator escalator;
  private JudgmentResponseHandler handler;

  @BeforeEach
  void setUp() {
    strategyResolver = mock(StrategyResolver.class);
    judgmentScheduler = mock(JudgmentScheduler.class);
    verifier = mock(JudgmentVerifier.class);
    escalator = mock(JudgmentEscalator.class);

    handler = new JudgmentResponseHandler();
    handler.strategyResolver = strategyResolver;
    handler.judgmentScheduler = judgmentScheduler;

    when(strategyResolver.resolve(eq(JudgmentVerifier.class), any())).thenReturn(verifier);
    when(strategyResolver.resolve(eq(JudgmentEscalator.class), any())).thenReturn(escalator);
  }

  @Test
  void processResponse_looks_up_caseInstance_and_calls_verifyAndApply() {
    var caseInstanceCache = mock(io.casehub.engine.common.spi.cache.CaseInstanceCache.class);
    var caseDefRegistry = mock(io.casehub.engine.common.spi.CaseDefinitionRegistry.class);
    var eventLogRepo = mock(io.casehub.engine.common.spi.EventLogRepository.class);
    handler.caseInstanceCache = caseInstanceCache;
    handler.caseDefinitionRegistry = caseDefRegistry;
    handler.eventLogRepository = eventLogRepo;

    var target = JudgmentTarget.forHuman().prompt("Review").verifier("none").build();
    var payload = bindingPayload();
    var pending = pending(payload);

    var instance = mock(io.casehub.engine.common.internal.model.CaseInstance.class);
    when(instance.getState()).thenReturn(io.casehub.api.model.CaseStatus.RUNNING);
    when(instance.getPendingJudgment("review-binding")).thenReturn(pending);
    when(instance.getCaseMetaModel()).thenReturn(null);
    when(caseInstanceCache.get(CASE_ID)).thenReturn(instance);

    var def = mock(io.casehub.api.model.CaseDefinition.class);
    var binding = mock(io.casehub.api.model.Binding.class);
    when(binding.getName()).thenReturn("review-binding");
    when(binding.target()).thenReturn(target);
    when(def.getBindings()).thenReturn(List.of(binding));
    when(caseDefRegistry.getCaseDefinition(any())).thenReturn(def);

    when(verifier.verify(any(), any())).thenReturn(new VerificationResult.Accepted());

    var event = responseEvent();
    handler.onJudgmentResponse(event);

    verify(verifier)
        .verify(any(JudgmentResponse.class), any(io.casehub.api.spi.VerificationContext.class));
  }

  @Test
  void processResponse_discards_when_case_not_in_cache() {
    var caseInstanceCache = mock(io.casehub.engine.common.spi.cache.CaseInstanceCache.class);
    handler.caseInstanceCache = caseInstanceCache;
    when(caseInstanceCache.get(CASE_ID)).thenReturn(null);

    handler.onJudgmentResponse(responseEvent());

    verify(verifier, never()).verify(any(), any());
  }

  @Test
  void processResponse_discards_when_case_is_terminal() {
    var caseInstanceCache = mock(io.casehub.engine.common.spi.cache.CaseInstanceCache.class);
    handler.caseInstanceCache = caseInstanceCache;

    var instance = mock(io.casehub.engine.common.internal.model.CaseInstance.class);
    when(instance.getState()).thenReturn(io.casehub.api.model.CaseStatus.COMPLETED);
    when(caseInstanceCache.get(CASE_ID)).thenReturn(instance);

    handler.onJudgmentResponse(responseEvent());

    verify(verifier, never()).verify(any(), any());
  }

  @Test
  void processResponse_discards_when_no_pending_judgment() {
    var caseInstanceCache = mock(io.casehub.engine.common.spi.cache.CaseInstanceCache.class);
    handler.caseInstanceCache = caseInstanceCache;

    var instance = mock(io.casehub.engine.common.internal.model.CaseInstance.class);
    when(instance.getState()).thenReturn(io.casehub.api.model.CaseStatus.RUNNING);
    when(instance.getPendingJudgment("review-binding")).thenReturn(null);
    when(caseInstanceCache.get(CASE_ID)).thenReturn(instance);

    handler.onJudgmentResponse(responseEvent());

    verify(verifier, never()).verify(any(), any());
  }

  @Test
  void accepted_binding_does_not_escalate() {
    var target = JudgmentTarget.forHuman().prompt("Review").verifier("evidence-presence").build();
    var payload = bindingPayload();
    var pending = pending(payload);
    var event = responseEvent();

    when(verifier.verify(any(), any())).thenReturn(new VerificationResult.Accepted());

    handler.verifyAndApply(event, target, pending, 1);

    verify(verifier).verify(any(JudgmentResponse.class), any(VerificationContext.class));
    verify(escalator, never()).escalate(any());
  }

  @Test
  void insufficient_evidence_triggers_escalation() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .verifier("evidence-presence")
            .escalator("default")
            .build();
    var payload = bindingPayload();
    var pending = pending(payload);
    var event = responseEvent();

    when(verifier.verify(any(), any()))
        .thenReturn(
            new VerificationResult.InsufficientEvidence("Missing rationale", List.of("rationale")));
    when(escalator.escalate(any()))
        .thenReturn(new EscalationDecision.ReYield("Please provide rationale"));

    handler.verifyAndApply(event, target, pending, 1);

    verify(escalator).escalate(any(EscalationContext.class));
  }

  @Test
  void rejected_triggers_escalation() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .verifier("evidence-presence")
            .escalator("default")
            .build();
    var payload = bindingPayload();
    var pending = pending(payload);
    var event = responseEvent();

    when(verifier.verify(any(), any()))
        .thenReturn(new VerificationResult.Rejected("Invalid response"));
    when(escalator.escalate(any())).thenReturn(new EscalationDecision.Fault("Cannot recover"));

    handler.verifyAndApply(event, target, pending, 3);

    verify(escalator).escalate(any(EscalationContext.class));
  }

  @Test
  void trust_too_low_triggers_escalation() {
    var target =
        JudgmentTarget.forHuman()
            .prompt("Review")
            .verifier("evidence-presence")
            .escalator("default")
            .build();
    var payload = bindingPayload();
    var pending = pending(payload);
    var event = responseEvent();

    when(verifier.verify(any(), any())).thenReturn(new VerificationResult.TrustTooLow(0.8, 0.3));
    when(escalator.escalate(any()))
        .thenReturn(new EscalationDecision.Escalate(null, "Trust insufficient"));

    handler.verifyAndApply(event, target, pending, 1);

    verify(escalator).escalate(any(EscalationContext.class));
  }

  @Test
  void accepted_gate_does_not_escalate() {
    var target = JudgmentTarget.forHuman().prompt("Approve action").build();
    var payload = gatePayload();
    var pending = pending(payload);
    var event = responseEvent();

    when(verifier.verify(any(), any())).thenReturn(new VerificationResult.Accepted());

    handler.verifyAndApply(event, target, pending, 1);

    verify(verifier).verify(any(JudgmentResponse.class), any(VerificationContext.class));
    verify(escalator, never()).escalate(any());
  }

  private static JudgmentPayload.BindingPayload bindingPayload() {
    return new JudgmentPayload.BindingPayload(
        Map.of("key", "value"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        Map.of());
  }

  private static JudgmentPayload.GatePayload gatePayload() {
    return new JudgmentPayload.GatePayload(
        1L,
        io.casehub.worker.api.PlannedAction.of("Action", "type", Map.of()),
        new io.casehub.api.spi.RiskDecision.GateRequired(
            "Review", true, null, null, null, null, null),
        java.util.Set.of("approvers"),
        null,
        Map.of());
  }

  private static PendingJudgment pending(JudgmentPayload payload) {
    return new PendingJudgment(
        1L, "review-binding", payload, "worker-1", "idem-1", Map.of(), Instant.now());
  }

  private static JudgmentResponseEvent responseEvent() {
    return new JudgmentResponseEvent(
        CASE_ID,
        TENANCY_ID,
        "review-binding",
        1L,
        new JudgmentResponse(
            Map.of("decision", "approve"),
            List.of(),
            new CallerIdentity("user-1", "human", null),
            Instant.now()));
  }
}
