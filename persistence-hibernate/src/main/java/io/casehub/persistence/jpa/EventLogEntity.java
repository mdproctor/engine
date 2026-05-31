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
package io.casehub.persistence.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "event_log",
    indexes = {@Index(name = "idx_event_log_tenancy_id", columnList = "tenancy_id")})
public class EventLogEntity extends PanacheEntity {

  @Column(
      name = "seq",
      nullable = false,
      updatable = false,
      insertable = false,
      columnDefinition = "BIGINT GENERATED ALWAYS AS IDENTITY")
  @Generated(event = EventType.INSERT)
  public Long seq;

  @Column(name = "case_id", nullable = false, updatable = false)
  public UUID caseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 255)
  public CaseHubEventType eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "stream_type", nullable = false, length = 255)
  public EventStreamType streamType;

  @Column(name = "worker_id", nullable = true, length = 255)
  public String workerId;

  @Column(name = "timestamp", nullable = false)
  public Instant timestamp;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  public JsonNode payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "jsonb")
  public JsonNode metadata;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;
}
