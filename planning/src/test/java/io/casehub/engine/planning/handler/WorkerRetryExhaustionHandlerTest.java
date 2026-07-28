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
package io.casehub.engine.planning.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.RetryState;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.casehub.engine.common.spi.event.PlanItemFaultedEvent;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.event.Event;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for WorkerRetryExhaustionHandler.
 *
 * <p>Proves: RUNNING PlanItem is marked FAULTED when its worker exhausts retries; workerId on the
 * event equals the tracking key in BlackboardRegistry (the invariant is documented in the spec).
 * After consolidating PlanItemFaultHandler (engine#666), also proves PlanItemFaultedEvent is fired.
 *
 * <p>Refs engine#331, engine#369, engine#666.
 */
class WorkerRetryExhaustionHandlerTest {

  private BlackboardRegistry registry;
  private EventBus eventBus;
  private Event<PlanItemFaultedEvent> planItemFaultedEvents;
  private StageAutocompleteEvaluator stageAutocompleteEvaluator;
  private WorkerRetryExhaustionHandler handler;
  private UUID caseId;
  private DefaultCasePlanModel plan;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    eventBus = mock(EventBus.class);
    planItemFaultedEvents = mock(Event.class);
    stageAutocompleteEvaluator = mock(StageAutocompleteEvaluator.class);
    handler =
        new WorkerRetryExhaustionHandler(
            registry, stageAutocompleteEvaluator, planItemFaultedEvents);
    caseId = UUID.randomUUID();
    plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
  }

  @Test
  void running_planItem_is_marked_faulted_on_retries_exhausted() {
    PlanItem item = PlanItem.create("capability-binding", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            caseId,
            "test-tenant",
            "worker-a",
            "hash-123",
            "capability-binding",
            null,
            RetryState.empty()));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void unknown_case_is_a_noop() {
    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            UUID.randomUUID(), "test-tenant", "worker-x", "hash", null, null, RetryState.empty()));
    // no exception
  }

  @Test
  void unknown_worker_is_a_noop() {
    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            caseId, "test-tenant", "unknown-worker", "hash", null, null, RetryState.empty()));
    // no exception
  }

  @Test
  void already_faulted_planItem_is_not_double_transitioned() {
    PlanItem item = PlanItem.create("capability-binding", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    item.markFaulted(); // already terminal
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            caseId,
            "test-tenant",
            "worker-a",
            "hash-123",
            "capability-binding",
            null,
            RetryState.empty()));

    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED); // unchanged, no throw
  }

  @Test
  void pending_planItem_is_not_transitioned() {
    // Guard-blocked path fires WORKER_RETRIES_EXHAUSTED before the job is submitted.
    // The PlanItem may still be PENDING if indexing happened before the guard check.
    PlanItem item = PlanItem.create("capability-binding", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    // PENDING — no markRunning()
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            caseId,
            "test-tenant",
            "worker-a",
            "hash-123",
            "capability-binding",
            null,
            RetryState.empty()));

    // PENDING is not RUNNING — handler must not transition it (guard fires before plan item
    // indexing in the guard-blocked path, so lookup returns empty; this tests a theoretical edge)
    assertThat(item.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void onWorkerRetriesExhausted_firesBothPlanItemFaultedEventAndStageAutocomplete() {
    // Setup: create a RUNNING PlanItem
    PlanItem item = PlanItem.create("capability-binding", ExecutorRef.of("worker-a"), 0);
    plan.addPlanItem(item);
    item.markRunning();
    registry.indexForCompletion(caseId, "worker-a", item.getPlanItemId());

    // Act: call onWorkerRetriesExhausted
    handler.onWorkerRetriesExhausted(
        new WorkerRetriesExhaustedEvent(
            caseId,
            "test-tenant",
            "worker-a",
            "hash-123",
            "capability-binding",
            null,
            RetryState.empty()));

    // Assert: PlanItem marked FAULTED
    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);

    // Assert: PlanItemFaultedEvent fired via CDI
    verify(planItemFaultedEvents)
        .fireAsync(
            new PlanItemFaultedEvent(
                caseId, item.getPlanItemId(), "capability-binding", "test-tenant"));

    // Assert: stageAutocompleteEvaluator.evaluate() called
    verify(stageAutocompleteEvaluator).evaluate(caseId, "test-tenant", plan, item.getPlanItemId());
  }
}
