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
package io.casehub.work.engine;

import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Blocking JPA entity for {@link PlanItemStore} in the work-adapter context.
 *
 * <p>Maps to the same {@code plan_item} table as {@code PlanItemEntity} in {@code
 * casehub-persistence-hibernate}, but uses standard blocking JPA (no Panache reactive) so writes
 * participate in the same JTA transaction as {@link
 * io.casehub.work.runtime.service.WorkItemService}. The two entity classes live in different
 * classpath contexts and never co-exist at runtime.
 */
@Entity
@Table(
    name = "plan_item",
    indexes = {
      @Index(name = "idx_plan_item_plan_item_id", columnList = "plan_item_id"),
      @Index(name = "idx_plan_item_case_id", columnList = "case_id"),
      @Index(name = "idx_plan_item_tenancy_id", columnList = "tenancy_id")
    })
public class WorkAdapterPlanItemEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "plan_item_id", nullable = false, unique = true, length = 36)
  public String planItemId;

  @Column(name = "case_id", nullable = false)
  public UUID caseId;

  @Column(name = "binding_name", nullable = false, length = 255)
  public String bindingName;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 50)
  public PlanItemStatus status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", length = 20)
  public TargetType targetType;

  @Column(name = "output_mapping_expression", length = 1000)
  public String outputMappingExpression;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;
}
