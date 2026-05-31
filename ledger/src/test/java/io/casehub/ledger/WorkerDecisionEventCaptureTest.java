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
package io.casehub.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.event.WorkerDecisionEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link io.casehub.ledger.service.WorkerDecisionEventCapture}.
 *
 * <p>Fires {@link WorkerDecisionEvent} via CDI and asserts that a {@link WorkerDecisionEntry}
 * appears in the ledger with correct actor, capability, and subject fields.
 */
@QuarkusTest
class WorkerDecisionEventCaptureTest {

  @Inject Event<WorkerDecisionEvent> workerDecisionEvents;

  @Inject CaseLedgerEntryRepository repository;

  @Test
  void happyPath_workerDecisionEvent_writesWorkerDecisionEntry() {
    final UUID caseId = UUID.randomUUID();
    final String workerId = "sar-drafting-agent-v1";
    final String capabilityTag = "sar-drafting";

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(caseId, "test-tenant", workerId, capabilityTag, "trace-abc"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final WorkerDecisionEntry entry = entries.get(0);
              assertThat(entry.workerId).isEqualTo(workerId);
              assertThat(entry.capabilityTag).isEqualTo(capabilityTag);
              assertThat(entry.caseId).isEqualTo(caseId);
              assertThat(entry.subjectId).isEqualTo(caseId);
              assertThat(entry.actorId).isEqualTo(workerId);
              assertThat(entry.actorType).isEqualTo(ActorType.SYSTEM);
              assertThat(entry.actorRole).isEqualTo("WORKER");
              assertThat(entry.entryType).isEqualTo(LedgerEntryType.EVENT);
              assertThat(entry.sequenceNumber).isGreaterThan(0);
              assertThat(entry.id).isNotNull();
              assertThat(entry.tenancyId).isEqualTo("test-tenant");
              assertThat(entry.traceId).isEqualTo("trace-abc");
            });
  }

  @Test
  void nullCapabilityTag_writesEntryWithNullCapabilityTag() {
    final UUID caseId = UUID.randomUUID();

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(caseId, "test-tenant", "generic-worker", null, null));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).capabilityTag).isNull();
              assertThat(entries.get(0).actorType).isEqualTo(ActorType.SYSTEM);
            });
  }

  @Test
  void sequenceNumber_workerDecisionAfterCaseEvent_sequencesCorrectly() {
    // Verifies that WorkerDecisionEntry sequence coordinates with CaseLedgerEntry
    // across the same subjectId (case).
    final UUID caseId = UUID.randomUUID();

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(caseId, "test-tenant", "worker-a", "cap-a", null));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).sequenceNumber).isEqualTo(1);
            });
  }
}
