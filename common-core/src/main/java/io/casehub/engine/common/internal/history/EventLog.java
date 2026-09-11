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
package io.casehub.engine.common.internal.history;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain domain object for an immutable audit event. {@code id} and {@code seq} are populated by the
 * repository after append. Static Panache query methods have been removed; use {@link
 * io.casehub.engine.common.spi.EventLogRepository} instead.
 *
 * <p>{@code workerId} is intentionally denormalised so events can be filtered by worker without
 * parsing the JSON metadata payload.
 */
public class EventLog {

  /** Populated by the repository after append. Null until first persisted. */
  public Long id;

  /** Tenant this event belongs to. Set by the repository at append; never updated. */
  public String tenancyId;

  private UUID caseId;
  private Long seq;
  private CaseHubEventType eventType;
  private EventStreamType streamType;
  private String workerId;
  private Instant timestamp;
  private JsonNode payload;
  private JsonNode metadata;

  public UUID getCaseId() {
    return caseId;
  }

  public void setCaseId(UUID caseId) {
    this.caseId = caseId;
  }

  public Long getSeq() {
    return seq;
  }

  public void setSeq(Long seq) {
    this.seq = seq;
  }

  public CaseHubEventType getEventType() {
    return eventType;
  }

  public void setEventType(CaseHubEventType eventType) {
    this.eventType = eventType;
  }

  public EventStreamType getStreamType() {
    return streamType;
  }

  public void setStreamType(EventStreamType streamType) {
    this.streamType = streamType;
  }

  public String getWorkerId() {
    return workerId;
  }

  public void setWorkerId(String workerId) {
    this.workerId = workerId;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public void setPayload(JsonNode payload) {
    this.payload = payload;
  }

  public JsonNode getMetadata() {
    return metadata;
  }

  public void setMetadata(JsonNode metadata) {
    this.metadata = metadata;
  }
}
