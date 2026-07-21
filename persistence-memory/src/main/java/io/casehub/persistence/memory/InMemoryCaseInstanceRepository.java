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
package io.casehub.persistence.memory;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory blocking {@link CaseInstanceRepository} for engine unit tests. Also implements {@link
 * CrossTenantCaseInstanceRepository} for recovery service testing. Canonical implementation —
 * {@link InMemoryReactiveCaseInstanceRepository} delegates to this.
 */
@Alternative
@ApplicationScoped
public class InMemoryCaseInstanceRepository
    implements CaseInstanceRepository, CrossTenantCaseInstanceRepository {

  private final AtomicLong idSeq = new AtomicLong(0);
  private final ConcurrentHashMap<UUID, CaseInstance> store = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  @Inject EventLogRepository eventLogRepository;

  void setEventLogRepository(EventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public CaseInstance save(CaseInstance instance, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      if (instance.id == null) {
        instance.id = idSeq.incrementAndGet();
      }
      instance.tenancyId = tenancyId;
      store.put(instance.getUuid(), instance);
      return instance;
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Override
  public CaseInstance update(CaseInstance instance, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      CaseInstance existing = store.get(instance.getUuid());
      if (existing == null || !tenancyId.equals(existing.tenancyId)) {
        throw new IllegalStateException(
            "CaseInstance not found or wrong tenant: " + instance.getUuid());
      }
      store.put(instance.getUuid(), instance);
      return instance;
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Override
  public CaseInstance findByUuid(UUID uuid, String tenancyId) {
    rwLock.readLock().lock();
    try {
      CaseInstance instance = store.get(uuid);
      if (instance != null && !tenancyId.equals(instance.tenancyId)) {
        return null;
      }
      return instance;
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public CaseInstance findByUuid(UUID uuid) {
    rwLock.readLock().lock();
    try {
      return store.get(uuid);
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public void updateStateAndAppendEvent(
      CaseInstance instance, EventLog eventLog, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      instance.tenancyId = tenancyId;
      store.put(instance.getUuid(), instance);
      eventLogRepository.append(eventLog, tenancyId);
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  @Override
  public List<CaseInstance> findByStatus(CaseStatus status, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(ci -> tenancyId.equals(ci.tenancyId) && ci.getState() == status)
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<CaseInstance> findAll(String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream().filter(ci -> tenancyId.equals(ci.tenancyId)).toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<CaseInstance> findByNamespaceAndName(
      String namespace, String name, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(
              ci ->
                  tenancyId.equals(ci.tenancyId)
                      && ci.getCaseMetaModel() != null
                      && namespace.equals(ci.getCaseMetaModel().getNamespace())
                      && name.equals(ci.getCaseMetaModel().getName()))
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public List<CaseInstance> query(CaseInstanceQuery query, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(i -> i.tenancyId != null && i.tenancyId.equals(tenancyId))
          .filter(i -> query.status() == null || query.status().equals(i.getState()))
          .filter(
              i -> {
                if (query.namespace() == null) {
                  return true;
                }
                var meta = i.getCaseMetaModel();
                return meta != null && query.namespace().equals(meta.getNamespace());
              })
          .filter(
              i -> {
                if (query.name() == null) {
                  return true;
                }
                var meta = i.getCaseMetaModel();
                return meta != null && query.name().equals(meta.getName());
              })
          .skip((long) query.page() * query.size())
          .limit(query.size())
          .toList();
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public long count(CaseInstanceQuery query, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.values().stream()
          .filter(i -> i.tenancyId != null && i.tenancyId.equals(tenancyId))
          .filter(i -> query.status() == null || query.status().equals(i.getState()))
          .filter(
              i -> {
                if (query.namespace() == null) {
                  return true;
                }
                var meta = i.getCaseMetaModel();
                return meta != null && query.namespace().equals(meta.getNamespace());
              })
          .filter(
              i -> {
                if (query.name() == null) {
                  return true;
                }
                var meta = i.getCaseMetaModel();
                return meta != null && query.name().equals(meta.getName());
              })
          .count();
    } finally {
      rwLock.readLock().unlock();
    }
  }
}
