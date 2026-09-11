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
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Case-specific query helper for {@link CaseLedgerEntry} and {@link WorkerDecisionEntry}.
 *
 * <p>Uses composition — does not extend {@code JpaLedgerEntryRepository}. The capture services
 * ({@code CaseLedgerEventCapture}, {@code WorkerDecisionEventCapture}) inject {@code
 * LedgerEntryRepository} directly for save and cross-subtype query operations. This class provides
 * case-scoped queries only.
 *
 * <p>{@code @DefaultBean} yields automatically to any explicitly selected alternative.
 */
@DefaultBean
@ApplicationScoped
public class CaseLedgerEntryRepository {

  @Inject @LedgerPersistenceUnit EntityManager em;

  @Transactional
  public List<CaseLedgerEntry> findByCaseId(final UUID caseId) {
    return em.createQuery(
            "SELECT e FROM CaseLedgerEntry e WHERE e.subjectId = :caseId ORDER BY e.sequenceNumber ASC",
            CaseLedgerEntry.class)
        .setParameter("caseId", caseId)
        .getResultList();
  }

  @Transactional
  public List<WorkerDecisionEntry> findWorkerDecisionsByCaseId(final UUID caseId) {
    return em.createQuery(
            "SELECT e FROM WorkerDecisionEntry e WHERE e.caseId = :caseId ORDER BY e.sequenceNumber ASC",
            WorkerDecisionEntry.class)
        .setParameter("caseId", caseId)
        .getResultList();
  }

  @Transactional
  public Optional<CaseLedgerEntry> findLatestByCaseId(final UUID caseId) {
    return em.createQuery(
            "SELECT e FROM CaseLedgerEntry e WHERE e.subjectId = :caseId ORDER BY e.sequenceNumber DESC",
            CaseLedgerEntry.class)
        .setParameter("caseId", caseId)
        .setMaxResults(1)
        .getResultStream()
        .findFirst();
  }
}
