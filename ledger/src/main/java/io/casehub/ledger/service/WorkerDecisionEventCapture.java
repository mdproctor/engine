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
package io.casehub.ledger.service;

import io.casehub.engine.common.spi.event.WorkerDecisionEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.repository.CaseLedgerEntryRepository;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.platform.api.identity.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jboss.logging.Logger;

/**
 * CDI observer that writes a {@link WorkerDecisionEntry} for every successful worker execution.
 *
 * <p>The engine fires {@link WorkerDecisionEvent} via {@code Event.fireAsync()} from {@code
 * WorkflowExecutionCompletedHandler}. This observer receives the event on a managed executor
 * thread, making it safe to use blocking JPA and {@code @Transactional}.
 *
 * <p>Sequence number is computed across ALL {@link io.casehub.ledger.runtime.model.LedgerEntry}
 * subclasses for the same {@code subjectId} (case) using {@code findLatestBySubjectId()} — not
 * {@code findLatestByCaseId()} which is scoped to {@link io.casehub.ledger.model.CaseLedgerEntry}
 * only.
 *
 * <p>If this module is absent, the event fires into the void — no coupling to the engine.
 */
@ApplicationScoped
public class WorkerDecisionEventCapture {

  private static final Logger LOG = Logger.getLogger(WorkerDecisionEventCapture.class);

  @Inject CaseLedgerEntryRepository ledgerRepo;

  @Inject LedgerConfig ledgerConfig;

  @Transactional
  void onWorkerDecisionEvent(@ObservesAsync WorkerDecisionEvent event) {
    if (!ledgerConfig.enabled()) {
      return;
    }

    final int seq =
        ledgerRepo.findLatestBySubjectId(event.caseId()).map(e -> e.sequenceNumber + 1).orElse(1);

    final WorkerDecisionEntry entry = new WorkerDecisionEntry();
    entry.caseId = event.caseId();
    entry.subjectId = event.caseId();
    entry.workerId = event.workerId();
    entry.capabilityTag = event.capabilityTag();
    entry.sequenceNumber = seq;
    entry.entryType = LedgerEntryType.EVENT;
    entry.actorId = event.workerId();
    entry.actorType = ActorType.SYSTEM;
    entry.actorRole = "WORKER";
    entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    entry.traceId = event.traceId();

    ledgerRepo.save(entry);

    LOG.debugf(
        "Worker decision entry written: caseId=%s workerId=%s capability=%s seq=%d",
        event.caseId(), event.workerId(), event.capabilityTag(), seq);
  }
}
