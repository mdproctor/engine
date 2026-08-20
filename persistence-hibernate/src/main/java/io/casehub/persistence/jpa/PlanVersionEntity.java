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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "plan_version",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_plan_version_case_version",
          columnNames = {"case_id", "version"})
    },
    indexes = {
      @Index(name = "idx_plan_version_case_id", columnList = "case_id"),
      @Index(name = "idx_plan_version_tenancy", columnList = "tenancy_id")
    })
public class PlanVersionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id")
  public UUID id;

  @Column(name = "case_id", nullable = false)
  public UUID caseId;

  @Column(name = "version", nullable = false)
  public int version;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;

  @Column(name = "timestamp", nullable = false)
  public Instant timestamp;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "trigger_data", columnDefinition = "jsonb")
  public String triggerData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "snapshot_data", columnDefinition = "jsonb")
  public String snapshotData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "delta_data", columnDefinition = "jsonb")
  public String deltaData;
}
