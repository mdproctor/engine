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
package io.casehub.engine.resilience.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryDeadLetterEntryStoreTest {

  private InMemoryDeadLetterEntryStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryDeadLetterEntryStore();
  }

  @Test
  void save_and_findById() {
    DeadLetterEntry entry =
        new DeadLetterEntry(
            "dlq-1", UUID.randomUUID(), "worker-a", "hash-1", Map.of("k", "v"), null);
    store.save(entry);
    assertThat(store.findById("dlq-1")).isNotNull();
    assertThat(store.findById("dlq-1").workerId()).isEqualTo("worker-a");
  }

  @Test
  void findById_unknown_returnsNull() {
    assertThat(store.findById("nonexistent")).isNull();
  }

  @Test
  void query_byStatus() {
    DeadLetterEntry entry =
        new DeadLetterEntry("dlq-2", UUID.randomUUID(), "worker-b", "hash-2", Map.of(), null);
    store.save(entry);
    store.updateStatus("dlq-2", DeadLetterStatus.DISCARDED);
    assertThat(store.query(DeadLetterQuery.withStatus(DeadLetterStatus.DISCARDED))).hasSize(1);
    assertThat(store.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW))).isEmpty();
  }

  @Test
  void incrementReplayAttempts_updatesCountAndTimestamp() {
    DeadLetterEntry entry =
        new DeadLetterEntry("dlq-3", UUID.randomUUID(), "worker-c", "hash-3", Map.of(), null);
    store.save(entry);
    store.incrementReplayAttempts("dlq-3");
    DeadLetterEntry updated = store.findById("dlq-3");
    assertThat(updated.replayAttempts()).isEqualTo(1);
    assertThat(updated.lastReplayAttemptAt()).isNotNull();
  }

  @Test
  void deleteAll_clearsStore() {
    store.save(new DeadLetterEntry("dlq-4", UUID.randomUUID(), "w", "h", Map.of(), null));
    store.deleteAll();
    assertThat(store.query(DeadLetterQuery.all())).isEmpty();
  }

  @Test
  void query_all_returnsAllEntries() {
    store.save(new DeadLetterEntry("dlq-5", UUID.randomUUID(), "w1", "h1", Map.of(), null));
    store.save(new DeadLetterEntry("dlq-6", UUID.randomUUID(), "w2", "h2", Map.of(), null));
    assertThat(store.query(DeadLetterQuery.all())).hasSize(2);
  }

  @Test
  void updateStatus_unknownId_noOp() {
    store.updateStatus("nonexistent", DeadLetterStatus.DISCARDED);
  }

  @Test
  void incrementReplayAttempts_unknownId_noOp() {
    store.incrementReplayAttempts("nonexistent");
  }
}
