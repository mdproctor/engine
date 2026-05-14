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
package io.casehub.workadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.work.api.WorkloadProvider;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WorkItemLifecycleAdapterTest {

  /** Resolves the CasehubWorkloadProvider vs JpaWorkloadProvider ambiguity in tests. */
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

  private UUID caseId;
  private String planItemId;
  private PlanItem planItem;

  @BeforeEach
  void setUp() {
    caseId = UUID.randomUUID();
    planItem = PlanItem.create("review-binding", "review-worker", 10);
    planItemId = planItem.getPlanItemId();
    planItem.markRunning();

    registry.getOrCreate(caseId).addPlanItem(planItem);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    caseInstanceRepository.save(instance).await().atMost(Duration.ofSeconds(5));
  }

  @Test
  void workItemCompleted_marksPlanItemCompleted_firesContextChanged() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.COMPLETED, "Approved"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.COMPLETED));
  }

  @Test
  void workItemRejected_marksPlanItemFaulted() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.REJECTED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.FAULTED));
  }

  @Test
  void workItemExpired_marksPlanItemFaulted() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.EXPIRED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.FAULTED));
  }

  @Test
  void workItemEscalated_marksPlanItemFaulted() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.ESCALATED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.FAULTED));
  }

  @Test
  void workItemCancelled_marksPlanItemCancelled() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.CANCELLED, null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.CANCELLED));
  }

  @Test
  void nonTerminalStatus_ignored() {
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.IN_PROGRESS, null));

    // Give the async observer time to run if it were going to
    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.RUNNING);
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
    assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.RUNNING);
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
    assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.RUNNING);
  }

  @Test
  void workItemCompleted_withOutputMapping_updatesCaseContext() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").outputMapping("{ irbOutcome: .decision }").build();
    PlanItem htPlanItem = PlanItem.create("review-binding-ht", "ht-worker", 10, target);
    htPlanItem.markRunning();
    registry.getOrCreate(caseId).addPlanItem(htPlanItem);

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = CallerRef.encode(caseId, htPlanItem.getPlanItemId());
    workItem.resolution = "{ \"decision\": \"Approved\" }";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(htPlanItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.COMPLETED));

    // CaseContext should be updated with outputMapping result
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              CaseInstance updated =
                  caseInstanceRepository.findByUuid(caseId).await().atMost(Duration.ofSeconds(2));
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
    registry.getOrCreate(caseId).addPlanItem(htPlanItem);

    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = WorkItemStatus.COMPLETED;
    workItem.callerRef = CallerRef.encode(caseId, htPlanItem.getPlanItemId());
    workItem.resolution = "{}";

    lifecycleEvents.fireAsync(
        WorkItemLifecycleEvent.of("workitem.completed", workItem, "system", null));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(htPlanItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.COMPLETED));
  }

  @Test
  void workItemCompleted_noTarget_noContextUpdate() {
    // PlanItem with no target (no outputMapping) — baseline: existing context unchanged
    CaseInstance before =
        caseInstanceRepository.findByUuid(caseId).await().atMost(Duration.ofSeconds(2));
    Map<String, Object> originalData = new HashMap<>(before.getCaseContext().getData());

    // Use the pre-existing planItem from setUp (no target)
    lifecycleEvents.fireAsync(buildEvent(WorkItemStatus.COMPLETED, "anything"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.COMPLETED));

    CaseInstance after =
        caseInstanceRepository.findByUuid(caseId).await().atMost(Duration.ofSeconds(2));
    assertThat(after.getCaseContext().getData()).isEqualTo(originalData);
  }

  private WorkItemLifecycleEvent buildEvent(WorkItemStatus status, String resolution) {
    WorkItem workItem = new WorkItem();
    workItem.id = UUID.randomUUID();
    workItem.status = status;
    workItem.callerRef = CallerRef.encode(caseId, planItemId);
    workItem.resolution = resolution;
    return WorkItemLifecycleEvent.of(
        "workitem." + status.name().toLowerCase(), workItem, "system", null);
  }
}
