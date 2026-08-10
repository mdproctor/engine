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

import io.casehub.api.model.CaseStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@DynamicUpdate
@Table(
    name = "case_instance",
    indexes = {@Index(name = "idx_case_instance_tenancy_id", columnList = "tenancy_id")})
public class CaseInstanceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(name = "uuid", nullable = false, unique = true, updatable = false)
  public UUID uuid;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 50)
  public CaseStatus state;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "case_definition_id", nullable = false)
  public CaseMetaModelEntity caseMetaModel;

  @Column(name = "parent_plan_item_id", nullable = true)
  public UUID parentPlanItemId;

  @Column(name = "parent_case_id", nullable = true)
  public UUID parentCaseId;

  @Column(name = "waiting_for_work_id", nullable = true, length = 255)
  public String waitingForWorkId;

  @Column(name = "tenancy_id", nullable = false, length = 64)
  public String tenancyId;

  @Column(name = "actor_id", nullable = true, length = 255)
  public String actorId;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "case_instance_label",
      joinColumns = @JoinColumn(name = "case_instance_id"))
  @Column(name = "label")
  public Set<String> labels = new LinkedHashSet<>();
  @Column(name = "exchange_headers", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  public java.util.Map<String, Object> exchangeHeaders;

}
