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

import io.casehub.ledger.runtime.model.jpa.JpaLedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
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
public class WorkerDecisionEntry extends JpaLedgerEntry {

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

  /**
   * CAPABILITY trust score read from {@code TrustScoreCache} at event observation time. Null when
   * no trust score exists for this worker+capability pair (Phase 0 / trust routing disabled).
   * Populated by {@link io.casehub.ledger.service.WorkerDecisionEventCapture}.
   */
  @Column(name = "trust_score_at_routing")
  public Double trustScoreAtRouting;

  /**
   * The threshold from {@code TrustRoutingPolicy} applied when this worker was selected. Null when
   * {@code trustScoreAtRouting} is null (no trust routing in play).
   */
  @Column(name = "threshold_applied")
  public Double thresholdApplied;

  /**
   * JSON snapshot of the full routing selection result at decision time. Contains the selected
   * candidate, all alternatives with scores/phases, and the applied policy. Null for entries
   * created before this field was added, or when the strategy does not provide a selection context.
   *
   * <p>Excluded from {@link #domainContentBytes()} — informational metadata, not part of the
   * tamper-evident content hash.
   */
  @Column(name = "routing_rationale", columnDefinition = "TEXT")
  public String routingRationale;

  @Override
  protected byte[] domainContentBytes() {
    return String.join(
            "|",
            workerId != null ? workerId : "",
            capabilityTag != null ? capabilityTag : "",
            caseId != null ? caseId.toString() : "",
            trustScoreAtRouting != null ? trustScoreAtRouting.toString() : "",
            thresholdApplied != null ? thresholdApplied.toString() : "")
        .getBytes(StandardCharsets.UTF_8);
  }
}
