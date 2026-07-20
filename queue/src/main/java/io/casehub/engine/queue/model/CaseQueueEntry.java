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
package io.casehub.engine.queue.model;

import java.time.Instant;
import java.util.UUID;

public class CaseQueueEntry {

  private UUID id;
  private UUID caseId;
  private String tenancyId;
  private UUID viewId;
  private String viewName;
  private QueueEntryStatus status;
  private String assignedTo;
  private Instant claimedAt;
  private Instant escalatedAt;
  private UUID previousViewId;
  private String previousViewName;
  private Instant createdAt;

  public CaseQueueEntry() {}

  public CaseQueueEntry(
      UUID id,
      UUID caseId,
      String tenancyId,
      UUID viewId,
      String viewName,
      QueueEntryStatus status,
      Instant createdAt) {
    this.id = id;
    this.caseId = caseId;
    this.tenancyId = tenancyId;
    this.viewId = viewId;
    this.viewName = viewName;
    this.status = status;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getCaseId() {
    return caseId;
  }

  public void setCaseId(UUID caseId) {
    this.caseId = caseId;
  }

  public String getTenancyId() {
    return tenancyId;
  }

  public void setTenancyId(String tenancyId) {
    this.tenancyId = tenancyId;
  }

  public UUID getViewId() {
    return viewId;
  }

  public void setViewId(UUID viewId) {
    this.viewId = viewId;
  }

  public String getViewName() {
    return viewName;
  }

  public void setViewName(String viewName) {
    this.viewName = viewName;
  }

  public QueueEntryStatus getStatus() {
    return status;
  }

  public void setStatus(QueueEntryStatus status) {
    this.status = status;
  }

  public String getAssignedTo() {
    return assignedTo;
  }

  public void setAssignedTo(String assignedTo) {
    this.assignedTo = assignedTo;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(Instant claimedAt) {
    this.claimedAt = claimedAt;
  }

  public Instant getEscalatedAt() {
    return escalatedAt;
  }

  public void setEscalatedAt(Instant escalatedAt) {
    this.escalatedAt = escalatedAt;
  }

  public UUID getPreviousViewId() {
    return previousViewId;
  }

  public void setPreviousViewId(UUID previousViewId) {
    this.previousViewId = previousViewId;
  }

  public String getPreviousViewName() {
    return previousViewName;
  }

  public void setPreviousViewName(String previousViewName) {
    this.previousViewName = previousViewName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
