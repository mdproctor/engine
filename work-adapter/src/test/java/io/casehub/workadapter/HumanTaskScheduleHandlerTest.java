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
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.HumanTaskScheduleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemStatus;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.work.testing.InMemoryWorkItemStore;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies HumanTaskScheduleHandler: inline mode creates WorkItem with correct callerRef and
 * transitions PlanItem to RUNNING. Template mode coverage in WorkItemRoundTripTest (requires DB).
 * Refs engine#245.
 */
@QuarkusTest
class HumanTaskScheduleHandlerTest {

  @Inject BlackboardRegistry registry;
  @Inject EventBus eventBus;
  @Inject WorkItemStore workItemStore;

  private UUID caseId;
  private PlanItem planItem;

  @BeforeEach
  void setUp() {
    if (workItemStore instanceof InMemoryWorkItemStore mem) {
      mem.clear();
    }
    caseId = UUID.randomUUID();
    planItem = PlanItem.create("irb-binding", "unused-worker", 5);
    registry.getOrCreate(caseId).addPlanItem(planItem);
  }

  @Test
  void inlineMode_createsWorkItem_withCallerRef_andMarksPlanItemRunning() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("IRB Ethics Review")
            .candidateGroups(Set.of("ethics-committee"))
            .expiresIn(Duration.ofHours(72))
            .build();

    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(caseId, "irb-binding", target, Map.of("caseRef", "T-42")));

    String expectedCallerRef = CallerRef.encode(caseId, planItem.getPlanItemId());

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.RUNNING));

    WorkItem created =
        workItemStore.scanAll().stream()
            .filter(w -> expectedCallerRef.equals(w.callerRef))
            .findFirst()
            .orElse(null);
    assertThat(created).isNotNull();
    assertThat(created.status).isEqualTo(WorkItemStatus.PENDING);
    assertThat(created.title).isEqualTo("IRB Ethics Review");
  }

  @Test
  void noPlanForCaseId_eventIgnored() {
    UUID unknownCaseId = UUID.randomUUID();
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();

    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(unknownCaseId, "irb-binding", target, Map.of()));

    try {
      Thread.sleep(300);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void noPlanItemForBindingName_eventIgnored() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();

    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(caseId, "unknown-binding", target, Map.of()));

    try {
      Thread.sleep(300);
    } catch (InterruptedException ignored) {
    }
    assertThat(planItem.getStatus()).isEqualTo(PlanItem.PlanItemStatus.PENDING);
    assertThat(workItemStore.scanAll()).isEmpty();
  }
}
