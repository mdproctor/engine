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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.api.model.RetryState;
import io.casehub.engine.resilience.deadletter.DeadLetterEntry;
import io.casehub.engine.resilience.deadletter.DeadLetterEntryStore;
import io.casehub.engine.resilience.deadletter.DeadLetterQuery;
import io.casehub.engine.resilience.deadletter.DeadLetterStatus;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Alternative
@Priority(2)
@ApplicationScoped
public class JpaDeadLetterEntryStore implements DeadLetterEntryStore {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final EntityManager em;
  private final TenantContextManager tcm;

  @Inject
  JpaDeadLetterEntryStore(EntityManager em, TenantContextManager tcm) {
    this.em = em;
    this.tcm = tcm;
  }

  @Override
  @Transactional
  public DeadLetterEntry save(DeadLetterEntry entry) {
    DeadLetterEntryEntity entity = toEntity(entry);
    em.persist(entity);
    em.flush();
    return entry;
  }

  @Override
  @Transactional
  public DeadLetterEntry findById(String deadLetterId) {
    tcm.setCrossTenantContext();
    var results =
        em.createQuery(
                "SELECT e FROM DeadLetterEntryEntity e WHERE e.deadLetterId = :dlid",
                DeadLetterEntryEntity.class)
            .setParameter("dlid", deadLetterId)
            .getResultList();
    return results.isEmpty() ? null : fromEntity(results.get(0));
  }

  @Override
  @Transactional
  public List<DeadLetterEntry> query(DeadLetterQuery query) {
    tcm.setCrossTenantContext();
    return em
        .createQuery("SELECT e FROM DeadLetterEntryEntity e", DeadLetterEntryEntity.class)
        .getResultList()
        .stream()
        .map(this::fromEntity)
        .filter(query.toPredicate())
        .toList();
  }

  @Override
  @Transactional
  public void updateStatus(String deadLetterId, DeadLetterStatus status) {
    tcm.setCrossTenantContext();
    em.createQuery("UPDATE DeadLetterEntryEntity e SET e.status = :s WHERE e.deadLetterId = :dlid")
        .setParameter("s", status.name())
        .setParameter("dlid", deadLetterId)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void incrementReplayAttempts(String deadLetterId) {
    tcm.setCrossTenantContext();
    em.createQuery(
            "UPDATE DeadLetterEntryEntity e SET e.replayAttempts = e.replayAttempts + 1, "
                + "e.lastReplayAttemptAt = :now WHERE e.deadLetterId = :dlid")
        .setParameter("now", Instant.now())
        .setParameter("dlid", deadLetterId)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void deleteAll() {
    tcm.setCrossTenantContext();
    em.createQuery("DELETE FROM DeadLetterEntryEntity").executeUpdate();
  }

  private DeadLetterEntryEntity toEntity(DeadLetterEntry entry) {
    DeadLetterEntryEntity entity = new DeadLetterEntryEntity();
    entity.deadLetterId = entry.deadLetterId();
    entity.caseId = entry.caseId();
    entity.workerId = entry.workerId();
    entity.idempotencyHash = entry.idempotencyHash();
    try {
      entity.inputContext = MAPPER.writeValueAsString(entry.inputContext());
      entity.retryState =
          entry.retryState() != null ? MAPPER.writeValueAsString(entry.retryState()) : null;
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize DLQ entry", e);
    }
    entity.status = entry.status().name();
    entity.replayAttempts = entry.replayAttempts();
    entity.arrivedAt = entry.arrivedAt();
    entity.lastReplayAttemptAt = entry.lastReplayAttemptAt();
    entity.tenancyId = "default";
    return entity;
  }

  private DeadLetterEntry fromEntity(DeadLetterEntryEntity entity) {
    Map<String, Object> inputContext;
    RetryState retryState;
    try {
      inputContext =
          entity.inputContext != null ? MAPPER.readValue(entity.inputContext, MAP_TYPE) : Map.of();
      retryState =
          entity.retryState != null
              ? MAPPER.readValue(entity.retryState, RetryState.class)
              : RetryState.empty();
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize DLQ entry", e);
    }
    DeadLetterEntry entry =
        new DeadLetterEntry(
            entity.deadLetterId,
            entity.caseId,
            entity.workerId,
            entity.idempotencyHash,
            inputContext,
            retryState);
    entry.setStatus(DeadLetterStatus.valueOf(entity.status));
    entry.setReplayState(entity.replayAttempts, entity.lastReplayAttemptAt);
    entry.setArrivedAt(entity.arrivedAt);
    return entry;
  }
}
