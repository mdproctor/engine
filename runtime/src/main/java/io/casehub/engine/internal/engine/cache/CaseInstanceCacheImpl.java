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
package io.casehub.engine.internal.engine.cache;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CaseInstanceCacheImpl implements CaseInstanceCache {

  private final Map<UUID, CaseInstance> cache = new ConcurrentHashMap<>();

  @Override
  public void put(CaseInstance instance) {
    cache.put(instance.getUuid(), instance);
  }

  @Override
  public CaseInstance get(UUID caseId) {
    return cache.get(caseId);
  }

  @Override
  public void clear() {
    cache.clear();
  }

  @Override
  /** Returns a snapshot of all currently cached CaseInstances for timeout scanning. */
  public List<CaseInstance> getAll() {
    return List.copyOf(cache.values());
  }
}
