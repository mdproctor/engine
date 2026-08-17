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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.ExecutionSnapshotStore;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaExecutionSnapshotStore extends TenantAwareRepository
    implements ExecutionSnapshotStore {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Override
  @Transactional
  public void storeDecomposition(UUID caseId, String tenancyId, DecompositionSnapshot snapshot) {
    setTenantContext(tenancyId);
    ExecutionSnapshotEntity entity = findOrCreate(caseId, tenancyId);
    entity.decompositionSnapshot = serialize(snapshot);
    entity.updatedAt = Instant.now();
    em.merge(entity);
  }

  @Override
  @Transactional
  public void storeDagPlan(UUID caseId, String tenancyId, DagPlanSnapshot snapshot) {
    setTenantContext(tenancyId);
    ExecutionSnapshotEntity entity = findOrCreate(caseId, tenancyId);
    entity.dagPlanSnapshot = serialize(snapshot);
    entity.updatedAt = Instant.now();
    em.merge(entity);
  }

  @Override
  @Transactional
  public void storeDagResult(UUID caseId, String tenancyId, DagResultSnapshot snapshot) {
    setTenantContext(tenancyId);
    ExecutionSnapshotEntity entity = findOrCreate(caseId, tenancyId);
    entity.dagResultSnapshot = serialize(snapshot);
    entity.updatedAt = Instant.now();
    em.merge(entity);
  }

  @Override
  @Transactional
  public Optional<DecompositionSnapshot> getDecomposition(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return findEntity(caseId, tenancyId)
        .map(e -> deserialize(e.decompositionSnapshot, DecompositionSnapshot.class));
  }

  @Override
  @Transactional
  public Optional<DagPlanSnapshot> getDagPlan(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return findEntity(caseId, tenancyId)
        .map(e -> deserialize(e.dagPlanSnapshot, DagPlanSnapshot.class));
  }

  @Override
  @Transactional
  public Optional<DagResultSnapshot> getDagResult(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return findEntity(caseId, tenancyId)
        .map(e -> deserialize(e.dagResultSnapshot, DagResultSnapshot.class));
  }

  @Override
  @Transactional
  public void evict(UUID caseId) {
    setCrossTenantContext();
    em.createQuery("DELETE FROM ExecutionSnapshotEntity e WHERE e.caseId = :caseId")
        .setParameter("caseId", caseId)
        .executeUpdate();
  }

  private ExecutionSnapshotEntity findOrCreate(UUID caseId, String tenancyId) {
    var results =
        em.createQuery(
                "SELECT e FROM ExecutionSnapshotEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tid",
                ExecutionSnapshotEntity.class)
            .setParameter("caseId", caseId)
            .setParameter("tid", tenancyId)
            .getResultList();
    if (!results.isEmpty()) {
      return results.get(0);
    }
    ExecutionSnapshotEntity entity = new ExecutionSnapshotEntity();
    entity.caseId = caseId;
    entity.tenancyId = tenancyId;
    entity.createdAt = Instant.now();
    entity.updatedAt = Instant.now();
    em.persist(entity);
    em.flush();
    return entity;
  }

  private Optional<ExecutionSnapshotEntity> findEntity(UUID caseId, String tenancyId) {
    var results =
        em.createQuery(
                "SELECT e FROM ExecutionSnapshotEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tid",
                ExecutionSnapshotEntity.class)
            .setParameter("caseId", caseId)
            .setParameter("tid", tenancyId)
            .getResultList();
    return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
  }

  private String serialize(Object snapshot) {
    if (snapshot == null) return null;
    try {
      return OBJECT_MAPPER.writeValueAsString(snapshot);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize snapshot", e);
    }
  }

  private <T> T deserialize(String json, Class<T> type) {
    if (json == null) return null;
    try {
      return OBJECT_MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize snapshot", e);
    }
  }
}
