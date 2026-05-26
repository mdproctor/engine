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
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaSubCaseGroupRepository implements SubCaseGroupRepository {

  @Override
  public Uni<SubCaseGroup> getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached) {
    return Panache.withTransaction(
        () ->
            SubCaseGroupEntity.<SubCaseGroupEntity>find(
                    "parentCaseId = ?1 and groupId = ?2", parentCaseId, groupId)
                .firstResult()
                .flatMap(
                    existing -> {
                      if (existing != null) return Uni.createFrom().item(toDomain(existing));
                      SubCaseGroupEntity e = new SubCaseGroupEntity();
                      e.parentCaseId = parentCaseId;
                      e.groupId = groupId;
                      e.instanceCount = totalInGroup;
                      e.requiredCount = requiredCount;
                      e.onThresholdReached =
                          onThresholdReached != null ? onThresholdReached : OnThresholdReached.KEEP;
                      return e.<SubCaseGroupEntity>persist().map(this::toDomain);
                    }));
  }

  @Override
  public Uni<SubCaseGroup> registerChild(UUID parentCaseId, String groupId, UUID childCaseId) {
    return Panache.withTransaction(
        () ->
            SubCaseGroupEntity.<SubCaseGroupEntity>find(
                    "parentCaseId = ?1 and groupId = ?2", parentCaseId, groupId)
                .firstResult()
                .flatMap(
                    e -> {
                      if (e == null)
                        return Uni.createFrom()
                            .failure(
                                new IllegalStateException(
                                    "Group not found: " + parentCaseId + ":" + groupId));
                      e.childCaseIds.add(childCaseId);
                      return Uni.createFrom().item(toDomain(e));
                    }));
  }

  @Override
  public Uni<SubCaseGroup> incrementCompleted(UUID parentCaseId, String groupId) {
    // Atomic JPQL increment — avoids read-modify-write race under PostgreSQL READ COMMITTED.
    // The returned SubCaseGroup reflects DB state at SELECT time; may include concurrent
    // increments.
    // SubCaseGroupPolicy.evaluate() reads counts and handles any total correctly. Refs engine#248.
    return Panache.withTransaction(
        () ->
            SubCaseGroupEntity.update(
                    "completedCount = completedCount + 1 WHERE parentCaseId = ?1 AND groupId = ?2",
                    parentCaseId,
                    groupId)
                .chain(
                    count -> {
                      if (count == 0)
                        return Uni.createFrom()
                            .failure(
                                new IllegalStateException(
                                    "Group not found: " + parentCaseId + ":" + groupId));
                      return SubCaseGroupEntity.<SubCaseGroupEntity>find(
                              "parentCaseId = ?1 and groupId = ?2", parentCaseId, groupId)
                          .firstResult()
                          .onItem()
                          .ifNotNull()
                          .transform(this::toDomain)
                          .onItem()
                          .ifNull()
                          .failWith(
                              () ->
                                  new IllegalStateException(
                                      "Group vanished after increment: " + parentCaseId));
                    }));
  }

  @Override
  public Uni<SubCaseGroup> incrementRejected(UUID parentCaseId, String groupId) {
    return Panache.withTransaction(
        () ->
            SubCaseGroupEntity.update(
                    "rejectedCount = rejectedCount + 1 WHERE parentCaseId = ?1 AND groupId = ?2",
                    parentCaseId,
                    groupId)
                .chain(
                    count -> {
                      if (count == 0)
                        return Uni.createFrom()
                            .failure(
                                new IllegalStateException(
                                    "Group not found: " + parentCaseId + ":" + groupId));
                      return SubCaseGroupEntity.<SubCaseGroupEntity>find(
                              "parentCaseId = ?1 and groupId = ?2", parentCaseId, groupId)
                          .firstResult()
                          .onItem()
                          .ifNotNull()
                          .transform(this::toDomain)
                          .onItem()
                          .ifNull()
                          .failWith(
                              () ->
                                  new IllegalStateException(
                                      "Group vanished after increment: " + parentCaseId));
                    }));
  }

  @Override
  public Uni<Boolean> markPolicyTriggered(UUID parentCaseId, String groupId) {
    return Panache.withTransaction(
        () ->
            SubCaseGroupEntity.update(
                    "policyTriggered = true WHERE parentCaseId = ?1 AND groupId = ?2 AND policyTriggered = false",
                    parentCaseId,
                    groupId)
                .map(count -> count > 0));
  }

  @Override
  public Uni<Optional<SubCaseGroup>> findByChildCaseId(UUID childCaseId) {
    return Panache.withSession(
        () ->
            SubCaseGroupEntity.<SubCaseGroupEntity>find("?1 member of childCaseIds", childCaseId)
                .firstResult()
                .map(e -> Optional.ofNullable(e == null ? null : toDomain(e))));
  }

  private SubCaseGroup toDomain(SubCaseGroupEntity e) {
    SubCaseGroup g = new SubCaseGroup();
    g.setParentCaseId(e.parentCaseId);
    g.setGroupId(e.groupId);
    g.setInstanceCount(e.instanceCount);
    g.setRequiredCount(e.requiredCount);
    g.setCompletedCount(e.completedCount);
    g.setRejectedCount(e.rejectedCount);
    g.setPolicyTriggered(e.policyTriggered);
    g.setOnThresholdReached(e.onThresholdReached);
    if (e.childCaseIds != null) g.addAllChildCaseIds(e.childCaseIds);
    return g;
  }
}
