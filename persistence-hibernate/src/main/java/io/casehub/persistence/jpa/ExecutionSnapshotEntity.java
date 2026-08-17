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
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "execution_snapshot",
    indexes = {@Index(name = "idx_execution_snapshot_tenancy", columnList = "tenancy_id")})
public class ExecutionSnapshotEntity {

  @Id
  @Column(name = "case_id")
  public UUID caseId;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "decomposition_snapshot", columnDefinition = "jsonb")
  public String decompositionSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "dag_plan_snapshot", columnDefinition = "jsonb")
  public String dagPlanSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "dag_result_snapshot", columnDefinition = "jsonb")
  public String dagResultSnapshot;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
