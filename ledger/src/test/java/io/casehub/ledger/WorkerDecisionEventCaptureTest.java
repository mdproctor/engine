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
import io.casehub.ledger.api.model.ScoreType;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.service.CachedTrustScoreSource;
import io.casehub.ledger.runtime.service.routing.TrustScoreFullPayload;
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

  @Inject TrustScoreSource trustScoreSource;

  // Concrete type needed to call onFull() directly for test seeding
  @Inject CachedTrustScoreSource cachedTrustScoreSource;

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
              // Trust score fields are null when no scores exist in TrustScoreSource for this actor
              assertThat(entry.trustScoreAtRouting).isNull();
              assertThat(entry.thresholdApplied).isNull();
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
  void trustScore_populatedFromCache_whenScoreExists() {
    final UUID caseId = UUID.randomUUID();
    final String workerId = "trust-worker-v1";
    final String capabilityTag = "trust-cap";

    // Pre-populate CachedTrustScoreSource via onFull() — same path as the production scoring cycle.
    final ActorTrustScore score = new ActorTrustScore();
    score.actorId = workerId;
    score.scoreType = ScoreType.CAPABILITY;
    score.capabilityKey = capabilityTag;
    score.trustScore = 0.85;
    cachedTrustScoreSource.onFull(new TrustScoreFullPayload(List.of(score)));

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(caseId, "test-tenant", workerId, capabilityTag, "trace-trust"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final WorkerDecisionEntry entry = entries.get(0);
              assertThat(entry.trustScoreAtRouting).isEqualTo(0.85);
              // DefaultTrustRoutingPolicyProvider returns TrustRoutingPolicy.DEFAULT
              // (threshold=0.7)
              assertThat(entry.thresholdApplied).isEqualTo(0.7);
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

  @Test
  void reasoning_populatesDomainData_whenPresent() {
    final UUID caseId = UUID.randomUUID();

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(
            caseId,
            "test-tenant",
            "analyst-v1",
            "analysis",
            "trace-r1",
            null,
            "I chose approach A because the risk indicators were above threshold"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final WorkerDecisionEntry entry = entries.get(0);
              assertThat(entry.domainData).isNotNull();
              assertThat(entry.domainData).containsKey("reasoning");
              assertThat(entry.domainData.get("reasoning"))
                  .isEqualTo("I chose approach A because the risk indicators were above threshold");
            });
  }

  @Test
  void reasoning_absent_domainDataNull() {
    final UUID caseId = UUID.randomUUID();

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(caseId, "test-tenant", "worker-no-reasoning", "cap-x", "trace-nr"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).domainData).isNull();
            });
  }

  @Test
  void reasoning_truncated_whenExceedsLimit() {
    final UUID caseId = UUID.randomUUID();
    final String longReasoning = "x".repeat(5000);

    workerDecisionEvents.fireAsync(
        new WorkerDecisionEvent(
            caseId, "test-tenant", "verbose-worker", "cap-v", "trace-trunc", null, longReasoning));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<WorkerDecisionEntry> entries =
                  repository.findWorkerDecisionsByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final String stored = (String) entries.get(0).domainData.get("reasoning");
              assertThat(stored).isNotNull();
              assertThat(stored.length()).isLessThanOrEqualTo(4096);
              assertThat(stored).contains("[...truncated...]");
            });
  }

  @Test
  void reasoning_inDomainData_includedInCanonicalBytes() {
    final WorkerDecisionEntry entry = new WorkerDecisionEntry();
    entry.caseId = UUID.randomUUID();
    entry.subjectId = entry.caseId;
    entry.workerId = "test-worker";
    entry.capabilityTag = "test-cap";
    entry.tenancyId = "test-tenant";
    entry.sequenceNumber = 1;
    entry.entryType = LedgerEntryType.EVENT;
    entry.actorId = "test-worker";
    entry.actorType = ActorType.SYSTEM;
    entry.actorRole = "WORKER";
    entry.occurredAt = java.time.Instant.now();

    byte[] withoutReasoning = entry.canonicalBytes();

    entry.domainData = java.util.Map.of("reasoning", "I chose A because threshold exceeded");

    byte[] withReasoning = entry.canonicalBytes();

    assertThat(withReasoning).isNotEqualTo(withoutReasoning);
    assertThat(new String(withReasoning, java.nio.charset.StandardCharsets.UTF_8))
        .contains("reasoning");
  }
}
