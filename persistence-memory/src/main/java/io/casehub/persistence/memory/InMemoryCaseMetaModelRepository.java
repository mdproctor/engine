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

import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory blocking {@link CaseMetaModelRepository} for engine unit tests. Canonical
 * implementation — {@link InMemoryReactiveCaseMetaModelRepository} delegates to this.
 */
@Alternative
@ApplicationScoped
public class InMemoryCaseMetaModelRepository implements CaseMetaModelRepository {

  private final AtomicLong idSeq = new AtomicLong(0);
  private final ConcurrentHashMap<String, CaseMetaModel> store = new ConcurrentHashMap<>();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

  @Override
  public CaseMetaModel findByKey(String namespace, String name, String version, String tenancyId) {
    rwLock.readLock().lock();
    try {
      return store.get(key(tenancyId, namespace, name, version));
    } finally {
      rwLock.readLock().unlock();
    }
  }

  @Override
  public CaseMetaModel save(CaseMetaModel metaModel, String tenancyId) {
    rwLock.writeLock().lock();
    try {
      if (metaModel.id == null) {
        metaModel.id = idSeq.incrementAndGet();
      }
      if (metaModel.getCreatedAt() == null) {
        metaModel.setCreatedAt(Instant.now());
      }
      metaModel.tenancyId = tenancyId;
      store.put(
          key(tenancyId, metaModel.getNamespace(), metaModel.getName(), metaModel.getVersion()),
          metaModel);
      return metaModel;
    } finally {
      rwLock.writeLock().unlock();
    }
  }

  private String key(String tenancyId, String namespace, String name, String version) {
    return tenancyId + ":" + namespace + ":" + name + ":" + version;
  }
}
