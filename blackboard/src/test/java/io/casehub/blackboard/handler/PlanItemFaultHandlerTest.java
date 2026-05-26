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
package io.casehub.blackboard.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import jakarta.enterprise.event.Event;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PlanItemFaultHandler — marks PlanItems FAULTED when worker retries are exhausted.
 * Closes casehubio/engine#331.
 */
class PlanItemFaultHandlerTest {

  private BlackboardRegistry registry;
  private PlanItemFaultHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    handler = new PlanItemFaultHandler(registry, mock(Event.class));
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId);
  }

  private WorkerRetriesExhaustedEvent eventFor(String workerId) {
    return new WorkerRetriesExhaustedEvent(caseId, workerId, "idempotency-key");
  }

  @Test
  void marks_running_plan_item_faulted_on_retries_exhausted() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(eventFor("worker-a")).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void marks_delegated_plan_item_faulted_on_retries_exhausted() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markDelegated();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(eventFor("worker-a")).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void marks_pending_plan_item_faulted_on_retries_exhausted() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(eventFor("worker-a")).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void skips_already_completed_plan_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning();
    item.markCompleted();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(eventFor("worker-a")).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.COMPLETED);
  }

  @Test
  void skips_already_faulted_plan_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning();
    item.markFaulted();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(eventFor("worker-a")).await().indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void unknown_worker_does_not_throw() {
    handler.onWorkerRetriesExhausted(eventFor("unknown-worker")).await().indefinitely();
  }

  @Test
  void no_plan_registered_does_not_throw() {
    UUID otherCase = UUID.randomUUID();
    handler
        .onWorkerRetriesExhausted(new WorkerRetriesExhaustedEvent(otherCase, "w", "key"))
        .await()
        .indefinitely();
  }
}
