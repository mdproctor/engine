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
package io.casehub.engine.watchdog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.context.WritableLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.StallRecoveryAction;
import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.StallRecoveryPolicy;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.recovery.StallClassificationContext;
import io.casehub.api.spi.recovery.StallClassifier;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultStallRecoveryHandlerTest {

  private DefaultStallRecoveryHandler handler;
  private CaseInstanceCache caseInstanceCache;
  private CaseDefinitionRegistry definitionRegistry;
  private EventLogRepository eventLogRepository;
  private EventBus eventBus;
  private PlanItemStore planItemStore;
  private JudgmentScheduler judgmentScheduler;

  private CaseInstance instance;
  private CaseDefinition definition;
  private CaseMetaModel metaModel;
  private MutableCaseContext mutableContext;
  private WritableLayer workingLayer;
  private UUID caseId;
  private String tenancyId;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    handler = new DefaultStallRecoveryHandler();
    caseInstanceCache = mock(CaseInstanceCache.class);
    definitionRegistry = mock(CaseDefinitionRegistry.class);
    eventLogRepository = mock(EventLogRepository.class);
    eventBus = mock(EventBus.class);
    planItemStore = mock(PlanItemStore.class);
    judgmentScheduler = mock(JudgmentScheduler.class);

    handler.caseInstanceCache = caseInstanceCache;
    handler.definitionRegistry = definitionRegistry;
    handler.eventLogRepository = eventLogRepository;
    handler.eventBus = eventBus;

    Instance<PlanItemStore> piInstance = mock(Instance.class);
    when(piInstance.isResolvable()).thenReturn(true);
    when(piInstance.get()).thenReturn(planItemStore);
    handler.planItemStore = piInstance;

    Instance<JudgmentScheduler> jsInstance = mock(Instance.class);
    when(jsInstance.isResolvable()).thenReturn(true);
    when(jsInstance.get()).thenReturn(judgmentScheduler);
    handler.judgmentScheduler = jsInstance;

    StallClassifier defaultClassifier =
        new StallClassifier() {
          @Override
          public StallRecoveryAction classify(StallClassificationContext ctx) {
            return ctx.policy()
                .conditionActions()
                .getOrDefault(ctx.recoveryContext().conditionType(), ctx.policy().defaultAction());
          }

          @Override
          public String id() {
            return "policy-lookup";
          }
        };
    Instance<StallClassifier> scInstance = mock(Instance.class);
    when(scInstance.iterator()).thenReturn(List.of(defaultClassifier).iterator());
    handler.stallClassifiers = scInstance;

    caseId = UUID.randomUUID();
    tenancyId = "tenant-1";
    metaModel = mock(CaseMetaModel.class);
    instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getState()).thenReturn(CaseStatus.RUNNING);
    when(instance.getCaseMetaModel()).thenReturn(metaModel);
    instance.tenancyId = tenancyId;
    when(caseInstanceCache.get(caseId)).thenReturn(instance);

    mutableContext = mock(MutableCaseContext.class);
    workingLayer = mock(WritableLayer.class);
    when(mutableContext.writableLayer(ContextLayer.WORKING)).thenReturn(workingLayer);
    CaseContext snapshotContext = mock(CaseContext.class);
    when(mutableContext.snapshot()).thenReturn(snapshotContext);
    when(instance.getCaseContext()).thenReturn(mutableContext);

    definition = mock(CaseDefinition.class);
    when(definitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
  }

  private StallRecoveryContext ctx(
      WatchdogConditionType type, List<String> agents, String bindingName, String planItemId) {
    return new StallRecoveryContext(
        caseId, tenancyId, type, agents, "alert", null, Instant.now(), bindingName, planItemId);
  }

  private void configurePolicy(Map<WatchdogConditionType, StallRecoveryAction> actions) {
    StallRecoveryPolicy policy =
        new StallRecoveryPolicy(true, "policy-lookup", actions, StallRecoveryAction.NOTIFY);
    when(definition.getStallRecoveryPolicy()).thenReturn(policy);
  }

  // --- RETRY ---

  @Test
  void retryPublishesContextChanged() {
    configurePolicy(Map.of(WatchdogConditionType.CHANNEL_IDLE, StallRecoveryAction.RETRY));
    when(workingLayer.get("_stallRecovery")).thenReturn(null);
    when(workingLayer.setPath(anyString(), any())).thenReturn(workingLayer);

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertTrue(result);
    verify(eventBus)
        .publish(eq(EventBusAddresses.CONTEXT_CHANGED), any(CaseContextChangedEvent.class));
  }

  @Test
  void retryDebounceSkipsWhenRecent() {
    configurePolicy(Map.of(WatchdogConditionType.CHANNEL_IDLE, StallRecoveryAction.RETRY));
    when(workingLayer.get("_stallRecovery"))
        .thenReturn(Map.of("lastRetryAt", Instant.now().toString()));

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertFalse(result);
    verify(eventBus, never()).publish(eq(EventBusAddresses.CONTEXT_CHANGED), any());
  }

  // --- REROUTE ---

  @Test
  void rerouteExcludesAgentAndPublishesContextChanged() {
    configurePolicy(Map.of(WatchdogConditionType.LOOP_DETECTED, StallRecoveryAction.REROUTE));
    when(workingLayer.getPath("_diagnostics.review.excludedAgents")).thenReturn(null);
    when(workingLayer.setPath(anyString(), any())).thenReturn(workingLayer);

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.LOOP_DETECTED, List.of("agent-1"), "review", "pi-1"));

    assertTrue(result);
    verify(eventBus)
        .publish(eq(EventBusAddresses.CONTEXT_CHANGED), any(CaseContextChangedEvent.class));
  }

  @Test
  void rerouteSkipsWhenAgentAlreadyExcluded() {
    configurePolicy(Map.of(WatchdogConditionType.LOOP_DETECTED, StallRecoveryAction.REROUTE));
    when(workingLayer.getPath("_diagnostics.review.excludedAgents")).thenReturn(List.of("agent-1"));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.LOOP_DETECTED, List.of("agent-1"), "review", "pi-1"));

    assertFalse(result);
    verify(eventBus, never()).publish(eq(EventBusAddresses.CONTEXT_CHANGED), any());
  }

  @Test
  void rerouteFallsToNotifyWithoutBinding() {
    configurePolicy(Map.of(WatchdogConditionType.BARRIER_STUCK, StallRecoveryAction.REROUTE));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.BARRIER_STUCK, List.of("agent-1"), null, null));

    assertTrue(result);
    verify(eventLogRepository).append(any(EventLog.class), eq(tenancyId));
    verify(eventBus, never()).publish(eq(EventBusAddresses.CONTEXT_CHANGED), any());
  }

  // --- CANCEL ---

  @Test
  void cancelMarksPlanItemCancelled() {
    configurePolicy(Map.of(WatchdogConditionType.LOOP_DETECTED, StallRecoveryAction.CANCEL));
    PlanItemRecord running =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "review",
            TaskStatus.RUNNING,
            Instant.now(),
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);
    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(running));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.LOOP_DETECTED, List.of("agent-1"), "review", "pi-1"));

    assertTrue(result);
    verify(planItemStore).updateStatus("pi-1", TaskStatus.CANCELLED, tenancyId);
    verify(eventBus)
        .publish(
            eq(EventBusAddresses.WORKER_OUTCOME_RESOLVED), any(WorkerOutcomeResolvedEvent.class));
  }

  @Test
  void cancelSkipsWhenAlreadyTerminal() {
    configurePolicy(Map.of(WatchdogConditionType.LOOP_DETECTED, StallRecoveryAction.CANCEL));
    PlanItemRecord completed =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "review",
            TaskStatus.COMPLETED,
            Instant.now(),
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);
    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(completed));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.LOOP_DETECTED, List.of("agent-1"), "review", "pi-1"));

    assertFalse(result);
    verify(planItemStore, never()).updateStatus(anyString(), any(TaskStatus.class), anyString());
  }

  @Test
  void cancelFallsToNotifyWithoutPlanItemId() {
    configurePolicy(Map.of(WatchdogConditionType.BARRIER_STUCK, StallRecoveryAction.CANCEL));

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.BARRIER_STUCK, List.of(), null, null));

    assertTrue(result);
    verify(planItemStore, never()).updateStatus(anyString(), any(TaskStatus.class), anyString());
  }

  // --- EXPIRE ---

  @Test
  void expirePublishesExhausted() {
    configurePolicy(Map.of(WatchdogConditionType.CONTEXT_PRESSURE, StallRecoveryAction.EXPIRE));
    PlanItemRecord running =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "analysis",
            TaskStatus.RUNNING,
            Instant.now(),
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);
    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(running));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.CONTEXT_PRESSURE, List.of("agent-1"), "analysis", "pi-1"));

    assertTrue(result);
    verify(eventBus)
        .publish(
            eq(EventBusAddresses.WORKER_OUTCOME_RESOLVED), any(WorkerOutcomeResolvedEvent.class));
  }

  @Test
  void expireSkipsWhenPlanItemNotRunning() {
    configurePolicy(Map.of(WatchdogConditionType.CONTEXT_PRESSURE, StallRecoveryAction.EXPIRE));
    PlanItemRecord faulted =
        PlanItemRecord.primitive(
            caseId,
            "pi-1",
            "analysis",
            TaskStatus.FAULTED,
            Instant.now(),
            TargetType.CAPABILITY,
            null,
            tenancyId,
            "desc",
            "agent-1",
            null);
    when(planItemStore.findByCaseId(caseId, tenancyId)).thenReturn(List.of(faulted));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.CONTEXT_PRESSURE, List.of("agent-1"), "analysis", "pi-1"));

    assertFalse(result);
  }

  // --- ESCALATE ---

  @Test
  void escalateSchedulesJudgment() {
    configurePolicy(Map.of(WatchdogConditionType.CONVERSATION_STALL, StallRecoveryAction.ESCALATE));

    boolean result =
        handler.handleStall(
            ctx(WatchdogConditionType.CONVERSATION_STALL, List.of(), "review", null));

    assertTrue(result);
    ArgumentCaptor<JudgmentRequest> captor = ArgumentCaptor.forClass(JudgmentRequest.class);
    verify(judgmentScheduler).schedule(captor.capture());
    assertEquals(caseId, captor.getValue().caseId());
    assertEquals("review", captor.getValue().bindingName());
  }

  @Test
  void escalateUsesSyntheticBindingWhenNotResolved() {
    configurePolicy(Map.of(WatchdogConditionType.CONVERSATION_STALL, StallRecoveryAction.ESCALATE));

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CONVERSATION_STALL, List.of(), null, null));

    assertTrue(result);
    ArgumentCaptor<JudgmentRequest> captor = ArgumentCaptor.forClass(JudgmentRequest.class);
    verify(judgmentScheduler).schedule(captor.capture());
    assertEquals("stall-recovery", captor.getValue().bindingName());
  }

  // --- NOTIFY ---

  @Test
  void notifyWritesEventLog() {
    configurePolicy(Map.of());

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.QUEUE_DEPTH, List.of(), null, null));

    assertTrue(result);
    verify(eventLogRepository).append(any(EventLog.class), eq(tenancyId));
  }

  // --- IGNORE ---

  @Test
  void ignoreReturnsFalse() {
    configurePolicy(Map.of(WatchdogConditionType.CHANNEL_IDLE, StallRecoveryAction.IGNORE));

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertFalse(result);
    verify(eventBus, never()).publish(anyString(), any());
    verify(eventLogRepository, never()).append(any(), anyString());
  }

  // --- Guard rails ---

  @Test
  void returnsFalseWhenCaseTerminal() {
    when(instance.getState()).thenReturn(CaseStatus.COMPLETED);
    configurePolicy(Map.of(WatchdogConditionType.CHANNEL_IDLE, StallRecoveryAction.RETRY));

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertFalse(result);
  }

  @Test
  void returnsFalseWhenPolicyDisabled() {
    StallRecoveryPolicy policy =
        new StallRecoveryPolicy(false, "policy-lookup", Map.of(), StallRecoveryAction.NOTIFY);
    when(definition.getStallRecoveryPolicy()).thenReturn(policy);

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertFalse(result);
  }

  @Test
  void returnsFalseWhenNoPolicyConfigured() {
    when(definition.getStallRecoveryPolicy()).thenReturn(null);

    boolean result =
        handler.handleStall(ctx(WatchdogConditionType.CHANNEL_IDLE, List.of(), null, null));

    assertFalse(result);
  }
}
