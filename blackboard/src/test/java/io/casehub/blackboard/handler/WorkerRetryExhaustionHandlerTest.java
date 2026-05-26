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
import io.casehub.engine.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for WorkerRetryExhaustionHandler.
 *
 * <p>Proves: RUNNING PlanItem is marked FAULTED when its worker exhausts retries; workerId on the
 * event equals the tracking key in BlackboardRegistry (the invariant is documented in the spec).
 * Refs engine#331, engine#369.
 */
class WorkerRetryExhaustionHandlerTest {

  private BlackboardRegistry registry;
  private EventBus eventBus;
  private WorkerRetryExhaustionHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    eventBus = mock(EventBus.class);
    handler = new WorkerRetryExhaustionHandler(registry, new StageAutocompleteEvaluator(eventBus));
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId);
  }

  @Test
  void running_planItem_is_marked_faulted_on_retries_exhausted() {
    PlanItem item = PlanItem.create("capability-binding", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler
        .onWorkerRetriesExhausted(new WorkerRetriesExhaustedEvent(caseId, "worker-a", "hash-123"))
        .await()
        .indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void unknown_case_is_a_noop() {
    handler
        .onWorkerRetriesExhausted(
            new WorkerRetriesExhaustedEvent(UUID.randomUUID(), "worker-x", "hash"))
        .await()
        .indefinitely();
    // no exception
  }

  @Test
  void unknown_worker_is_a_noop() {
    handler
        .onWorkerRetriesExhausted(new WorkerRetriesExhaustedEvent(caseId, "unknown-worker", "hash"))
        .await()
        .indefinitely();
    // no exception
  }

  @Test
  void already_faulted_planItem_is_not_double_transitioned() {
    PlanItem item = PlanItem.create("capability-binding", "worker-a", 0);
    plan.addPlanItem(item);
    item.markRunning();
    item.markFaulted(); // already terminal
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler
        .onWorkerRetriesExhausted(new WorkerRetriesExhaustedEvent(caseId, "worker-a", "hash-123"))
        .await()
        .indefinitely();

    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.FAULTED); // unchanged, no throw
  }

  @Test
  void pending_planItem_is_not_transitioned() {
    // Guard-blocked path fires WORKER_RETRIES_EXHAUSTED before the job is submitted.
    // The PlanItem may still be PENDING if indexing happened before the guard check.
    PlanItem item = PlanItem.create("capability-binding", "worker-a", 0);
    plan.addPlanItem(item);
    // PENDING — no markRunning()
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler
        .onWorkerRetriesExhausted(new WorkerRetriesExhaustedEvent(caseId, "worker-a", "hash-123"))
        .await()
        .indefinitely();

    // PENDING is not RUNNING — handler must not transition it (guard fires before plan item
    // indexing in the guard-blocked path, so lookup returns empty; this tests a theoretical edge)
    assertThat(item.getStatus()).isEqualTo(PlanItemStatus.PENDING);
  }
}
