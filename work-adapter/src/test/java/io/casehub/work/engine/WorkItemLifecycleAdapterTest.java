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

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.runtime.context.CaseContextImpl;
import io.casehub.work.api.GroupStatus;
import io.casehub.work.api.WorkItem;
import io.casehub.work.api.WorkItemGroupLifecycleEvent;
import io.casehub.work.api.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkloadProvider;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
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

  @Inject CaseInstanceRepository caseInstanceRepository;

  @Inject Event<WorkItemLifecycleEvent> lifecycleEvents;

  @Inject Event<WorkItemGroupLifecycleEvent> groupLifecycleEvents;

  private UUID caseId;
  private String planItemId;
  private PlanItem planItem;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    planItem =
        PlanItem.create("review-binding", io.casehub.api.model.ExecutorRef.of("review-worker"), 10);
    planItemId = planItem.getPlanItemId();
    planItem.markRunning();

    registry.getOrCreate(caseId, "test-tenant").addPlanItem(planItem);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    caseInstanceRepository.save(instance, "test-tenant");
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
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));
  }

  @Test
  void workItemRejected_marksPlanItemRejected() {
    // Human task refusal — PlanItem must be DELEGATED (human task lifecycle)
    PlanItem delegatedItem =
        PlanItem.create("review-ht", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.REJECTED)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.rejected", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.REJECTED));
  }

  @Test
  void workItemExpired_marksPlanItemFaulted() {
    // Deadline expiry — a time-based failure, maps to FAULTED
    PlanItem delegatedItem =
        PlanItem.create("review-ht-expired", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.EXPIRED)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.expired", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.FAULTED));
  }

  @Test
  void workItemFaulted_marksPlanItemFaulted() {
    PlanItem delegatedItem =
        PlanItem.create("review-ht-faulted", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.FAULTED)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.faulted", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.FAULTED));
  }

  @Test
  void workItemObsolete_marksPlanItemObsolete() {
    PlanItem delegatedItem =
        PlanItem.create("review-ht-obsolete", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.OBSOLETE)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.obsolete", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.OBSOLETE));
  }

  @Test
  void workItemEscalated_routesToApplier_marksPlanItemFaulted() {
    PlanItem delegatedItem =
        PlanItem.create("escalation-ht", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem escalatedItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.ESCALATED)
            .candidateGroups("committee-a,committee-b")
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.escalated", escalatedItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.FAULTED));

    CaseInstance updated = caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
    Object signal = updated.getCaseContext().get("workItemEscalated");
    assertThat(signal).isNotNull().isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> signalMap = (Map<String, Object>) signal;
    assertThat(signalMap)
        .containsEntry("workItemId", escalatedItem.id().toString())
        .containsEntry("bindingName", "escalation-ht");
    assertThat(signalMap.get("lastCandidateGroups"))
        .asList()
        .containsExactlyInAnyOrder("committee-a", "committee-b");
  }

  @Test
  void workItemSuspended_marksPlanItemSuspended() {
    PlanItem delegatedItem =
        PlanItem.create("review-ht-suspend", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.SUSPENDED)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.suspended", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.SUSPENDED));
  }

  @Test
  void workItemResumed_marksPlanItemDelegated() {
    PlanItem delegatedItem =
        PlanItem.create("review-ht-resume", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10);
    delegatedItem.tryMarkDispatching();
    delegatedItem.markDelegated();
    delegatedItem.markSuspended();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(delegatedItem);
    String delegatedItemId = delegatedItem.getPlanItemId();

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.IN_PROGRESS)
            .callerRef(PlanItemRef.encode(caseId, delegatedItemId))
            .build();
    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.resumed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.DELEGATED));
  }

  @Test
  void workItemCancelled_marksPlanItemCancelled() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.CANCELLED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(TaskStatus.CANCELLED));
  }

  @Test
  void nonTerminalStatus_ignored() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.IN_PROGRESS, null));

    // Give the async observer time to run if it were going to
    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void unknownCallerRef_ignored() {
    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.COMPLETED)
            .callerRef("some-other-system:xyz")
            .build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void missingCallerRef_ignored() {
    WorkItem workItem =
        WorkItem.builder().id(UUID.randomUUID()).status(WorkItemStatus.COMPLETED).build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void workItemCompleted_withOutputMapping_updatesCaseContext() {
    io.casehub.api.model.JudgmentTarget target =
        io.casehub.api.model.JudgmentTarget.builder()
            .prompt("Review")
            .title("Review")
            .outputMapping("{ irbOutcome: .decision }")
            .build();
    PlanItem htPlanItem =
        PlanItem.create(
            "review-binding-ht", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.COMPLETED)
            .callerRef(PlanItemRef.encode(caseId, htPlanItem.getPlanItemId()))
            .resolution("{ \"decision\": \"Approved\" }")
            .build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(htPlanItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));

    // CaseContext should be updated with outputMapping result
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
              assertThat(updated.getCaseContext().get("irbOutcome")).isEqualTo("Approved");
            });
  }

  @Test
  void workItemCompleted_withFailingOutputMapping_planItemStillCompletes() {
    // outputMapping evaluator with invalid expression — should warn, not fail the transition
    io.casehub.api.model.JudgmentTarget target =
        io.casehub.api.model.JudgmentTarget.builder()
            .prompt("Review")
            .title("Review")
            .outputMapping("not-a-valid-template")
            .build();
    PlanItem htPlanItem =
        PlanItem.create(
            "review-binding-fail", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.COMPLETED)
            .callerRef(PlanItemRef.encode(caseId, htPlanItem.getPlanItemId()))
            .resolution("{}")
            .build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(htPlanItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));
  }

  @Test
  void workItemCompleted_withNestedOutputMapping_producesNestedMap() {
    // engine#314: { outer: { inner: .path } } must produce a nested Map, not a String literal
    io.casehub.api.model.JudgmentTarget target =
        io.casehub.api.model.JudgmentTarget.builder()
            .prompt("Approval")
            .title("Approval")
            .outputMapping("{ humanApproval: { status: .decision } }")
            .build();
    PlanItem htPlanItem =
        PlanItem.create(
            "nested-mapping-binding", io.casehub.api.model.ExecutorRef.of("ht-worker"), 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId, "test-tenant").addPlanItem(htPlanItem);

    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(WorkItemStatus.COMPLETED)
            .callerRef(PlanItemRef.encode(caseId, htPlanItem.getPlanItemId()))
            .resolution("{ \"decision\": \"approved\" }")
            .build();

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(htPlanItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
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
    CaseInstance before = caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
    Map<String, Object> originalData = new HashMap<>(before.getCaseContext().getData());

    // Use the pre-existing planItem from setUp (no target)
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.COMPLETED, "anything"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));

    CaseInstance after = caseInstanceRepository.findByUuid(caseId, "test-tenant").orElse(null);
    assertThat(after.getCaseContext().getData()).isEqualTo(originalData);
  }

  @Test
  void workItemGroupCompleted_marksPlanItemCompleted() {
    groupLifecycleEvents.fireAsync(buildGroupEvent(GroupStatus.COMPLETED));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(planItem.getStatus()).isEqualTo(TaskStatus.COMPLETED));
  }

  @Test
  void workItemGroupRejected_marksPlanItemRejected() {
    // Group threshold unreachable — group PlanItems are always DELEGATED (HumanTask SpawnGroup)
    PlanItem delegatedItem =
        PlanItem.create("group-binding", io.casehub.api.model.ExecutorRef.of("group-worker"), 10);
    delegatedItem.tryMarkDispatching();
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
            PlanItemRef.encode(caseId, delegatedItemId),
            "test-tenant"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(delegatedItem.getStatus()).isEqualTo(TaskStatus.REJECTED));
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
        .isEqualTo(TaskStatus.RUNNING);
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
        .isEqualTo(TaskStatus.RUNNING);
  }

  private WorkItemLifecycleEvent buildEvent(WorkItemStatus status, String resolution) {
    WorkItem workItem =
        WorkItem.builder()
            .id(UUID.randomUUID())
            .status(status)
            .callerRef(PlanItemRef.encode(caseId, planItemId))
            .resolution(resolution)
            .build();
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
        PlanItemRef.encode(caseId, planItemId),
        "test-tenant");
  }
}
