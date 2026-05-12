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
import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "case_instance")
public class CaseInstanceEntity extends PanacheEntity {

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
}
