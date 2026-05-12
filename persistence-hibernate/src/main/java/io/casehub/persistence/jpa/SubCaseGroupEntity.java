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

import io.casehub.api.model.OnThresholdReached;
import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
    name = "subcase_group",
    uniqueConstraints = @UniqueConstraint(columnNames = {"parent_case_id", "group_id"}))
public class SubCaseGroupEntity extends PanacheEntity {

  @Column(name = "parent_case_id", nullable = false)
  public UUID parentCaseId;

  @Column(name = "group_id", nullable = false, length = 255)
  public String groupId;

  @Column(name = "instance_count", nullable = false)
  public int instanceCount;

  @Column(name = "required_count", nullable = false)
  public int requiredCount;

  @Column(name = "completed_count", nullable = false)
  public int completedCount;

  @Column(name = "rejected_count", nullable = false)
  public int rejectedCount;

  @Column(name = "policy_triggered", nullable = false)
  public boolean policyTriggered;

  @Enumerated(EnumType.STRING)
  @Column(name = "on_threshold_reached", nullable = false, length = 50)
  public OnThresholdReached onThresholdReached;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "subcase_group_children",
      joinColumns = @JoinColumn(name = "group_entity_id"))
  @Column(name = "child_case_id")
  public Set<UUID> childCaseIds = new HashSet<>();
}
