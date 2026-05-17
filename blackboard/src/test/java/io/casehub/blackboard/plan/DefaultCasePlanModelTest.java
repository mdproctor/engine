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
package io.casehub.blackboard.plan;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.blackboard.stage.Stage;
import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.spi.PlanItemStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DefaultCasePlanModel agenda management and milestone tracking. See
 * casehubio/engine#76. Milestone/Goal/Stage alignment: casehubio/engine#84.
 */
class DefaultCasePlanModelTest {

  private DefaultCasePlanModel plan;

  @BeforeEach
  void setUp() {
    plan = new DefaultCasePlanModel(UUID.randomUUID());
  }

  @Test
  void agenda_returns_only_pending_items_sorted_by_priority() {
    PlanItem low = PlanItem.create("b-low", "w-low", 1);
    PlanItem high = PlanItem.create("b-high", "w-high", 10);
    PlanItem running = PlanItem.create("b-run", "w-run", 99);
    running.markRunning();

    plan.addPlanItem(low);
    plan.addPlanItem(high);
    plan.addPlanItem(running);

    List<PlanItem> agenda = plan.getAgenda();
    assertThat(agenda).hasSize(2);
    assertThat(agenda.get(0).getBindingName()).isEqualTo("b-high");
    assertThat(agenda.get(1).getBindingName()).isEqualTo("b-low");
  }

  @Test
  void getTopPlanItems_respects_limit() {
    for (int i = 0; i < 5; i++) {
      plan.addPlanItem(PlanItem.create("b-" + i, "w-" + i, i));
    }
    assertThat(plan.getTopPlanItems(3)).hasSize(3);
  }

  @Test
  void getTopPlanItems_handles_limit_larger_than_agenda() {
    plan.addPlanItem(PlanItem.create("b-a", "w-a", 0));
    assertThat(plan.getTopPlanItems(100)).hasSize(1);
  }

  @Test
  void getPlanItem_returns_by_id() {
    PlanItem item = PlanItem.create("b-a", "w-a", 0);
    plan.addPlanItem(item);
    assertThat(plan.getPlanItem(item.getPlanItemId())).contains(item);
  }

  @Test
  void removePlanItem_removes_from_agenda() {
    PlanItem item = PlanItem.create("b-a", "w-a", 0);
    plan.addPlanItem(item);
    plan.removePlanItem(item.getPlanItemId());
    assertThat(plan.getAgenda()).isEmpty();
  }

  @Test
  void milestone_lifecycle_pending_to_achieved() {
    plan.trackMilestone("docs-received");
    assertThat(plan.isMilestoneAchieved("docs-received")).isFalse();
    plan.achieveMilestone("docs-received");
    assertThat(plan.isMilestoneAchieved("docs-received")).isTrue();
  }

  @Test
  void achieve_untracked_milestone_does_not_throw() {
    // achieveMilestone records regardless of prior trackMilestone — no exception expected
    plan.achieveMilestone("unknown");
  }

  @Test
  void achieveMilestone_before_trackMilestone_still_records_achievement() {
    plan.achieveMilestone("docs-received");
    assertThat(plan.isMilestoneAchieved("docs-received"))
        .as("achieveMilestone must record regardless of trackMilestone call order")
        .isTrue();
  }

  @Test
  void trackMilestone_after_achieve_sees_already_achieved() {
    plan.achieveMilestone("docs-received");
    plan.trackMilestone("docs-received");
    assertThat(plan.isMilestoneAchieved("docs-received")).isTrue();
  }

  @Test
  void focus_and_rationale_roundtrip() {
    plan.setFocus("analysis");
    plan.setFocusRationale("high-value documents detected");
    assertThat(plan.getFocus()).contains("analysis");
    assertThat(plan.getFocusRationale()).contains("high-value documents detected");
  }

  @Test
  void extensible_kv_roundtrip() {
    plan.put("custom-key", 42);
    assertThat(plan.get("custom-key", Integer.class)).contains(42);
  }

  @Test
  void hasActivePlanItem_true_for_pending_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItem(item);
    assertThat(plan.hasActivePlanItem("binding-a")).isTrue();
  }

  @Test
  void hasActivePlanItem_true_for_running_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    item.markRunning();
    plan.addPlanItem(item);
    assertThat(plan.hasActivePlanItem("binding-a")).isTrue();
  }

  @Test
  void hasActivePlanItem_false_for_completed_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    item.markRunning();
    item.markCompleted();
    plan.addPlanItem(item);
    assertThat(plan.hasActivePlanItem("binding-a")).isFalse();
  }

  @Test
  void hasActivePlanItem_false_when_no_item() {
    assertThat(plan.hasActivePlanItem("binding-a")).isFalse();
  }

  @Test
  void hasActivePlanItem_false_for_faulted_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    item.markFaulted();
    plan.addPlanItem(item);
    assertThat(plan.hasActivePlanItem("binding-a")).isFalse();
  }

  @Test
  void stage_management_add_and_retrieve() {
    Stage stage = Stage.alwaysActivate("intake");
    plan.addStage(stage);
    assertThat(plan.getStage(stage.getStageId())).contains(stage);
    assertThat(plan.getAllStages()).containsExactly(stage);
  }

  @Test
  void getPendingStages_and_getActiveStages_filter_by_status() {
    Stage pending = Stage.alwaysActivate("pending-stage");
    Stage active = Stage.alwaysActivate("active-stage");
    active.activate();
    plan.addStage(pending);
    plan.addStage(active);
    assertThat(plan.getPendingStages()).containsExactly(pending);
    assertThat(plan.getActiveStages()).containsExactly(active);
  }

  @Test
  void addPlanItemIfAbsent_returns_true_when_no_active_item() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    assertThat(plan.addPlanItemIfAbsent(item)).isTrue();
    assertThat(plan.getAgenda()).hasSize(1);
  }

  @Test
  void addPlanItemIfAbsent_returns_false_when_pending_item_exists() {
    PlanItem first = PlanItem.create("binding-a", "worker-a", 0);
    PlanItem second = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItemIfAbsent(first);
    assertThat(plan.addPlanItemIfAbsent(second)).isFalse();
    assertThat(plan.getAgenda()).hasSize(1);
  }

  @Test
  void addPlanItemIfAbsent_returns_false_when_running_item_exists() {
    PlanItem item = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItemIfAbsent(item);
    item.markRunning();
    PlanItem second = PlanItem.create("binding-a", "worker-a", 0);
    assertThat(plan.addPlanItemIfAbsent(second)).isFalse();
  }

  @Test
  void addPlanItemIfAbsent_returns_true_when_prior_item_is_completed() {
    PlanItem first = PlanItem.create("binding-a", "worker-a", 0);
    plan.addPlanItemIfAbsent(first);
    first.markRunning();
    first.markCompleted();
    PlanItem second = PlanItem.create("binding-a", "worker-a", 0);
    assertThat(plan.addPlanItemIfAbsent(second)).isTrue();
  }

  @Test
  void concurrent_addPlanItemIfAbsent_for_same_binding_adds_exactly_one() throws Exception {
    int threads = 10;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
    java.util.concurrent.atomic.AtomicInteger addedCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    for (int i = 0; i < threads; i++) {
      int idx = i;
      Thread t =
          new Thread(
              () -> {
                try {
                  start.await();
                  PlanItem item = PlanItem.create("binding-a", "worker-" + idx, 0);
                  if (plan.addPlanItemIfAbsent(item)) addedCount.incrementAndGet();
                } catch (InterruptedException ignored) {
                } finally {
                  done.countDown();
                }
              });
      t.start();
    }

    start.countDown();
    done.await(5, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(addedCount.get())
        .as("Exactly one thread should have added the PlanItem")
        .isEqualTo(1);
    assertThat(plan.getAgenda()).hasSize(1);
  }

  @Test
  void getTopPlanItems_with_zero_limit_returns_empty() {
    plan.addPlanItem(PlanItem.create("b-a", "w-a", 5));
    plan.addPlanItem(PlanItem.create("b-b", "w-b", 3));
    assertThat(plan.getTopPlanItems(0))
        .as("getTopPlanItems(0) must return empty, not all items")
        .isEmpty();
  }

  @Test
  void get_returns_empty_when_type_does_not_match() {
    plan.put("count", 42);
    assertThat(plan.get("count", String.class))
        .as("get() must return empty when stored type does not match — no ClassCastException")
        .isEmpty();
    assertThat(plan.get("count", Integer.class)).contains(42);
  }

  // ---------------------------------------------------------------------------
  // PlanItemStore integration tests
  // ---------------------------------------------------------------------------

  static class RecordingPlanItemStore implements PlanItemStore {
    final List<PlanItemRecord> saved = new ArrayList<>();

    @Override
    public void save(
        UUID caseId,
        String planItemId,
        String bindingName,
        PlanItemStatus status,
        Instant createdAt) {
      saved.add(new PlanItemRecord(caseId, planItemId, bindingName, status, createdAt));
    }

    @Override
    public void updateStatus(String planItemId, PlanItemStatus status) {}

    @Override
    public List<PlanItemRecord> findByCaseId(UUID caseId) {
      return saved;
    }
  }

  @Test
  void addPlanItem_saves_to_store() {
    UUID caseId = UUID.randomUUID();
    RecordingPlanItemStore store = new RecordingPlanItemStore();
    DefaultCasePlanModel model = new DefaultCasePlanModel(caseId, store);

    PlanItem item = PlanItem.create("my-binding", "my-worker", 5);
    model.addPlanItem(item);

    assertThat(store.saved).hasSize(1);
    assertThat(store.saved.get(0).planItemId()).isEqualTo(item.getPlanItemId());
    assertThat(store.saved.get(0).status()).isEqualTo(PlanItemStatus.PENDING);
  }

  @Test
  void addPlanItemIfAbsent_saves_to_store_when_added() {
    UUID caseId = UUID.randomUUID();
    RecordingPlanItemStore store = new RecordingPlanItemStore();
    DefaultCasePlanModel model = new DefaultCasePlanModel(caseId, store);

    PlanItem item = PlanItem.create("my-binding", "my-worker", 5);
    boolean added = model.addPlanItemIfAbsent(item);

    assertThat(added).isTrue();
    assertThat(store.saved).hasSize(1);
  }

  @Test
  void addPlanItemIfAbsent_does_not_save_when_already_active() {
    UUID caseId = UUID.randomUUID();
    RecordingPlanItemStore store = new RecordingPlanItemStore();
    DefaultCasePlanModel model = new DefaultCasePlanModel(caseId, store);

    PlanItem item = PlanItem.create("my-binding", "my-worker", 5);
    model.addPlanItem(item);
    store.saved.clear();

    boolean added = model.addPlanItemIfAbsent(PlanItem.create("my-binding", "my-worker", 5));
    assertThat(added).isFalse();
    assertThat(store.saved).isEmpty();
  }
}
