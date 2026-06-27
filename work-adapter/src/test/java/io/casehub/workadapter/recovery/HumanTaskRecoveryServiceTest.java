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
package io.casehub.workadapter.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.persistence.memory.MemoryPlanItemStore;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.workadapter.PlanItemCallerRef;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HumanTaskRecoveryServiceTest {

  @Inject HumanTaskRecoveryService recoveryService;
  @Inject BlackboardRegistry registry;
  @Inject PlanItemStore planItemStore;
  @Inject WorkItemStore workItemStore;
  @Inject CaseInstanceRepository caseInstanceRepository;
  @Inject EventBus eventBus;

  private UUID caseId;
  private String planItemId;
  private String callerRef;

  @BeforeEach
  void setUp() {
    if (planItemStore instanceof MemoryPlanItemStore mem) mem.clear();
    if (workItemStore instanceof InMemoryWorkItemStore mem) mem.clear();
    caseId = UUID.randomUUID();
    planItemId = UUID.randomUUID().toString();
    callerRef = PlanItemCallerRef.encode(caseId, planItemId);

    planItemStore.save(
        new PlanItemSaveRequest(
            caseId,
            planItemId,
            "review-task",
            PlanItemStatus.DELEGATED,
            Instant.now(),
            TargetType.HUMAN_TASK,
            null,
            "test-tenant"),
        "test-tenant");

    // Set up CaseInstance so PlanItemCompletionApplier can fire CONTEXT_CHANGED
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.setState(io.casehub.api.model.CaseStatus.RUNNING);
    instance.setCaseContext(new CaseContextImpl(Map.of("stage", "review")));
    caseInstanceRepository.save(instance, "test-tenant").await().atMost(Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    if (planItemStore instanceof MemoryPlanItemStore mem) mem.clear();
    if (workItemStore instanceof InMemoryWorkItemStore mem) mem.clear();
    registry.evict(caseId);
  }

  @Test
  void onStart_transitionsPlanItemWhenWorkItemIsCompleted() {
    createWorkItem(callerRef, WorkItemStatus.COMPLETED);

    AtomicBoolean contextChangedFired = new AtomicBoolean(false);
    eventBus.consumer(EventBusAddresses.CONTEXT_CHANGED, msg -> contextChangedFired.set(true));

    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);

    await().atMost(Duration.ofSeconds(2)).untilTrue(contextChangedFired);
  }

  @Test
  void onStart_transitionsPlanItemToRejectedWhenWorkItemIsRejected() {
    createWorkItem(callerRef, WorkItemStatus.REJECTED);
    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.REJECTED);
  }

  @Test
  void onStart_transitionsPlanItemToFaultedWhenWorkItemIsExpired() {
    createWorkItem(callerRef, WorkItemStatus.EXPIRED);
    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void onStart_skipsWhenWorkItemIsStillInFlight() {
    createWorkItem(callerRef, WorkItemStatus.IN_PROGRESS);
    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
  }

  @Test
  void onStart_skipsWhenNoMatchingWorkItemFound() {
    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.DELEGATED);
  }

  @Test
  void onStart_isIdempotent_whenPlanItemAlreadyTerminal() {
    createWorkItem(callerRef, WorkItemStatus.COMPLETED);

    recoveryService.onStart(new StartupEvent());
    recoveryService.onStart(new StartupEvent());

    PlanItem item = registry.get(caseId).flatMap(plan -> plan.getPlanItem(planItemId)).orElse(null);
    assertThat(item).isNotNull();
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }

  private WorkItem createWorkItem(String ref, WorkItemStatus status) {
    WorkItem w = new WorkItem();
    w.id = UUID.randomUUID();
    w.callerRef = ref;
    w.title = "Test WorkItem";
    w.status = status;
    w.createdAt = Instant.now();
    workItemStore.put(w);
    return w;
  }
}
