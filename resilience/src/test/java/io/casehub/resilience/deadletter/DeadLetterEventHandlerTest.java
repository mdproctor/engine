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
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerRetriesExhaustedEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test: verifies that publishing a {@code WORKER_RETRIES_EXHAUSTED} event on the Vert.x
 * event bus results in a {@link DeadLetterEntry} being created in the {@link DeadLetterQueue}.
 *
 * <p>No real case is started — just the event bus publish + DLQ listener.
 */
@QuarkusTest
class DeadLetterEventHandlerTest {

  @Inject EventBus eventBus;

  @Inject DeadLetterQueue deadLetterQueue;

  @BeforeEach
  void clearQueue() {
    // DeadLetterQueue is ApplicationScoped — clear between tests to keep them independent.
    deadLetterQueue.clear();
  }

  @Test
  void workerRetriesExhaustedEvent_createsEntryInDlq() {
    UUID caseId = UUID.randomUUID();
    String workerId = "failing-worker";
    String hash = "test-hash-001";

    eventBus.publish(
        EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
        new WorkerRetriesExhaustedEvent(caseId, workerId, hash, null, "test-tenant"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> entries = deadLetterQueue.query(DeadLetterQuery.all());
              assertThat(entries).hasSize(1);
              DeadLetterEntry entry = entries.get(0);
              assertThat(entry.caseId()).isEqualTo(caseId);
              assertThat(entry.workerId()).isEqualTo(workerId);
              assertThat(entry.idempotencyHash()).isEqualTo(hash);
              assertThat(entry.status()).isEqualTo(DeadLetterStatus.PENDING_REVIEW);
            });
  }

  @Test
  void multipleExhaustedEvents_eachCreatesSeparateEntry() {
    UUID caseA = UUID.randomUUID();
    UUID caseB = UUID.randomUUID();

    eventBus.publish(
        EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
        new WorkerRetriesExhaustedEvent(caseA, "worker-a", "hash-a", null, "test-tenant"));
    eventBus.publish(
        EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
        new WorkerRetriesExhaustedEvent(caseB, "worker-b", "hash-b", null, "test-tenant"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> entries = deadLetterQueue.query(DeadLetterQuery.all());
              assertThat(entries).hasSize(2);
              assertThat(entries.stream().map(DeadLetterEntry::caseId))
                  .containsExactlyInAnyOrder(caseA, caseB);
            });
  }

  @Test
  void dlqEntry_isQueryableByWorkerId() {
    UUID caseId = UUID.randomUUID();
    eventBus.publish(
        EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
        new WorkerRetriesExhaustedEvent(
            caseId, "specific-worker", "hash-xyz", null, "test-tenant"));

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              List<DeadLetterEntry> results =
                  deadLetterQueue.query(DeadLetterQuery.forWorker("specific-worker"));
              assertThat(results).hasSize(1);
              assertThat(results.get(0).caseId()).isEqualTo(caseId);
            });
  }
}
