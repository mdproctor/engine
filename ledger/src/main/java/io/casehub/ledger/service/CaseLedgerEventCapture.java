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

import io.casehub.engine.internal.event.CaseLifecycleEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
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
 * CDI observer that writes a {@link CaseLedgerEntry} for every case lifecycle transition.
 *
 * <p>The engine fires {@link CaseLifecycleEvent} via {@code Event.fireAsync()} from Vert.x
 * handlers. This observer receives the event on a managed executor thread, making it safe to use
 * blocking JPA and {@code @Transactional}. Each write is in its own transaction — eventual
 * consistency with respect to the case state update is acceptable for an audit log.
 *
 * <p>If this module is absent, lifecycle events fire into the void — no coupling to the engine.
 */
@ApplicationScoped
public class CaseLedgerEventCapture {

  private static final Logger LOG = Logger.getLogger(CaseLedgerEventCapture.class);

  @Inject CaseLedgerEntryRepository ledgerRepo;

  @Inject LedgerConfig ledgerConfig;

  @Transactional
  void onCaseLifecycleEvent(@ObservesAsync CaseLifecycleEvent event) {
    if (!ledgerConfig.enabled()) {
      return;
    }

    final int seq =
        ledgerRepo.findLatestByCaseId(event.caseId()).map(e -> e.sequenceNumber + 1).orElse(1);

    final CaseLedgerEntry entry = new CaseLedgerEntry();
    entry.caseId = event.caseId();
    entry.subjectId = event.caseId();
    entry.sequenceNumber = seq;
    entry.entryType = LedgerEntryType.EVENT;
    entry.commandType = event.commandType();
    entry.eventType = event.eventType();
    entry.caseStatus = event.caseStatus();
    entry.actorId = event.actorId() != null ? event.actorId() : "system";
    entry.actorType = deriveActorType(event.actorId());
    entry.actorRole = event.actorRole() != null ? event.actorRole() : "System";
    // Set before hash computation — @PrePersist runs too late for canonical form
    // Set before save() — @PrePersist runs too late for the canonical leaf hash.
    entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    // save() handles digest computation and Merkle frontier update internally.
    ledgerRepo.save(entry);

    LOG.debugf(
        "Ledger entry written: caseId=%s seq=%d event=%s", event.caseId(), seq, event.eventType());
  }

  private ActorType deriveActorType(final String actorId) {
    if (actorId == null) {
      return ActorType.SYSTEM;
    }
    // Versioned persona format: "model:persona@version" — e.g. "claude:casehub-agent@v1"
    if (actorId.contains(":") || actorId.contains("@")) {
      return ActorType.AGENT;
    }
    if (actorId.equals("system")) {
      return ActorType.SYSTEM;
    }
    return ActorType.HUMAN;
  }
}
