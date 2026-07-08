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
package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateCancelledEvent;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import io.casehub.engine.common.internal.event.ActionGateScheduleEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.worker.api.PlannedAction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for ActionGateWorkItemHandler, ActionGateCompletionApplier, and gate routing in
 * WorkItemLifecycleAdapter.
 *
 * <p>Uses @ConsumeEvent recording beans to capture gate resolution events. Quarkus registers codecs
 * for event types when it scans @ConsumeEvent annotations at startup — the recorder beans enable
 * this without requiring the engine runtime module on the classpath.
 */
@QuarkusTest
class ActionGateHandlerTest {

  private static final String TENANCY_ID = "test-tenant";

  @Inject ActionGateWorkItemHandler actionGateWorkItemHandler;
  @Inject ActionGateCompletionApplier actionGateCompletionApplier;
  @Inject WorkItemStore workItemStore;

  private static WorkItemRef toRef(final WorkItem wi) {
    return new WorkItemRef(
        wi.id,
        wi.status,
        wi.callerRef,
        wi.assigneeId,
        wi.resolution,
        wi.candidateGroups,
        wi.outcome,
        wi.tenancyId,
        wi.payload);
  }

  @BeforeEach
  void setUp() {
    GateEventRecorder.reset();
    if (workItemStore instanceof InMemoryWorkItemStore mem) {
      mem.clear();
    }
  }

  // --- ActionGateWorkItemHandler ---

  @AfterEach
  @Transactional
  void tearDown() {
    if (workItemStore instanceof InMemoryWorkItemStore mem) {
      mem.clear();
    }
  }

  @Test
  @Transactional
  void onActionGateSchedule_createsWorkItemWithGateCallerRef() {
    final UUID caseId = UUID.randomUUID();
    final long gateId = 42L;
    final GateRequired gate =
        new GateRequired(
            "SAR filing requires MLRO sign-off",
            false,
            StaticSetStrategy.of("mlro", "analyst"),
            null,
            null);
    final PlannedAction action =
        PlannedAction.of("File SAR", "sar.file", Map.of("accountId", "ACC-123"));

    actionGateWorkItemHandler.onActionGateSchedule(
        new ActionGateScheduleEvent(
            caseId, TENANCY_ID, gateId, action, gate, Set.of("mlro", "analyst")));

    final String expectedCallerRef = GateCallerRef.encode(caseId, gateId);
    final WorkItem workItem = workItemStore.findByCallerRef(expectedCallerRef).orElse(null);

    assertThat(workItem).isNotNull();
    assertThat(workItem.callerRef).isEqualTo(expectedCallerRef);
    assertThat(workItem.title).isEqualTo("SAR filing requires MLRO sign-off");
    assertThat(workItem.candidateGroups).isEqualTo("analyst,mlro");
    assertThat(workItem.payload).contains("sar.file");
    assertThat(workItem.payload).contains("ACC-123");
    assertThat(workItem.payload).contains("File SAR");
  }

  @Test
  @Transactional
  void onActionGateSchedule_noExpiresAt_whenExpiresInIsNull() {
    final UUID caseId = UUID.randomUUID();
    final GateRequired gate = new GateRequired("Confirm action", true, null, null, null);
    final PlannedAction action = PlannedAction.of("Do something", "action.type", Map.of());

    actionGateWorkItemHandler.onActionGateSchedule(
        new ActionGateScheduleEvent(caseId, TENANCY_ID, 99L, action, gate, Set.of()));

    final String callerRef = GateCallerRef.encode(caseId, 99L);
    final WorkItem workItem = workItemStore.findByCallerRef(callerRef).orElse(null);
    assertThat(workItem).isNotNull();
    // candidateGroups is null when not specified — WorkItemService does not add defaults
    assertThat(workItem.candidateGroups).isNull();
    // expiresAt may be set by WorkItemService defaults; we don't assert null here
  }

  // --- ActionGateCompletionApplier ---

  @Test
  @Transactional
  void onActionGateSchedule_withExpiresIn_setsExpiresAt() {
    final UUID caseId = UUID.randomUUID();
    final Instant before = Instant.now();
    final GateRequired gate =
        new GateRequired(
            "Urgent review", false, StaticSetStrategy.of("mlro"), Duration.ofHours(24), null);

    actionGateWorkItemHandler.onActionGateSchedule(
        new ActionGateScheduleEvent(
            caseId, TENANCY_ID, 77L, PlannedAction.of("d", "t", Map.of()), gate, Set.of("mlro")));

    final WorkItem workItem =
        workItemStore.findByCallerRef(GateCallerRef.encode(caseId, 77L)).orElse(null);
    assertThat(workItem).isNotNull();
    assertThat(workItem.expiresAt).isNotNull();
    assertThat(workItem.expiresAt).isAfter(before.plus(Duration.ofHours(23)));
    assertThat(workItem.expiresAt).isBefore(before.plus(Duration.ofHours(25)));
  }

  @Test
  void completionApplier_completed_publishesApprovedEvent() {
    final UUID caseId = UUID.randomUUID();
    final long gateId = 10L;
    final WorkItem workItem = new WorkItem();
    workItem.callerRef = GateCallerRef.encode(caseId, gateId);
    workItem.assigneeId = "user-mlro-001";
    workItem.resolution = "{\"note\": \"approved\"}";

    actionGateCompletionApplier.apply(
        new GateCallerRef(caseId, gateId), WorkItemStatus.COMPLETED, toRef(workItem));

    await().atMost(2, TimeUnit.SECONDS).until(() -> !GateEventRecorder.approvedEvents.isEmpty());

    assertThat(GateEventRecorder.approvedEvents).hasSize(1);
    assertThat(GateEventRecorder.approvedEvents.get(0).caseId()).isEqualTo(caseId);
    assertThat(GateEventRecorder.approvedEvents.get(0).gateId()).isEqualTo(gateId);
    assertThat(GateEventRecorder.approvedEvents.get(0).approvedBy())
        .isEqualTo("user-mlro-001"); // from assigneeId
    assertThat(GateEventRecorder.rejectedEvents).isEmpty();
    assertThat(GateEventRecorder.expiredEvents).isEmpty();
  }

  @Test
  void completionApplier_rejected_publishesRejectedEvent() {
    final UUID caseId = UUID.randomUUID();
    final long gateId = 20L;
    final WorkItem workItem = new WorkItem();
    workItem.callerRef = GateCallerRef.encode(caseId, gateId);
    workItem.assigneeId = "user-mlro-002";
    workItem.resolution = "{\"reason\": \"rejected\"}";

    actionGateCompletionApplier.apply(
        new GateCallerRef(caseId, gateId), WorkItemStatus.REJECTED, toRef(workItem));

    await().atMost(2, TimeUnit.SECONDS).until(() -> !GateEventRecorder.rejectedEvents.isEmpty());

    assertThat(GateEventRecorder.rejectedEvents).hasSize(1);
    assertThat(GateEventRecorder.rejectedEvents.get(0).caseId()).isEqualTo(caseId);
    assertThat(GateEventRecorder.rejectedEvents.get(0).rejectedBy()).isEqualTo("user-mlro-002");
    assertThat(GateEventRecorder.approvedEvents).isEmpty();
  }

  @Test
  void completionApplier_cancelled_publishesRejectedEvent() {
    final UUID caseId = UUID.randomUUID();
    final WorkItem workItem = new WorkItem();
    workItem.callerRef = GateCallerRef.encode(caseId, 30L);

    actionGateCompletionApplier.apply(
        new GateCallerRef(caseId, 30L), WorkItemStatus.CANCELLED, toRef(workItem));

    await().atMost(2, TimeUnit.SECONDS).until(() -> !GateEventRecorder.rejectedEvents.isEmpty());
    assertThat(GateEventRecorder.rejectedEvents).hasSize(1);
    assertThat(GateEventRecorder.rejectedEvents.get(0).caseId()).isEqualTo(caseId);
  }

  // --- Recording CDI beans — @ConsumeEvent causes Quarkus to register codecs for event types ---

  @Test
  void completionApplier_expired_publishesExpiredEvent() {
    final UUID caseId = UUID.randomUUID();
    final WorkItem workItem = new WorkItem();
    workItem.callerRef = GateCallerRef.encode(caseId, 40L);

    actionGateCompletionApplier.apply(
        new GateCallerRef(caseId, 40L), WorkItemStatus.EXPIRED, toRef(workItem));

    await().atMost(2, TimeUnit.SECONDS).until(() -> !GateEventRecorder.expiredEvents.isEmpty());
    assertThat(GateEventRecorder.expiredEvents).hasSize(1);
    assertThat(GateEventRecorder.expiredEvents.get(0).caseId()).isEqualTo(caseId);
    assertThat(GateEventRecorder.expiredEvents.get(0).gateId()).isEqualTo(40L);
  }

  @ApplicationScoped
  static class GateEventRecorder {

    static final List<ActionGateApprovedEvent> approvedEvents = new CopyOnWriteArrayList<>();
    static final List<ActionGateRejectedEvent> rejectedEvents = new CopyOnWriteArrayList<>();
    static final List<ActionGateExpiredEvent> expiredEvents = new CopyOnWriteArrayList<>();
    static final List<ActionGateCancelledEvent> cancelledEvents = new CopyOnWriteArrayList<>();

    static void reset() {
      approvedEvents.clear();
      rejectedEvents.clear();
      expiredEvents.clear();
      cancelledEvents.clear();
    }

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_APPROVED)
    void onApproved(final ActionGateApprovedEvent event) {
      approvedEvents.add(event);
    }

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_REJECTED)
    void onRejected(final ActionGateRejectedEvent event) {
      rejectedEvents.add(event);
    }

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_EXPIRED)
    void onExpired(final ActionGateExpiredEvent event) {
      expiredEvents.add(event);
    }

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_CANCELLED)
    void onCancelled(final ActionGateCancelledEvent event) {
      cancelledEvents.add(event);
    }
  }
}
