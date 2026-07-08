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

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.work.api.GroupStatus;
import io.casehub.work.api.WorkItemGroupLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkloadProvider;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkItemLifecycleAdapterTest {

  /** Overrides JpaWorkloadProvider for test isolation — returns zero active work count. */
  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class StubWorkloadProvider implements WorkloadProvider {
    @Override
    public int getActiveWorkCount(String workerId) {
      return 0;
    }
  }

  @Inject BlackboardRegistry registry;

  @Inject ReactiveCaseInstanceRepository reactiveCaseInstanceRepository;

  @Inject Event<WorkItemLifecycleEvent> lifecycleEvents;

  @Inject Event<WorkItemGroupLifecycleEvent> groupLifecycleEvents;

  private UUID caseId;
  private String planItemId;
  private PlanItem planItem;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    planItem = PlanItem.create("review-binding", "review-worker", 10);
    planItemId = planItem.getPlanItemId();
    planItem.markRunning();

    registry.getOrCreate(caseId, "test-tenant").addPlanItem(planItem);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    reactiveCaseInstanceRepository
        .save(instance, "test-tenant")
        .await()
        .atMost(Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    registry.evict(caseId);
  }

  @Test
  void workItemCompleted_marksPlanItemCompleted_firesContextChanged() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.COMPLETED, "Approved"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));
  }

  @Test
  void workItemRejected_marksPlanItemRejected() {
    // Human task refusal — PlanItem must be DELEGATED (human task lifecycle)
    PlanItem delegatedItem = PlanItem.create("review-ht", "ht-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.REJECTED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.rejected", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.REJECTED));
  }

  @Test
  void workItemExpired_marksPlanItemFaulted() {
    // Deadline expiry — a time-based failure, maps to FAULTED
    PlanItem delegatedItem = PlanItem.create("review-ht-expired", "ht-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.EXPIRED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.expired", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.FAULTED));
  }

  @Test
  void workItemFaulted_marksPlanItemFaulted() {
    PlanItem delegatedItem = PlanItem.create("review-ht-faulted", "ht-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.FAULTED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.faulted", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.FAULTED));
  }

  @Test
  void workItemObsolete_marksPlanItemObsolete() {
    PlanItem delegatedItem = PlanItem.create("review-ht-obsolete", "ht-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.OBSOLETE;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.obsolete", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.OBSOLETE));
  }

  @Test
  void workItemEscalated_writesContextSignal_planItemUnchanged() {
    // ESCALATED is terminal (all SLA breach policies exhausted) but the PlanItem stays
    // DELEGATED — the adapter writes a workItemEscalated signal instead. Refs engine#400.
    WorkItem escalatedItem = new WorkItem();
    escalatedItem.id = UUID.randomUUID();
    escalatedItem.status = WorkItemStatus.ESCALATED;
    escalatedItem.candidateGroups = "committee-a,committee-b";
    escalatedItem.callerRef = PlanItemCallerRef.encode(caseId, planItemId);

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.escalated", escalatedItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  reactiveCaseInstanceRepository
                      .findByUuid(caseId, "test-tenant")
                      .await()
                      .atMost(Duration.ofSeconds(2));
              Object signal = updated.getCaseContext().get("workItemEscalated");
              assertThat(signal).isNotNull().isInstanceOf(Map.class);
              @SuppressWarnings("unchecked")
              Map<String, Object> signalMap = (Map<String, Object>) signal;
              assertThat(signalMap)
                  .containsEntry("workItemId", escalatedItem.id.toString())
                  .containsEntry("bindingName", "review-binding");
              assertThat(signalMap.get("newGroups"))
                  .asList()
                  .containsExactlyInAnyOrder("committee-a", "committee-b");
            });

    // PlanItem status must remain unchanged — ESCALATED is not terminal
    assertThat(planItem.getStatus())
        .as("ESCALATED must not change PlanItem status")
        .isEqualTo(PlanItemStatus.RUNNING);
  }

  @Test
  void workItemSuspended_marksPlanItemSuspended() {
    PlanItem delegatedItem = PlanItem.create("review-ht-suspend", "ht-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.SUSPENDED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.suspended", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.SUSPENDED));
  }

  @Test
  void workItemResumed_marksPlanItemDelegated() {
    PlanItem delegatedItem = PlanItem.create("review-ht-resume", "ht-worker", 10);
    delegatedItem.markDelegated();
    delegatedItem.markSuspended();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.IN_PROGRESS;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, delegatedItemId);
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.resumed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.DELEGATED));
  }

  @Test
  void workItemCancelled_marksPlanItemCancelled() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.CANCELLED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.CANCELLED));
  }

  @Test
  void nonTerminalStatus_ignored() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.IN_PROGRESS, null));

    // Give the async observer time to run if it were going to
    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.RUNNING);
  }

  @Test
  void unknownCallerRef_ignored() {
    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = "some-other-system:xyz";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.RUNNING);
  }

  @Test
  void missingCallerRef_ignored() {
    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = null;

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.RUNNING);
  }

  @Test
  void workItemCompleted_withOutputMapping_updatesCaseContext() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").outputMapping("{ irbOutcome: .decision }").build();
    PlanItem htPlanItem = PlanItem.create("review-binding-ht", "ht-worker", 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, htPlanItem.getPlanItemId());
    workItem.resolution = "{ \"decision\": \"Approved\" }";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(htPlanItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));

    // CaseContext should be updated with outputMapping result
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  reactiveCaseInstanceRepository
                      .findByUuid(caseId, "test-tenant")
                      .await()
                      .atMost(Duration.ofSeconds(2));
              assertThat(updated.getCaseContext().get("irbOutcome")).isEqualTo("Approved");
            });
  }

  @Test
  void workItemCompleted_withFailingOutputMapping_planItemStillCompletes() {
    // outputMapping evaluator with invalid expression — should warn, not fail the transition
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").outputMapping("not-a-valid-template").build();
    PlanItem htPlanItem = PlanItem.create("review-binding-fail", "ht-worker", 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, htPlanItem.getPlanItemId());
    workItem.resolution = "{}";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(htPlanItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));
  }

  @Test
  void workItemCompleted_withNestedOutputMapping_producesNestedMap() {
    // engine#314: { outer: { inner: .path } } must produce a nested Map, not a String literal
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Approval")
            .outputMapping("{ humanApproval: { status: .decision } }")
            .build();
    PlanItem htPlanItem = PlanItem.create("nested-mapping-binding", "ht-worker", 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, htPlanItem.getPlanItemId());
    workItem.resolution = "{ \"decision\": \"approved\" }";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(htPlanItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  reactiveCaseInstanceRepository
                      .findByUuid(caseId, "test-tenant")
                      .await()
                      .atMost(Duration.ofSeconds(2));
              Object humanApproval = updated.getCaseContext().get("humanApproval");
              assertThat(humanApproval).isInstanceOf(Map.class);
              @SuppressWarnings("unchecked")
              Map<String, Object> approvalMap = (Map<String, Object>) humanApproval;
              assertThat(approvalMap).containsEntry("status", "approved");
            });
  }

  @Test
  void workItemCompleted_noTarget_noContextUpdate() {
    // PlanItem with no target (no outputMapping) — baseline: existing context unchanged
    CaseInstance before =
        reactiveCaseInstanceRepository
            .findByUuid(caseId, "test-tenant")
            .await()
            .atMost(Duration.ofSeconds(2));
    Map<String, Object> originalData = new HashMap<>(before.getCaseContext().getData());

    // Use the pre-existing planItem from setUp (no target)
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.COMPLETED, "anything"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));

    CaseInstance after =
        reactiveCaseInstanceRepository
            .findByUuid(caseId, "test-tenant")
            .await()
            .atMost(Duration.ofSeconds(2));
    assertThat(after.getCaseContext().getData()).isEqualTo(originalData);
  }

  @Test
  void workItemGroupCompleted_marksPlanItemCompleted() {
    groupLifecycleEvents.fireAsync(buildGroupEvent(GroupStatus.COMPLETED));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.COMPLETED));
  }

  @Test
  void workItemGroupRejected_marksPlanItemRejected() {
    // Group threshold unreachable — group PlanItems are always DELEGATED (HumanTask SpawnGroup)
    PlanItem delegatedItem = PlanItem.create("group-binding", "group-worker", 10);
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    groupLifecycleEvents.fireAsync(
        WorkItemGroupLifecycleEvent.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            2,
            0,
            3,
            GroupStatus.REJECTED,
            PlanItemCallerRef.encode(caseId, delegatedItemId),
            "test-tenant"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(delegatedItem.getStatus()).isEqualTo(PlanItemStatus.REJECTED));
  }

  @Test
  void workItemGroupInProgress_isIgnored() {
    groupLifecycleEvents.fireAsync(buildGroupEvent(GroupStatus.IN_PROGRESS));

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus())
        .as("IN_PROGRESS group event must not change PlanItem status")
        .isEqualTo(PlanItemStatus.RUNNING);
  }

  @Test
  void workItemGroupCompleted_unknownCallerRef_isIgnored() {
    WorkItemGroupLifecycleEvent event =
        WorkItemGroupLifecycleEvent.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            2,
            2,
            0,
            GroupStatus.COMPLETED,
            "some-other-system:xyz",
            "test-tenant");

    groupLifecycleEvents.fireAsync(event);

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus())
        .as("Unknown callerRef must be ignored for group events")
        .isEqualTo(PlanItemStatus.RUNNING);
  }

  private WorkItemLifecycleEvent buildEvent(WorkItemStatus status, String resolution) {
    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = status;
    workItem.callerRef = PlanItemCallerRef.encode(caseId, planItemId);
    workItem.resolution = resolution;
    return WorkItemLifecycleEvent.of(
        "workitem." + status.name().toLowerCase(), workItem, "system", null);
  }

  private WorkItemGroupLifecycleEvent buildGroupEvent(GroupStatus status) {
    return WorkItemGroupLifecycleEvent.of(
        UUID.randomUUID(),
        UUID.randomUUID(),
        3,
        2,
        2,
        0,
        status,
        PlanItemCallerRef.encode(caseId, planItemId),
        "test-tenant");
  }
}
