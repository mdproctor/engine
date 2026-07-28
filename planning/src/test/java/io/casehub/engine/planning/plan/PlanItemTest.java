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
package io.casehub.engine.planning.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.BindingTarget;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.worker.api.Capability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unit tests for PlanItem ordering and lifecycle. See casehubio/engine#76. */
class PlanItemTest {

  @Test
  void higher_priority_sorts_before_lower() {
    PlanItem low = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    PlanItem high = PlanItem.create("binding-b", ExecutorRef.of("worker-b"), 10);
    List<PlanItem> items = new ArrayList<>(List.of(low, high));
    Collections.sort(items);
    assertThat(items.get(0).getBindingName()).isEqualTo("binding-b");
  }

  @Test
  void equal_priority_earlier_creation_sorts_first() throws InterruptedException {
    PlanItem first = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 5);
    Thread.sleep(2);
    PlanItem second = PlanItem.create("binding-b", ExecutorRef.of("worker-b"), 5);
    List<PlanItem> items = new ArrayList<>(List.of(second, first));
    Collections.sort(items);
    assertThat(items.get(0).getBindingName()).isEqualTo("binding-a");
  }

  @Test
  void default_status_is_pending() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    assertThat(item.getStatus()).isEqualTo(TaskStatus.PENDING);
  }

  @Test
  void markRunning_from_pending_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void markRunning_from_running_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    assertThatThrownBy(item::markRunning).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void tryMarkRunning_fromPending_returnsTrue() {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    assertThat(item.tryMarkRunning()).isTrue();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void tryMarkRunning_fromRunning_returnsFalse() {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markRunning();
    assertThat(item.tryMarkRunning()).isFalse();
  }

  @Test
  void tryMarkRunning_fromCompleted_returnsFalse() {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markRunning();
    item.markCompleted();
    assertThat(item.tryMarkRunning()).isFalse();
  }

  @Test
  void tryMarkRunning_concurrentCallers_exactlyOneWins() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    int threadCount = 10;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger wins = new AtomicInteger(0);

    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    if (item.tryMarkRunning()) {
                      wins.incrementAndGet();
                    }
                  });
    }
    ready.await();
    go.countDown();
    for (Thread t : threads) t.join();

    assertThat(wins.get()).isEqualTo(1);
    assertThat(item.getStatus()).isEqualTo(TaskStatus.RUNNING);
  }

  @Test
  void markCompleted_from_running_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markCompleted();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void markCompleted_from_pending_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    assertThatThrownBy(item::markCompleted).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markFaulted_from_running_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markFaulted();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void markCancelled_from_pending_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markCancelled();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.CANCELLED);
  }

  @Test
  void create_withTarget_storesTarget() {
    BindingTarget target = HumanTaskTarget.template("irb-review").build();
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 5, target);

    assertThat(item.getTarget()).isSameAs(target);
  }

  @Test
  void create_withoutTarget_targetIsNull() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 5);

    assertThat(item.getTarget()).isNull();
  }

  @Test
  void create_capabilityTarget_roundTrip() {
    Capability cap =
        Capability.builder().name("review").inputSchema("{}").outputSchema("{}").build();
    CapabilityTarget target = new CapabilityTarget(cap);
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 5, target);

    assertThat(item.getTarget()).isInstanceOf(CapabilityTarget.class);
    assertThat(((CapabilityTarget) item.getTarget()).capability()).isSameAs(cap);
  }

  @Test
  void status_field_is_atomicReference() throws NoSuchFieldException {
    java.lang.reflect.Field field = PlanItem.class.getDeclaredField("status");
    assertThat(field.getType())
        .as("PlanItem.status must be AtomicReference for thread-safe CAS operations")
        .isEqualTo(java.util.concurrent.atomic.AtomicReference.class);
  }

  // --- DELEGATED state ---

  @Test
  void markDelegated_from_pending_transitions_to_delegated() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void markDelegated_from_running_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    assertThatThrownBy(item::markDelegated).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markDelegated_from_delegated_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    assertThatThrownBy(item::markDelegated).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markCompleted_from_delegated_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markCompleted();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void markFaulted_from_delegated_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markFaulted();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void markCancelled_from_delegated_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markCancelled();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.CANCELLED);
  }

  @Test
  void markFaulted_from_pending_succeeds() {
    // Pre-dispatch errors (spawn failure, guard block) must fault without prior RUNNING/DELEGATED.
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markFaulted();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  // --- REJECTED state ---

  @Test
  void markRejected_from_delegated_transitions_to_rejected() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markRejected();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.REJECTED);
  }

  @Test
  void markRejected_from_pending_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    assertThatThrownBy(item::markRejected).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markRejected_from_running_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    assertThatThrownBy(item::markRejected).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markRejected_from_completed_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markCompleted();
    assertThatThrownBy(item::markRejected).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markRejected_from_faulted_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markFaulted();
    assertThatThrownBy(item::markRejected).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markRejected_from_cancelled_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markCancelled();
    assertThatThrownBy(item::markRejected).isInstanceOf(IllegalStateException.class);
  }

  // --- OBSOLETE state ---

  @Test
  void markObsolete_from_pending_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markObsolete();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.OBSOLETE);
  }

  @Test
  void markObsolete_from_running_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markObsolete();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.OBSOLETE);
  }

  @Test
  void markObsolete_from_delegated_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markObsolete();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.OBSOLETE);
  }

  @Test
  void markObsolete_from_completed_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markCompleted();
    assertThatThrownBy(item::markObsolete).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markObsolete_from_faulted_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markFaulted();
    assertThatThrownBy(item::markObsolete).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markObsolete_from_obsolete_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markObsolete();
    assertThatThrownBy(item::markObsolete).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markObsolete_from_cancelled_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markCancelled();
    assertThatThrownBy(item::markObsolete).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markFaulted_from_obsolete_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markObsolete();
    assertThatThrownBy(item::markFaulted).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markCancelled_from_obsolete_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markObsolete();
    assertThatThrownBy(item::markCancelled).isInstanceOf(IllegalStateException.class);
  }

  // --- SUSPENDED state ---

  @Test
  void markSuspended_from_delegated_succeeds() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markSuspended();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.SUSPENDED);
  }

  @Test
  void markSuspended_from_pending_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    assertThatThrownBy(item::markSuspended).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markSuspended_from_running_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    assertThatThrownBy(item::markSuspended).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markSuspended_from_completed_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("worker-a"), 0);
    item.markRunning();
    item.markCompleted();
    assertThatThrownBy(item::markSuspended).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markResumed_from_suspended_returns_to_delegated() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markSuspended();
    item.markResumed();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void markResumed_from_delegated_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    assertThatThrownBy(item::markResumed).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void markResumed_from_pending_throws() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    assertThatThrownBy(item::markResumed).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void suspend_resume_cycle_returns_to_delegated() {
    PlanItem item = PlanItem.create("binding-a", ExecutorRef.of("unknown"), 0);
    item.markDelegated();
    item.markSuspended();
    item.markResumed();
    item.markSuspended();
    item.markResumed();
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  // --- restore() factory ---

  @Test
  void restore_createsPlanItemWithGivenStatusAndId() {
    String planItemId = UUID.randomUUID().toString();
    PlanItem item =
        PlanItem.restore(planItemId, "my-binding", null, null, TaskStatus.DELEGATED, Instant.now());
    assertThat(item.getPlanItemId()).isEqualTo(planItemId);
    assertThat(item.getBindingName()).isEqualTo("my-binding");
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(item.getTarget()).isNull();
  }

  @Test
  void restore_rejectsInvalidStatus() {
    assertThatThrownBy(
            () ->
                PlanItem.restore(
                    UUID.randomUUID().toString(),
                    "b",
                    null,
                    null,
                    TaskStatus.PENDING,
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PENDING");
  }

  // --- Concurrent CAS transition tests (engine#649) ---

  @Test
  void markCompleted_concurrentCallers_exactlyOneWins() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markRunning();

    int threadCount = 10;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger failures = new AtomicInteger(0);

    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    try {
                      item.markCompleted();
                      successes.incrementAndGet();
                    } catch (IllegalStateException e) {
                      failures.incrementAndGet();
                    }
                  });
    }
    ready.await();
    go.countDown();
    for (Thread t : threads) t.join();

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
    assertThat(item.getStatus()).isEqualTo(TaskStatus.COMPLETED);
  }

  @Test
  void markFaulted_vs_markCompleted_exactlyOneWins() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markRunning();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger completedWins = new AtomicInteger(0);
    AtomicInteger faultedWins = new AtomicInteger(0);

    Thread completer =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  try {
                    item.markCompleted();
                    completedWins.incrementAndGet();
                  } catch (IllegalStateException e) {
                    // lost the race
                  }
                });

    Thread faulter =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  try {
                    item.markFaulted();
                    faultedWins.incrementAndGet();
                  } catch (IllegalStateException e) {
                    // lost the race
                  }
                });

    ready.await();
    go.countDown();
    completer.join();
    faulter.join();

    assertThat(completedWins.get() + faultedWins.get())
        .as("exactly one transition must win")
        .isEqualTo(1);
    assertThat(item.getStatus().isTerminal()).isTrue();
  }

  @Test
  void markFaulted_concurrentCallers_noSilentOverwrite() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markRunning();

    int threadCount = 10;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger failures = new AtomicInteger(0);

    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    try {
                      item.markFaulted();
                      successes.incrementAndGet();
                    } catch (IllegalStateException e) {
                      failures.incrementAndGet();
                    }
                  });
    }
    ready.await();
    go.countDown();
    for (Thread t : threads) t.join();

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
    assertThat(item.getStatus()).isEqualTo(TaskStatus.FAULTED);
  }

  @Test
  void markCancelled_concurrentCallers_exactlyOneWins() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markDelegated();

    int threadCount = 10;
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger failures = new AtomicInteger(0);

    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] =
          Thread.ofVirtual()
              .start(
                  () -> {
                    ready.countDown();
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    try {
                      item.markCancelled();
                      successes.incrementAndGet();
                    } catch (IllegalStateException e) {
                      failures.incrementAndGet();
                    }
                  });
    }
    ready.await();
    go.countDown();
    for (Thread t : threads) t.join();

    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(threadCount - 1);
    assertThat(item.getStatus()).isEqualTo(TaskStatus.CANCELLED);
  }

  @Test
  void markObsolete_vs_markCancelled_exactlyOneWins() throws Exception {
    PlanItem item = PlanItem.create("b1", ExecutorRef.of("w1"), 0, null);
    item.markDelegated();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger obsoleteWins = new AtomicInteger(0);
    AtomicInteger cancelledWins = new AtomicInteger(0);

    Thread obsoleter =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  try {
                    item.markObsolete();
                    obsoleteWins.incrementAndGet();
                  } catch (IllegalStateException e) {
                    // lost the race
                  }
                });

    Thread canceller =
        Thread.ofVirtual()
            .start(
                () -> {
                  ready.countDown();
                  try {
                    go.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  try {
                    item.markCancelled();
                    cancelledWins.incrementAndGet();
                  } catch (IllegalStateException e) {
                    // lost the race
                  }
                });

    ready.await();
    go.countDown();
    obsoleter.join();
    canceller.join();

    assertThat(obsoleteWins.get() + cancelledWins.get())
        .as("exactly one transition must win")
        .isEqualTo(1);
    assertThat(item.getStatus().isTerminal()).isTrue();
  }
}
