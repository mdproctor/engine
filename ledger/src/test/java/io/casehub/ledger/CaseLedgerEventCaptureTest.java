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

import io.casehub.engine.common.internal.event.CaseLifecycleEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.ledger.runtime.service.LedgerVerificationService;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link io.casehub.ledger.service.CaseLedgerEventCapture}.
 *
 * <p>Fires {@link CaseLifecycleEvent} via CDI and asserts that entries appear in the ledger. Uses
 * the real repository and database to verify end-to-end correctness.
 */
@QuarkusTest
class CaseLedgerEventCaptureTest {

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject CaseLedgerEntryRepository repository;

  @Inject LedgerVerificationService verificationService;

  @Test
  void happyPath_singleEvent_writesLedgerEntry() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final CaseLedgerEntry entry = entries.get(0);
              assertThat(entry.caseId).isEqualTo(caseId);
              assertThat(entry.subjectId).isEqualTo(caseId);
              assertThat(entry.commandType).isEqualTo("StartCase");
              assertThat(entry.eventType).isEqualTo("CaseStarted");
              assertThat(entry.caseStatus).isEqualTo("RUNNING");
              assertThat(entry.sequenceNumber).isEqualTo(1);
              assertThat(entry.entryType).isEqualTo(LedgerEntryType.EVENT);
              assertThat(entry.actorType).isEqualTo(ActorType.SYSTEM);
              assertThat(entry.occurredAt).isNotNull();
            });
  }

  @Test
  void sequenceNumbers_incrementPerCase() {
    final UUID caseId = UUID.randomUUID();

    // Join each fire to serialize: concurrent fires for the same case would race on seq numbers.
    // In production, the engine processes one case event at a time — concurrency doesn't arise.
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(
                caseId, "SuspendCase", "CaseSuspended", "SUSPENDED", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseId, "ResumeCase", "CaseResumed", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(3);
              assertThat(entries.get(0).sequenceNumber).isEqualTo(1);
              assertThat(entries.get(1).sequenceNumber).isEqualTo(2);
              assertThat(entries.get(2).sequenceNumber).isEqualTo(3);
            });
  }

  @Test
  void sequenceNumbers_independentPerCase() {
    final UUID caseA = UUID.randomUUID();
    final UUID caseB = UUID.randomUUID();

    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseA, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseB, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(
                caseA, "CompleteCase", "CaseCompleted", "COMPLETED", null, "System"))
        .toCompletableFuture()
        .join();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(repository.findByCaseId(caseA)).hasSize(2);
              assertThat(repository.findByCaseId(caseB)).hasSize(1);
              assertThat(repository.findByCaseId(caseA).get(1).sequenceNumber).isEqualTo(2);
              assertThat(repository.findByCaseId(caseB).get(0).sequenceNumber).isEqualTo(1);
            });
  }

  @Test
  void actorType_agentPersona_derivedCorrectly() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(
            caseId,
            "StartCase",
            "CaseStarted",
            "RUNNING",
            "claude:casehub-agent@v1",
            "Orchestrator"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).actorType).isEqualTo(ActorType.AGENT);
              assertThat(entries.get(0).actorId).isEqualTo("claude:casehub-agent@v1");
              assertThat(entries.get(0).actorRole).isEqualTo("Orchestrator");
            });
  }

  @Test
  void actorType_humanActor_derivedCorrectly() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(
            caseId, "SuspendCase", "CaseSuspended", "SUSPENDED", "alice", "Administrator"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).actorType).isEqualTo(ActorType.HUMAN);
            });
  }

  @Test
  void merkleDigest_isPopulatedWhenHashChainEnabled() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              // digest is set when casehub.ledger.hash-chain.enabled=true (default)
              assertThat(entries.get(0).digest).isNotNull().isNotEmpty();
            });
  }

  @Test
  void findLatestByCaseId_returnsHighestSequence() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(
                caseId, "CompleteCase", "CaseCompleted", "COMPLETED", null, "System"))
        .toCompletableFuture()
        .join();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final var latest = repository.findLatestByCaseId(caseId);
              assertThat(latest).isPresent();
              assertThat(latest.get().sequenceNumber).isEqualTo(2);
              assertThat(latest.get().eventType).isEqualTo("CaseCompleted");
            });
  }

  @Test
  void nonStatusEvent_nullCaseStatus_storedCorrectly() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(caseId, "SignalCase", "SignalReceived", null, null, "System"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              assertThat(entries.get(0).caseStatus).isNull();
            });
  }

  @Test
  void robustness_unknownCaseId_returnsEmptyList() {
    final List<CaseLedgerEntry> entries = repository.findByCaseId(UUID.randomUUID());
    assertThat(entries).isEmpty();
  }

  @Test
  @Transactional
  void robustness_findLatest_emptyForUnknownCase() {
    assertThat(repository.findLatestByCaseId(UUID.randomUUID())).isEmpty();
  }

  @Test
  void workerExecutionStarted_writesLedgerEntry_withWorkerIdAsActorId() {
    final UUID caseId = UUID.randomUUID();
    final String workerId = "researcher-worker";

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(
            caseId, "ExecuteWorker", "WorkerExecutionStarted", null, workerId, "WORKER"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final CaseLedgerEntry entry = entries.get(0);
              assertThat(entry.commandType).isEqualTo("ExecuteWorker");
              assertThat(entry.eventType).isEqualTo("WorkerExecutionStarted");
              assertThat(entry.caseStatus).isNull();
              assertThat(entry.actorId).isEqualTo(workerId);
              assertThat(entry.actorRole).isEqualTo("WORKER");
            });
  }

  @Test
  void workerExecutionCompleted_writesLedgerEntry_withWorkerIdAsActorId() {
    final UUID caseId = UUID.randomUUID();
    final String workerId = "analyst-worker";

    lifecycleEvents.fireAsync(
        new CaseLifecycleEvent(
            caseId, "ExecuteWorker", "WorkerExecutionCompleted", "RUNNING", workerId, "WORKER"));

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final List<CaseLedgerEntry> entries = repository.findByCaseId(caseId);
              assertThat(entries).hasSize(1);
              final CaseLedgerEntry entry = entries.get(0);
              assertThat(entry.commandType).isEqualTo("ExecuteWorker");
              assertThat(entry.eventType).isEqualTo("WorkerExecutionCompleted");
              assertThat(entry.caseStatus).isEqualTo("RUNNING");
              assertThat(entry.actorId).isEqualTo(workerId);
              assertThat(entry.actorRole).isEqualTo("WORKER");
            });
  }

  // ── Correctness: Merkle chain integrity ────────────────────────────────────

  @Test
  void merkleChain_singleEntry_verifies() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(repository.findByCaseId(caseId)).hasSize(1));

    assertThat(verificationService.verify(caseId))
        .as("Merkle chain must be intact after a single event")
        .isTrue();
  }

  @Test
  void merkleChain_multipleEntries_verifies() {
    final UUID caseId = UUID.randomUUID();

    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(caseId, "StartCase", "CaseStarted", "RUNNING", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(
                caseId, "SuspendCase", "CaseSuspended", "SUSPENDED", null, "System"))
        .toCompletableFuture()
        .join();
    lifecycleEvents
        .fireAsync(
            new CaseLifecycleEvent(
                caseId, "CompleteCase", "CaseCompleted", "COMPLETED", null, "System"))
        .toCompletableFuture()
        .join();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(repository.findByCaseId(caseId)).hasSize(3));

    assertThat(verificationService.verify(caseId))
        .as("Merkle chain must be intact after three events")
        .isTrue();
  }
}
