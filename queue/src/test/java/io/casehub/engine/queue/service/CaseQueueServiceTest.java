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
package io.casehub.engine.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.casehub.engine.queue.event.CaseQueueEntryClaimed;
import io.casehub.engine.queue.event.CaseQueueEntryEscalated;
import io.casehub.engine.queue.event.CaseQueueEntryReleased;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.store.InMemoryCaseQueueEntryStore;
import jakarta.enterprise.event.Event;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseQueueServiceTest {

  private CaseQueueService service;
  private InMemoryCaseQueueEntryStore store;
  private final List<Object> firedEvents = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    service = new CaseQueueService();
    store = new InMemoryCaseQueueEntryStore();
    Event<CaseQueueEntryClaimed> claimedBus = mock(Event.class);
    Event<CaseQueueEntryReleased> releasedBus = mock(Event.class);
    Event<CaseQueueEntryEscalated> escalatedBus = mock(Event.class);

    inject(service, "store", store);
    inject(service, "claimedEvents", claimedBus);
    inject(service, "releasedEvents", releasedBus);
    inject(service, "escalatedEvents", escalatedBus);

    firedEvents.clear();
    doAnswer(
            inv -> {
              firedEvents.add(inv.getArgument(0));
              return null;
            })
        .when(claimedBus)
        .fireAsync(any());
    doAnswer(
            inv -> {
              firedEvents.add(inv.getArgument(0));
              return null;
            })
        .when(releasedBus)
        .fireAsync(any());
    doAnswer(
            inv -> {
              firedEvents.add(inv.getArgument(0));
              return null;
            })
        .when(escalatedBus)
        .fireAsync(any());
  }

  @Test
  void claim_pending_succeeds() {
    CaseQueueEntry entry = savePending("tenant-1");
    CaseQueueEntry claimed = service.claim(entry.getId(), "tenant-1", "user-1");

    assertThat(claimed.getStatus()).isEqualTo(QueueEntryStatus.CLAIMED);
    assertThat(claimed.getAssignedTo()).isEqualTo("user-1");
    assertThat(claimed.getClaimedAt()).isNotNull();
    assertThat(firedEvents).hasSize(1);
    assertThat(firedEvents.get(0)).isInstanceOf(CaseQueueEntryClaimed.class);
  }

  @Test
  void claim_nonPending_throws() {
    CaseQueueEntry entry = savePending("tenant-1");
    service.claim(entry.getId(), "tenant-1", "user-1");

    assertThatThrownBy(() -> service.claim(entry.getId(), "tenant-1", "user-2"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void claim_wrongTenancy_throws() {
    CaseQueueEntry entry = savePending("tenant-1");

    assertThatThrownBy(() -> service.claim(entry.getId(), "tenant-2", "user-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void release_claimed_succeeds() {
    CaseQueueEntry entry = savePending("tenant-1");
    service.claim(entry.getId(), "tenant-1", "user-1");
    firedEvents.clear();

    CaseQueueEntry released = service.release(entry.getId(), "tenant-1");

    assertThat(released.getStatus()).isEqualTo(QueueEntryStatus.PENDING);
    assertThat(released.getAssignedTo()).isNull();
    assertThat(released.getClaimedAt()).isNull();
    assertThat(firedEvents).hasSize(1);
    assertThat(firedEvents.get(0)).isInstanceOf(CaseQueueEntryReleased.class);
  }

  @Test
  void release_notClaimed_throws() {
    CaseQueueEntry entry = savePending("tenant-1");

    assertThatThrownBy(() -> service.release(entry.getId(), "tenant-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void escalate_moves_entry() {
    CaseQueueEntry entry = savePending("tenant-1");
    UUID originalViewId = entry.getViewId();
    UUID targetViewId = UUID.randomUUID();

    CaseQueueEntry escalated = service.escalate(entry.getId(), "tenant-1", targetViewId);

    assertThat(escalated.getViewId()).isEqualTo(targetViewId);
    assertThat(escalated.getPreviousViewId()).isEqualTo(originalViewId);
    assertThat(escalated.getStatus()).isEqualTo(QueueEntryStatus.PENDING);
    assertThat(escalated.getEscalatedAt()).isNotNull();
    assertThat(firedEvents).hasSize(1);
    assertThat(firedEvents.get(0)).isInstanceOf(CaseQueueEntryEscalated.class);
  }

  @Test
  void escalate_alreadyInTarget_throws() {
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry entry = savePendingWithView("tenant-1", viewId);

    assertThatThrownBy(() -> service.escalate(entry.getId(), "tenant-1", viewId))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void findPending_filtersCorrectly() {
    UUID viewId = UUID.randomUUID();
    savePendingWithView("tenant-1", viewId);
    savePendingWithView("tenant-1", viewId);
    CaseQueueEntry claimed = savePendingWithView("tenant-1", viewId);
    store.claimIfPending(claimed.getId(), "user-1");

    List<CaseQueueEntry> pending = service.findPending(viewId, "tenant-1");
    assertThat(pending).hasSize(2);
  }

  @Test
  void countByView_correct() {
    UUID viewId = UUID.randomUUID();
    savePendingWithView("tenant-1", viewId);
    savePendingWithView("tenant-1", viewId);

    assertThat(service.countByView(viewId, "tenant-1")).isEqualTo(2);
  }

  private CaseQueueEntry savePending(String tenancyId) {
    return savePendingWithView(tenancyId, UUID.randomUUID());
  }

  private CaseQueueEntry savePendingWithView(String tenancyId, UUID viewId) {
    CaseQueueEntry entry =
        new CaseQueueEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            tenancyId,
            viewId,
            "test-queue",
            QueueEntryStatus.PENDING,
            Instant.now());
    return store.save(entry);
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
