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
package io.casehub.resilience.deadletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests — no Quarkus, no CDI. DeadLetterQueue is an in-memory store and must be testable
 * without a container.
 */
class DeadLetterQueueTest {

  private DeadLetterQueue queue;

  @BeforeEach
  void setUp() {
    queue = new DeadLetterQueue();
  }

  // ---- add ------------------------------------------------------------------

  @Test
  void add_storesEntryWithPendingReviewStatus() {
    UUID caseId = UUID.randomUUID();
    DeadLetterEntry entry =
        queue.add(caseId, "my-worker", "hash-abc", Map.of("key", "value"), null);

    assertThat(entry.deadLetterId()).isNotNull();
    assertThat(entry.caseId()).isEqualTo(caseId);
    assertThat(entry.workerId()).isEqualTo("my-worker");
    assertThat(entry.idempotencyHash()).isEqualTo("hash-abc");
    assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
    assertThat(entry.arrivedAt()).isNotNull();
    assertThat(entry.inputContext()).containsEntry("key", "value");
  }

  @Test
  void add_multipleEntriesAreStoredIndependently() {
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();

    queue.add(caseA, "worker-1", "hash-1", Map.of(), null);
    queue.add(caseB, "worker-2", "hash-2", Map.of(), null);

    List<DeadLetterEntry> all = queue.query(DeadLetterQuery.all());
    assertThat(all).hasSize(2);
    assertThat(all.stream().map(DeadLetterEntry::caseId)).containsExactlyInAnyOrder(caseA, caseB);
  }

  // ---- query ----------------------------------------------------------------

  @Test
  void query_all_returnsEveryEntry() {
    queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), null);
    queue.add(UUID.randomUUID(), "w2", "h2", Map.of(), null);
    queue.add(UUID.randomUUID(), "w3", "h3", Map.of(), null);

    assertThat(queue.query(DeadLetterQuery.all())).hasSize(3);
  }

  @Test
  void query_byStatus_filtersPendingOnly() {
    UUID caseId = UUID.randomUUID();
    DeadLetterEntry added = queue.add(caseId, "w1", "h1", Map.of(), null);
    queue.discard(added.deadLetterId());

    List<DeadLetterEntry> pending =
        queue.query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW));
    List<DeadLetterEntry> discarded =
        queue.query(DeadLetterQuery.withStatus(DeadLetterStatus.DISCARDED));

    assertThat(pending).isEmpty();
    assertThat(discarded).hasSize(1);
    assertThat(discarded.get(0).caseId()).isEqualTo(caseId);
  }

  @Test
  void query_byWorkerId_filtersCorrectly() {
    queue.add(UUID.randomUUID(), "target-worker", "h1", Map.of(), null);
    queue.add(UUID.randomUUID(), "other-worker", "h2", Map.of(), null);

    List<DeadLetterEntry> results = queue.query(DeadLetterQuery.forWorker("target-worker"));
    assertThat(results).hasSize(1);
    assertThat(results.get(0).workerId()).isEqualTo("target-worker");
  }

  @Test
  void query_arrivedAfter_excludesOlderEntries() throws InterruptedException {
    queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), null);
    Instant cutoff = Instant.now();
    Thread.sleep(5); // ensure next entry has a later timestamp
    queue.add(UUID.randomUUID(), "w2", "h2", Map.of(), null);

    List<DeadLetterEntry> results = queue.query(DeadLetterQuery.arrivedAfter(cutoff));
    assertThat(results).hasSize(1);
    assertThat(results.get(0).workerId()).isEqualTo("w2");
  }

  @Test
  void query_emptyQueue_returnsEmptyList() {
    assertThat(queue.query(DeadLetterQuery.all())).isEmpty();
  }

  // ---- discard --------------------------------------------------------------

  @Test
  void discard_updatesStatusToDiscarded() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), null);

    queue.discard(entry.deadLetterId());

    List<DeadLetterEntry> discarded =
        queue.query(DeadLetterQuery.withStatus(DeadLetterStatus.DISCARDED));
    assertThat(discarded).hasSize(1);
    assertThat(discarded.get(0).deadLetterId()).isEqualTo(entry.deadLetterId());
  }

  @Test
  void discard_unknownId_doesNothing() {
    queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), null);
    queue.discard("nonexistent-id"); // must not throw

    assertThat(queue.query(DeadLetterQuery.all())).hasSize(1);
  }

  // ---- replay ---------------------------------------------------------------

  @Test
  void replay_updatesStatusToReplayed() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w1", "h1", Map.of("doc", "id-1"), null);

    queue.markReplayed(entry.deadLetterId());

    List<DeadLetterEntry> replayed =
        queue.query(DeadLetterQuery.withStatus(DeadLetterStatus.REPLAYED));
    assertThat(replayed).hasSize(1);
    assertThat(replayed.get(0).deadLetterId()).isEqualTo(entry.deadLetterId());
  }

  @Test
  void replay_preservesOriginalInputContext() {
    Map<String, Object> input = Map.of("documentId", "doc-99", "status", "failed");
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "w1", "h1", input, null);

    queue.markReplayed(entry.deadLetterId());

    DeadLetterEntry replayed =
        queue.query(DeadLetterQuery.withStatus(DeadLetterStatus.REPLAYED)).get(0);
    assertThat(replayed.inputContext()).containsEntry("documentId", "doc-99");
  }

  // ---- replay attempt tracking -----------------------------------------------

  @Test
  void newEntry_hasZeroReplayAttempts() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "worker-1", "hash-1", Map.of(), null);
    assertThat(entry.replayAttempts()).isZero();
    assertThat(entry.lastReplayAttemptAt()).isNull();
  }

  @Test
  void incrementReplayAttempts_updatesCountAndTimestamp() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "worker-2", "hash-2", Map.of(), null);
    Instant before = Instant.now();
    entry.incrementReplayAttempts();
    assertThat(entry.replayAttempts()).isEqualTo(1);
    assertThat(entry.lastReplayAttemptAt()).isAfterOrEqualTo(before);
  }

  @Test
  void incrementReplayAttempts_accumulates() {
    DeadLetterEntry entry = queue.add(UUID.randomUUID(), "worker-3", "hash-3", Map.of(), null);
    entry.incrementReplayAttempts();
    entry.incrementReplayAttempts();
    assertThat(entry.replayAttempts()).isEqualTo(2);
  }

  // ---- size / eviction sanity -----------------------------------------------

  @Test
  void size_reflectsNumberOfStoredEntries() {
    assertThat(queue.size()).isZero();
    queue.add(UUID.randomUUID(), "w1", "h1", Map.of(), null);
    queue.add(UUID.randomUUID(), "w2", "h2", Map.of(), null);
    assertThat(queue.size()).isEqualTo(2);
  }
}
