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
package io.casehub.engine.internal.bridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.WatchdogResponseAction;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.qhorus.api.watchdog.AgentStaleContext;
import io.casehub.qhorus.api.watchdog.ChannelIdleContext;
import io.casehub.qhorus.api.watchdog.LoopDetectedContext;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WatchdogRecoveryBridgeTest {

  @Mock EventBus eventBus;
  @Mock CaseInstanceCache caseInstanceCache;
  @Mock CaseDefinitionRegistry definitionRegistry;
  @Mock PlanItemStore planItemStore;
  @Mock WorkerExecutionManager executionManager;
  @InjectMocks WatchdogRecoveryBridge bridge;

  private final UUID caseId = UUID.randomUUID();
  private final CaseMetaModel metaModel = new CaseMetaModel();
  private CaseInstance instance;
  private CaseDefinition definition;

  @BeforeEach
  void setUp() {
    instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    when(instance.getCaseMetaModel()).thenReturn(metaModel);
    instance.tenancyId = "tenant-1";

    definition =
        CaseDefinition.builder().namespace("test").name("test-case").version("1.0").build();

    when(caseInstanceCache.get(caseId)).thenReturn(instance);
    when(definitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
  }

  @Test
  void agentStale_with_caseId_publishes_synthetic_expired() {
    when(planItemStore.findByCaseId(caseId, "tenant-1"))
        .thenReturn(List.of(planItem("worker-1", TaskStatus.RUNNING)));

    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "AGENT_STALE: stale",
            Instant.now(),
            new AgentStaleContext(1, List.of("worker-1")),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus)
        .publish(
            eq(EventBusAddresses.WORKER_EXECUTION_FINISHED), any(WorkflowExecutionCompleted.class));
  }

  @Test
  void loopDetected_resolves_via_executionManager_when_caseId_null() {
    when(executionManager.getActiveCaseIds("looping-agent")).thenReturn(List.of(caseId));
    when(planItemStore.findByCaseId(caseId, "tenant-1"))
        .thenReturn(List.of(planItem("looping-agent", TaskStatus.RUNNING)));

    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "LOOP_DETECTED: loop",
            Instant.now(),
            new LoopDetectedContext(UUID.randomUUID(), "ch-1", "looping-agent", 5, 0.9));

    bridge.onWatchdogAlert(event);

    verify(eventBus)
        .publish(
            eq(EventBusAddresses.WORKER_EXECUTION_FINISHED), any(WorkflowExecutionCompleted.class));
  }

  @Test
  void no_affected_agents_skips_silently() {
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "CHANNEL_IDLE: idle",
            Instant.now(),
            new ChannelIdleContext(List.of(), 600),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus, never()).publish(any(String.class), any());
  }

  @Test
  void ignore_policy_skips_cancellation() {
    definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .watchdogPolicy(
                Map.of(WatchdogConditionType.LOOP_DETECTED, WatchdogResponseAction.IGNORE))
            .build();
    when(definitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    when(planItemStore.findByCaseId(caseId, "tenant-1"))
        .thenReturn(List.of(planItem("worker-1", TaskStatus.RUNNING)));

    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "LOOP_DETECTED: loop",
            Instant.now(),
            new LoopDetectedContext(UUID.randomUUID(), "ch-1", "worker-1", 5, 0.9),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus, never()).publish(any(String.class), any());
  }

  @Test
  void terminal_planItem_skipped() {
    when(planItemStore.findByCaseId(caseId, "tenant-1"))
        .thenReturn(List.of(planItem("worker-1", TaskStatus.COMPLETED)));

    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "AGENT_STALE: stale",
            Instant.now(),
            new AgentStaleContext(1, List.of("worker-1")),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus, never()).publish(any(String.class), any());
  }

  @Test
  void no_matching_planItem_skips_silently() {
    when(planItemStore.findByCaseId(caseId, "tenant-1"))
        .thenReturn(List.of(planItem("other-worker", TaskStatus.RUNNING)));

    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "AGENT_STALE: stale",
            Instant.now(),
            new AgentStaleContext(1, List.of("unknown-worker")),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus, never()).publish(any(String.class), any());
  }

  @Test
  void caseLevelCondition_defaultsToIgnore() {
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "target",
            "notif",
            "CHANNEL_IDLE: idle",
            Instant.now(),
            new ChannelIdleContext(List.of("worker-1"), 600),
            caseId);

    bridge.onWatchdogAlert(event);

    verify(eventBus, never()).publish(any(String.class), any());
  }

  private PlanItemRecord planItem(String executorName, TaskStatus status) {
    return PlanItemRecord.primitive(
        caseId,
        UUID.randomUUID().toString(),
        "binding-" + executorName,
        status,
        Instant.now(),
        TargetType.CAPABILITY,
        ".",
        "tenant-1",
        null,
        executorName,
        null);
  }
}
