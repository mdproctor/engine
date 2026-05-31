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
package io.casehub.ledger.model;

import io.casehub.ledger.runtime.model.LedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A ledger entry scoped to a single CaseHub lifecycle transition.
 *
 * <p>Extends the domain-agnostic {@link LedgerEntry} base using JPA JOINED inheritance. The {@code
 * case_ledger_entry} table holds case-specific fields; all common fields (subject, sequence, actor,
 * digest, supplements) live in {@code ledger_entry}.
 *
 * <p>{@code subjectId} (inherited) must equal {@code caseId} — they identify the same aggregate.
 * Sequence numbering and the Merkle chain are scoped per {@code subjectId}.
 */
@Entity
@Table(
    name = "case_ledger_entry",
    indexes = {@Index(name = "idx_case_ledger_entry_tenancy_id", columnList = "tenancy_id")})
@DiscriminatorValue("CASE")
public class CaseLedgerEntry extends LedgerEntry {

  /** The CaseInstance UUID — equals {@code subjectId}. */
  @Column(name = "case_id", nullable = false)
  public UUID caseId;

  /** Actor intent — e.g. {@code "StartCase"}, {@code "SuspendCase"}, {@code "SubmitWork"}. */
  @Column(name = "command_type", length = 100)
  public String commandType;

  /**
   * Observable fact — e.g. {@code "CaseStarted"}, {@code "CaseSuspended"}, {@code "WorkSubmitted"}.
   */
  @Column(name = "event_type", length = 100)
  public String eventType;

  /** Snapshot of {@code CaseStatus} at transition time. */
  @Column(name = "case_status", length = 50)
  public String caseStatus;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;
}
