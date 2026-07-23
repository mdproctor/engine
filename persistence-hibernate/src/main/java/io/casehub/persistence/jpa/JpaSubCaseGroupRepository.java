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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Blocking JPA {@link SubCaseGroupRepository}. Direct EntityManager implementation. */
@ApplicationScoped
public class JpaSubCaseGroupRepository extends TenantAwareRepository
    implements SubCaseGroupRepository {

  @Override
  @Transactional
  public SubCaseGroup getOrCreate(
      UUID parentCaseId,
      String groupId,
      int totalInGroup,
      int requiredCount,
      OnThresholdReached onThresholdReached,
      String tenancyId) {
    setTenantContext(tenancyId);
    List<SubCaseGroupEntity> existing =
        em.createQuery(
                "SELECT g FROM SubCaseGroupEntity g"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid",
                SubCaseGroupEntity.class)
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .getResultList();
    if (!existing.isEmpty()) {
      return toDomain(existing.get(0));
    }
    SubCaseGroupEntity e = new SubCaseGroupEntity();
    e.tenancyId = tenancyId;
    e.parentCaseId = parentCaseId;
    e.groupId = groupId;
    e.instanceCount = totalInGroup;
    e.requiredCount = requiredCount;
    e.onThresholdReached =
        onThresholdReached != null ? onThresholdReached : OnThresholdReached.KEEP;
    em.persist(e);
    em.flush();
    return toDomain(e);
  }

  @Override
  @Transactional
  public SubCaseGroup registerChild(
      UUID parentCaseId, String groupId, UUID childCaseId, String tenancyId) {
    setTenantContext(tenancyId);
    List<SubCaseGroupEntity> results =
        em.createQuery(
                "SELECT g FROM SubCaseGroupEntity g"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid",
                SubCaseGroupEntity.class)
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .getResultList();
    if (results.isEmpty()) {
      throw new IllegalStateException("Group not found: " + parentCaseId + ":" + groupId);
    }
    SubCaseGroupEntity e = results.get(0);
    e.childCaseIds.add(childCaseId);
    return toDomain(e);
  }

  @Override
  @Transactional
  public SubCaseGroup incrementCompleted(UUID parentCaseId, String groupId, String tenancyId) {
    setTenantContext(tenancyId);
    int count =
        em.createQuery(
                "UPDATE SubCaseGroupEntity g SET g.completedCount = g.completedCount + 1"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid")
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .executeUpdate();
    if (count == 0) {
      throw new IllegalStateException("Group not found: " + parentCaseId + ":" + groupId);
    }
    em.clear();
    List<SubCaseGroupEntity> results =
        em.createQuery(
                "SELECT g FROM SubCaseGroupEntity g"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid",
                SubCaseGroupEntity.class)
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .getResultList();
    if (results.isEmpty()) {
      throw new IllegalStateException("Group vanished after increment: " + parentCaseId);
    }
    return toDomain(results.get(0));
  }

  @Override
  @Transactional
  public SubCaseGroup incrementRejected(UUID parentCaseId, String groupId, String tenancyId) {
    setTenantContext(tenancyId);
    int count =
        em.createQuery(
                "UPDATE SubCaseGroupEntity g SET g.rejectedCount = g.rejectedCount + 1"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid")
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .executeUpdate();
    if (count == 0) {
      throw new IllegalStateException("Group not found: " + parentCaseId + ":" + groupId);
    }
    em.clear();
    List<SubCaseGroupEntity> results =
        em.createQuery(
                "SELECT g FROM SubCaseGroupEntity g"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid",
                SubCaseGroupEntity.class)
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .getResultList();
    if (results.isEmpty()) {
      throw new IllegalStateException("Group vanished after increment: " + parentCaseId);
    }
    return toDomain(results.get(0));
  }

  @Override
  @Transactional
  public boolean markPolicyTriggered(UUID parentCaseId, String groupId, String tenancyId) {
    setTenantContext(tenancyId);
    int count =
        em.createQuery(
                "UPDATE SubCaseGroupEntity g SET g.policyTriggered = true"
                    + " WHERE g.parentCaseId = :pid AND g.groupId = :gid AND g.tenancyId = :tid"
                    + " AND g.policyTriggered = false")
            .setParameter("pid", parentCaseId)
            .setParameter("gid", groupId)
            .setParameter("tid", tenancyId)
            .executeUpdate();
    return count > 0;
  }

  @Override
  @Transactional
  public Optional<SubCaseGroup> findByChildCaseId(UUID childCaseId, String tenancyId) {
    setTenantContext(tenancyId);
    List<SubCaseGroupEntity> results =
        em.createQuery(
                "SELECT g FROM SubCaseGroupEntity g"
                    + " WHERE :childId MEMBER OF g.childCaseIds AND g.tenancyId = :tid",
                SubCaseGroupEntity.class)
            .setParameter("childId", childCaseId)
            .setParameter("tid", tenancyId)
            .getResultList();
    return results.isEmpty() ? Optional.empty() : Optional.of(toDomain(results.get(0)));
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
    if (e.childCaseIds != null) {
      g.addAllChildCaseIds(e.childCaseIds);
    }
    return g;
  }
}
