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
package io.casehub.engine.queue.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.casehub.engine.queue.event.CaseQueueEntryRevoked;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.event.CaseQueueEventType;
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

class CaseQueueEntryManagerTest {

  private CaseQueueEntryManager manager;
  private InMemoryCaseQueueEntryStore store;
  private final List<CaseQueueEntryRevoked> revokedEvents = new ArrayList<>();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() throws Exception {
    manager = new CaseQueueEntryManager();
    store = new InMemoryCaseQueueEntryStore();
    Event<CaseQueueEntryRevoked> revokedBus = mock(Event.class);

    inject(manager, "store", store);
    inject(manager, "revokedEvents", revokedBus);

    revokedEvents.clear();
    doAnswer(
            inv -> {
              revokedEvents.add(inv.getArgument(0));
              return null;
            })
        .when(revokedBus)
        .fireAsync(any());
  }

  @Test
  void added_creates_pending_entry() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();

    manager.onQueueEvent(added(caseId, viewId, "tenant-1", "High Priority"));

    assertThat(store.findByCaseAndView(caseId, viewId)).isPresent();
    assertThat(store.findByCaseAndView(caseId, viewId).get().getStatus())
        .isEqualTo(QueueEntryStatus.PENDING);
  }

  @Test
  void added_existing_pending_noop() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    store.save(pendingEntry(caseId, viewId, "tenant-1"));

    manager.onQueueEvent(added(caseId, viewId, "tenant-1", "Q"));

    assertThat(store.findByCaseId(caseId)).hasSize(1);
  }

  @Test
  void added_existing_claimed_noop() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry entry = pendingEntry(caseId, viewId, "tenant-1");
    entry.setStatus(QueueEntryStatus.CLAIMED);
    entry.setAssignedTo("user-1");
    store.save(entry);

    manager.onQueueEvent(added(caseId, viewId, "tenant-1", "Q"));

    assertThat(store.findByCaseAndView(caseId, viewId).get().getStatus())
        .isEqualTo(QueueEntryStatus.CLAIMED);
    assertThat(store.findByCaseAndView(caseId, viewId).get().getAssignedTo()).isEqualTo("user-1");
  }

  @Test
  void added_existing_revoked_reactivates() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry entry = pendingEntry(caseId, viewId, "tenant-1");
    entry.setStatus(QueueEntryStatus.REVOKED);
    entry.setAssignedTo("old-user");
    entry.setClaimedAt(Instant.now());
    store.save(entry);

    manager.onQueueEvent(added(caseId, viewId, "tenant-1", "Q"));

    CaseQueueEntry reactivated = store.findByCaseAndView(caseId, viewId).get();
    assertThat(reactivated.getStatus()).isEqualTo(QueueEntryStatus.PENDING);
    assertThat(reactivated.getAssignedTo()).isNull();
    assertThat(reactivated.getClaimedAt()).isNull();
  }

  @Test
  void removed_pending_deletes() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    store.save(pendingEntry(caseId, viewId, "tenant-1"));

    manager.onQueueEvent(removed(caseId, viewId, "tenant-1", "Q"));

    assertThat(store.findByCaseAndView(caseId, viewId)).isEmpty();
  }

  @Test
  void removed_claimed_revokes() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry entry = pendingEntry(caseId, viewId, "tenant-1");
    entry.setStatus(QueueEntryStatus.CLAIMED);
    entry.setAssignedTo("user-1");
    store.save(entry);

    manager.onQueueEvent(removed(caseId, viewId, "tenant-1", "Q"));

    CaseQueueEntry revoked = store.findByCaseAndView(caseId, viewId).get();
    assertThat(revoked.getStatus()).isEqualTo(QueueEntryStatus.REVOKED);
    assertThat(revokedEvents).hasSize(1);
    assertThat(revokedEvents.get(0).previousAssignee()).isEqualTo("user-1");
  }

  @Test
  void changed_noop() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    store.save(pendingEntry(caseId, viewId, "tenant-1"));

    manager.onQueueEvent(changed(caseId, viewId, "tenant-1", "Q"));

    assertThat(store.findByCaseAndView(caseId, viewId).get().getStatus())
        .isEqualTo(QueueEntryStatus.PENDING);
  }

  private CaseQueueEvent added(UUID caseId, UUID viewId, String tenancyId, String name) {
    return new CaseQueueEvent(caseId, viewId, name, CaseQueueEventType.ADDED, tenancyId);
  }

  private CaseQueueEvent removed(UUID caseId, UUID viewId, String tenancyId, String name) {
    return new CaseQueueEvent(caseId, viewId, name, CaseQueueEventType.REMOVED, tenancyId);
  }

  private CaseQueueEvent changed(UUID caseId, UUID viewId, String tenancyId, String name) {
    return new CaseQueueEvent(caseId, viewId, name, CaseQueueEventType.CHANGED, tenancyId);
  }

  private CaseQueueEntry pendingEntry(UUID caseId, UUID viewId, String tenancyId) {
    return new CaseQueueEntry(
        UUID.randomUUID(),
        caseId,
        tenancyId,
        viewId,
        "test-queue",
        QueueEntryStatus.PENDING,
        Instant.now());
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
