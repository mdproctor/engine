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
package io.casehub.engine.queue.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryCaseQueueEntryStoreTest {

  private CaseQueueEntryStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryCaseQueueEntryStore();
  }

  @Test
  void save_and_findById() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    store.save(entry);
    Optional<CaseQueueEntry> found = store.findById(entry.getId());
    assertThat(found).isPresent();
    assertThat(found.get().getCaseId()).isEqualTo(entry.getCaseId());
  }

  @Test
  void findById_notFound_returnsEmpty() {
    assertThat(store.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findByCaseAndView() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry entry = newEntry(caseId, viewId, "t1");
    store.save(entry);
    assertThat(store.findByCaseAndView(caseId, viewId)).isPresent();
    assertThat(store.findByCaseAndView(caseId, UUID.randomUUID())).isEmpty();
  }

  @Test
  void upsertByCaseAndView_insertsWhenNew() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    CaseQueueEntry result = store.upsertByCaseAndView(entry);
    assertThat(result.getStatus()).isEqualTo(QueueEntryStatus.PENDING);
    assertThat(store.findById(entry.getId())).isPresent();
  }

  @Test
  void upsertByCaseAndView_updatesExisting() {
    UUID caseId = UUID.randomUUID();
    UUID viewId = UUID.randomUUID();
    CaseQueueEntry first = newEntry(caseId, viewId, "t1");
    store.save(first);

    CaseQueueEntry second =
        new CaseQueueEntry(
            UUID.randomUUID(),
            caseId,
            "t1",
            viewId,
            "updated-name",
            QueueEntryStatus.PENDING,
            Instant.now());
    CaseQueueEntry result = store.upsertByCaseAndView(second);
    assertThat(result.getViewName()).isEqualTo("updated-name");
    assertThat(store.findByCaseAndView(caseId, viewId).get().getViewName())
        .isEqualTo("updated-name");
  }

  @Test
  void findByView_filtersByViewAndTenancy() {
    UUID viewId = UUID.randomUUID();
    store.save(newEntry(UUID.randomUUID(), viewId, "t1"));
    store.save(newEntry(UUID.randomUUID(), viewId, "t1"));
    store.save(newEntry(UUID.randomUUID(), viewId, "t2"));
    store.save(newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1"));

    assertThat(store.findByView(viewId, "t1")).hasSize(2);
    assertThat(store.findByView(viewId, "t2")).hasSize(1);
  }

  @Test
  void findByCaseId() {
    UUID caseId = UUID.randomUUID();
    store.save(newEntry(caseId, UUID.randomUUID(), "t1"));
    store.save(newEntry(caseId, UUID.randomUUID(), "t1"));
    store.save(newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1"));

    assertThat(store.findByCaseId(caseId)).hasSize(2);
  }

  @Test
  void countByView() {
    UUID viewId = UUID.randomUUID();
    store.save(newEntry(UUID.randomUUID(), viewId, "t1"));
    store.save(newEntry(UUID.randomUUID(), viewId, "t1"));

    assertThat(store.countByView(viewId, "t1")).isEqualTo(2);
  }

  @Test
  void delete() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    store.save(entry);
    assertThat(store.delete(entry.getId())).isTrue();
    assertThat(store.findById(entry.getId())).isEmpty();
  }

  @Test
  void delete_notFound_returnsFalse() {
    assertThat(store.delete(UUID.randomUUID())).isFalse();
  }

  @Test
  void deleteByCaseId() {
    UUID caseId = UUID.randomUUID();
    store.save(newEntry(caseId, UUID.randomUUID(), "t1"));
    store.save(newEntry(caseId, UUID.randomUUID(), "t1"));
    store.save(newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1"));

    store.deleteByCaseId(caseId);
    assertThat(store.findByCaseId(caseId)).isEmpty();
    assertThat(store.countByView(UUID.randomUUID(), "t1")).isZero();
  }

  @Test
  void claimIfPending_succeeds() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    store.save(entry);

    Optional<CaseQueueEntry> claimed = store.claimIfPending(entry.getId(), "user-1");
    assertThat(claimed).isPresent();
    assertThat(claimed.get().getStatus()).isEqualTo(QueueEntryStatus.CLAIMED);
    assertThat(claimed.get().getAssignedTo()).isEqualTo("user-1");
    assertThat(claimed.get().getClaimedAt()).isNotNull();
  }

  @Test
  void claimIfPending_alreadyClaimed_returnsEmpty() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    store.save(entry);
    store.claimIfPending(entry.getId(), "user-1");

    Optional<CaseQueueEntry> second = store.claimIfPending(entry.getId(), "user-2");
    assertThat(second).isEmpty();
  }

  @Test
  void claimIfPending_notFound_returnsEmpty() {
    assertThat(store.claimIfPending(UUID.randomUUID(), "user-1")).isEmpty();
  }

  @Test
  void claimIfPending_revoked_returnsEmpty() {
    CaseQueueEntry entry = newEntry(UUID.randomUUID(), UUID.randomUUID(), "t1");
    entry.setStatus(QueueEntryStatus.REVOKED);
    store.save(entry);

    assertThat(store.claimIfPending(entry.getId(), "user-1")).isEmpty();
  }

  private CaseQueueEntry newEntry(UUID caseId, UUID viewId, String tenancyId) {
    return new CaseQueueEntry(
        UUID.randomUUID(),
        caseId,
        tenancyId,
        viewId,
        "test-queue",
        QueueEntryStatus.PENDING,
        Instant.now());
  }
}
