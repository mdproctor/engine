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
package io.casehub.ledger.repository;

import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.runtime.repository.jpa.JpaLedgerEntryRepository;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link CaseLedgerEntry}.
 *
 * <p>Extends {@link JpaLedgerEntryRepository} — adds case-specific query methods for {@link
 * CaseLedgerEntry} and {@link WorkerDecisionEntry}.
 *
 * <p>{@code @DefaultBean} yields automatically to any explicitly selected alternative ({@code
 * selected-alternatives}, {@code @Priority}-activated alternative). Consumers that want a different
 * implementation (e.g. in-memory test doubles) need no exclusion configuration — their selected
 * alternative wins by default. This follows the engine SPI default pattern
 * (PP-20260514-engine-spi-noops-defaultbean).
 */
@DefaultBean
@ApplicationScoped
public class CaseLedgerEntryRepository extends JpaLedgerEntryRepository {

  @Inject @LedgerPersistenceUnit EntityManager caseEm; // own field — parent's em is package-private

  /** All ledger entries for the given case, ordered by sequence number ascending. */
  @Transactional
  public List<CaseLedgerEntry> findByCaseId(final UUID caseId) {
    return caseEm
        .createQuery(
            "SELECT e FROM CaseLedgerEntry e WHERE e.subjectId = :caseId ORDER BY e.sequenceNumber ASC",
            CaseLedgerEntry.class)
        .setParameter("caseId", caseId)
        .getResultList();
  }

  /** All worker decision entries for the given case, ordered by sequence number ascending. */
  @Transactional
  public List<WorkerDecisionEntry> findWorkerDecisionsByCaseId(final UUID caseId) {
    return caseEm
        .createQuery(
            "SELECT e FROM WorkerDecisionEntry e WHERE e.caseId = :caseId ORDER BY e.sequenceNumber ASC",
            WorkerDecisionEntry.class)
        .setParameter("caseId", caseId)
        .getResultList();
  }

  /** The most recent ledger entry for the given case, or empty if none exist yet. */
  @Transactional
  public Optional<CaseLedgerEntry> findLatestByCaseId(final UUID caseId) {
    return caseEm
        .createQuery(
            "SELECT e FROM CaseLedgerEntry e WHERE e.subjectId = :caseId ORDER BY e.sequenceNumber DESC",
            CaseLedgerEntry.class)
        .setParameter("caseId", caseId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }
}
