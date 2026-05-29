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
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A ledger entry recording that a worker exercised a named capability for a case.
 *
 * <p>Extends {@link LedgerEntry} using JPA JOINED inheritance. The {@code worker_decision_entry}
 * table holds worker-specific fields; all common fields (actor, sequence, digest, supplements) live
 * in {@code ledger_entry}.
 *
 * <p>Written by {@link io.casehub.ledger.service.WorkerDecisionEventCapture} in response to a
 * {@link io.casehub.engine.common.spi.event.WorkerDecisionEvent}. One entry per successful worker
 * execution.
 *
 * <p>These entries are the trust-scoring anchor for {@code TrustScoreJob}: attestations reference
 * {@code ledgerEntry.id} to attribute investigation outcomes (e.g. "SAR was FLAGGED") to a specific
 * worker capability decision.
 */
@Entity
@Table(name = "worker_decision_entry")
@DiscriminatorValue("WORKER_DECISION")
public class WorkerDecisionEntry extends LedgerEntry {

  /** The worker name from the case definition — equals {@code actorId}. */
  @Column(name = "worker_id", nullable = false)
  public String workerId;

  /**
   * The capability exercised by this worker (e.g. {@code "sar-drafting"}).
   *
   * <p>Null when no matching binding is found in the case definition. Should not occur in practice
   * but nullable to avoid blocking the worker completion path.
   */
  @Column(name = "capability_tag")
  public String capabilityTag;

  /** The CaseInstance UUID — equals {@code subjectId}. */
  @Column(name = "case_id", nullable = false)
  public UUID caseId;
}
