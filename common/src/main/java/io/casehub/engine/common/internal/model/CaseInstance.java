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
package io.casehub.engine.common.internal.model;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.CaseStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Plain domain object representing one running case. Persistence is handled by the SPI. */
public class CaseInstance {

  /** Populated by the repository after save. Null until first persisted. */
  public Long id;

  /** Tenant this case belongs to. Set by the repository at save; never updated. */
  public String tenancyId;

  private CaseMetaModel caseMetaModel;
  private UUID uuid;
  private long version = 0L;
  private CaseContext caseContext;
  private PropagationContext propagationContext;
  private String waitingForWorkId;
  private UUID parentPlanItemId;
  private CaseStatus state;
  private final java.util.concurrent.locks.ReentrantLock stateLock =
      new java.util.concurrent.locks.ReentrantLock();

  public CaseMetaModel getCaseMetaModel() {
    return caseMetaModel;
  }

  public void setCaseMetaModel(CaseMetaModel caseMetaModel) {
    this.caseMetaModel = caseMetaModel;
  }

  public UUID getUuid() {
    return uuid;
  }

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public long getVersion() {
    return version;
  }

  public void setVersion(long version) {
    this.version = version;
  }

  public CaseContext getCaseContext() {
    return caseContext;
  }

  public void setCaseContext(CaseContext caseContext) {
    this.caseContext = caseContext;
  }

  public PropagationContext getPropagationContext() {
    return propagationContext;
  }

  public void setPropagationContext(PropagationContext propagationContext) {
    this.propagationContext = propagationContext;
  }

  public String getWaitingForWorkId() {
    return waitingForWorkId;
  }

  public void setWaitingForWorkId(String waitingForWorkId) {
    this.waitingForWorkId = waitingForWorkId;
  }

  public UUID getParentPlanItemId() {
    return parentPlanItemId;
  }

  public void setParentPlanItemId(UUID parentPlanItemId) {
    this.parentPlanItemId = parentPlanItemId;
  }

  private UUID parentCaseId;

  public UUID getParentCaseId() {
    return parentCaseId;
  }

  public void setParentCaseId(UUID parentCaseId) {
    this.parentCaseId = parentCaseId;
  }

  private String actorId;

  public String getActorId() {
    return actorId;
  }

  public void setActorId(String actorId) {
    this.actorId = actorId;
  }

  public CaseStatus getState() {
    return state;
  }

  public void setState(CaseStatus state) {
    this.state = state;
  }

  public boolean trySetTerminalState(CaseStatus newState) {
    stateLock.lock();
    try {
      if (state == CaseStatus.COMPLETED
          || state == CaseStatus.FAULTED
          || state == CaseStatus.CANCELLED) {
        return false;
      }
      this.state = newState;
      return true;
    } finally {
      stateLock.unlock();
    }
  }

  /**
   * Non-null while an action gate is pending human approval. Set by the engine when {@link
   * io.casehub.api.spi.RiskDecision.GateRequired} fires; cleared by the gate resolution handlers
   * after processing.
   *
   * <p><strong>In-memory only in v1 — not persisted by {@code CaseInstanceEntity}.</strong> If the
   * engine restarts while a gate is pending, the gate is lost from memory. The WorkItem in
   * quarkus-work survives restart, but when it resolves, the resolution handler sees null from the
   * cache and discards the approval silently — the case stalls. When v2 adds the {@code
   * pending_action_gate} column to {@code CaseInstanceEntity}, resolution handlers must also call
   * {@code caseInstanceRepository.update()} to clear it.
   *
   * <p>Only one gate is supported per case in v1. If a second worker returns a PlannedAction while
   * this field is non-null, the engine proceeds as Autonomous for the second action and logs an
   * ERROR. The second action bypasses classification — a known v1 constraint.
   */
  private PendingActionGate pendingActionGate;

  public PendingActionGate getPendingActionGate() {
    return pendingActionGate;
  }

  public void setPendingActionGate(final PendingActionGate pendingActionGate) {
    this.pendingActionGate = pendingActionGate;
  }

  private Set<String> labels = new LinkedHashSet<>();
  private Set<String> types = new LinkedHashSet<>();
  private java.util.Map<String, Object> exchangeHeaders = new java.util.LinkedHashMap<>();

  public java.util.Map<String, Object> getExchangeHeaders() {
    return exchangeHeaders;
  }

  public void setExchangeHeaders(java.util.Map<String, Object> exchangeHeaders) {
    this.exchangeHeaders =
        exchangeHeaders != null ? exchangeHeaders : new java.util.LinkedHashMap<>();
  }

  public void mergeExchangeHeaders(java.util.Map<String, Object> incoming) {
    if (incoming != null && !incoming.isEmpty()) {
      this.exchangeHeaders.putAll(incoming);
    }
  }

  public Set<String> getLabels() {
    return labels;
  }

  public void setLabels(Set<String> labels) {
    this.labels = labels;
  }

  public Set<String> getTypes() {
    return types;
  }

  public void setTypes(Set<String> types) {
    this.types = types;
  }

  private Instant createdAt;

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
