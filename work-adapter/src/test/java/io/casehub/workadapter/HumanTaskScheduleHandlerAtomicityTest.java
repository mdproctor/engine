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

import io.casehub.api.model.HumanTaskTarget;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.internal.event.EventBusAddresses;
import io.casehub.engine.internal.event.HumanTaskScheduleEvent;
import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.spi.PlanItemStore;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import io.casehub.persistence.memory.InMemoryCaseMetaModelRepository;
import io.casehub.persistence.memory.InMemoryEventLogRepository;
import io.casehub.persistence.memory.MemoryPlanItemStore;
import io.casehub.persistence.memory.MemorySubCaseGroupRepository;
import io.casehub.work.runtime.repository.WorkItemQuery;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a WorkItem creation failure leaves PlanItem PENDING and does not write a RUNNING
 * status to the store (atomicity guarantee, engine#273). Runs under its own QuarkusTestProfile so
 * FailingWorkItemStore is activated only for this class.
 *
 * <p>Refs engine#282, engine#273.
 */
@QuarkusTest
@TestProfile(HumanTaskScheduleHandlerAtomicityTest.Profile.class)
class HumanTaskScheduleHandlerAtomicityTest {

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
      // getEnabledAlternatives() replaces quarkus.arc.selected-alternatives — must include all
      // alternatives required for deployment, not just the ones specific to this test.
      return Set.of(
          FailingWorkItemStore.class,
          InMemoryCaseInstanceRepository.class,
          InMemoryCaseMetaModelRepository.class,
          InMemoryEventLogRepository.class,
          MemorySubCaseGroupRepository.class,
          MemoryPlanItemStore.class,
          NoOpLedgerEntryRepository.class,
          NoOpReactiveLedgerEntryRepository.class);
    }
  }

  /**
   * WorkItemStore substitute that throws on put() when shouldFail is true. Activated only via
   * Profile — not listed in the global selected-alternatives.
   */
  @ApplicationScoped
  @Alternative
  @Priority(2)
  public static class FailingWorkItemStore implements WorkItemStore {

    public static final AtomicBoolean shouldFail = new AtomicBoolean(false);
    public static volatile CountDownLatch putAttemptLatch = new CountDownLatch(1);

    private final java.util.Map<UUID, io.casehub.work.runtime.model.WorkItem> store =
        new ConcurrentHashMap<>();

    public void clear() {
      store.clear();
    }

    @Override
    public io.casehub.work.runtime.model.WorkItem put(io.casehub.work.runtime.model.WorkItem w) {
      putAttemptLatch.countDown(); // signal that handler reached put() before any throw
      if (shouldFail.get()) throw new RuntimeException("Simulated WorkItemStore failure");
      if (w.id == null) w.id = UUID.randomUUID();
      store.put(w.id, w);
      return w;
    }

    @Override
    public Optional<io.casehub.work.runtime.model.WorkItem> get(UUID id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<io.casehub.work.runtime.model.WorkItem> scan(WorkItemQuery query) {
      return new ArrayList<>(store.values());
    }
  }

  @Inject BlackboardRegistry registry;
  @Inject EventBus eventBus;
  @Inject WorkItemStore workItemStore;
  @Inject PlanItemStore planItemStore;

  private UUID caseId;
  private PlanItem planItem;

  @BeforeEach
  @Transactional
  void setUp() {
    assertThat(workItemStore).isInstanceOf(FailingWorkItemStore.class);
    FailingWorkItemStore failing = (FailingWorkItemStore) workItemStore;
    failing.clear();
    FailingWorkItemStore.shouldFail.set(false);
    FailingWorkItemStore.putAttemptLatch = new CountDownLatch(1);
    if (planItemStore instanceof MemoryPlanItemStore mem) {
      mem.clear();
    }
    caseId = UUID.randomUUID();
    planItem = PlanItem.create("irb-binding", "unused-worker", 5);
    registry.getOrCreate(caseId).addPlanItem(planItem);
  }

  @AfterEach
  void tearDown() {
    registry.evict(caseId);
  }

  @Test
  void inlineMode_workItemCreationFails_planItemStaysPending_storeNotUpdated() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();

    FailingWorkItemStore.shouldFail.set(true);
    try {
      eventBus.publish(
          EventBusAddresses.HUMAN_TASK_SCHEDULE,
          new HumanTaskScheduleEvent(caseId, "irb-binding", target, Map.of(), null));

      try {
        assertThat(FailingWorkItemStore.putAttemptLatch.await(5, TimeUnit.SECONDS))
            .as("Handler must attempt WorkItemStore.put() within 5 seconds")
            .isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted waiting for put() attempt", e);
      }

      assertThat(planItem.getStatus()).isEqualTo(PlanItemStatus.PENDING);
      assertThat(workItemStore.scanAll()).isEmpty();
      List<PlanItemRecord> records = planItemStore.findByCaseId(caseId);
      assertThat(records)
          .noneMatch(
              r ->
                  r.planItemId().equals(planItem.getPlanItemId())
                      && r.status() == PlanItemStatus.RUNNING);
    } finally {
      FailingWorkItemStore.shouldFail.set(false);
    }
  }
}
