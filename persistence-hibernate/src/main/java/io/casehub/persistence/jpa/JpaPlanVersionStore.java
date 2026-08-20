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
import io.casehub.engine.common.spi.recovery.PlanVersionStore;
import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.PlanVersion;
import io.casehub.engine.plan.snapshot.PlanVersionDelta;
import io.casehub.engine.plan.snapshot.PlanVersionTrigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaPlanVersionStore extends TenantAwareRepository implements PlanVersionStore {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Override
  @Transactional
  public void store(PlanVersion version, String tenancyId) {
    setTenantContext(tenancyId);
    PlanVersionEntity entity = new PlanVersionEntity();
    entity.caseId = version.caseId();
    entity.version = version.version();
    entity.tenancyId = tenancyId;
    entity.timestamp = version.timestamp();
    entity.triggerData = serialize(version.trigger());
    entity.snapshotData = serialize(version.snapshot());
    entity.deltaData = serialize(version.delta());
    em.persist(entity);
  }

  @Override
  @Transactional
  public List<PlanVersion> getHistory(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM PlanVersionEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tid ORDER BY e.version",
            PlanVersionEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .map(this::toModel)
        .toList();
  }

  @Override
  @Transactional
  public Optional<PlanVersion> getVersion(UUID caseId, int version, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM PlanVersionEntity e WHERE e.caseId = :caseId AND e.version = :ver AND e.tenancyId = :tid",
            PlanVersionEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("ver", version)
        .setParameter("tid", tenancyId)
        .getResultList()
        .stream()
        .findFirst()
        .map(this::toModel);
  }

  @Override
  @Transactional
  public Optional<PlanVersion> getLatest(UUID caseId, String tenancyId) {
    setTenantContext(tenancyId);
    return em
        .createQuery(
            "SELECT e FROM PlanVersionEntity e WHERE e.caseId = :caseId AND e.tenancyId = :tid ORDER BY e.version DESC",
            PlanVersionEntity.class)
        .setParameter("caseId", caseId)
        .setParameter("tid", tenancyId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst()
        .map(this::toModel);
  }

  @Override
  @Transactional
  public void evict(UUID caseId) {
    setCrossTenantContext();
    em.createQuery("DELETE FROM PlanVersionEntity e WHERE e.caseId = :caseId")
        .setParameter("caseId", caseId)
        .executeUpdate();
  }

  private PlanVersion toModel(PlanVersionEntity entity) {
    return new PlanVersion(
        entity.version,
        entity.caseId,
        entity.timestamp,
        deserialize(entity.triggerData, PlanVersionTrigger.class),
        deserialize(entity.snapshotData, CasePlanModelSnapshot.class),
        deserialize(entity.deltaData, PlanVersionDelta.class));
  }

  private String serialize(Object value) {
    if (value == null) return null;
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize plan version data", e);
    }
  }

  private <T> T deserialize(String json, Class<T> type) {
    if (json == null) return null;
    try {
      return OBJECT_MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to deserialize plan version data", e);
    }
  }
}
