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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.ConductorInboxEntry;
import io.casehub.engine.common.spi.ConductorInboxRepository;
import io.casehub.engine.common.spi.Resettable;
import io.quarkus.arc.DefaultBean;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryConductorInboxRepository implements ConductorInboxRepository, Resettable {

  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, ConductorInboxEntry>> entries =
      new ConcurrentHashMap<>();

  @Override
  public void save(ConductorInboxEntry entry, String tenancyId) {
    entries.computeIfAbsent(entry.caseId(), k -> new ConcurrentHashMap<>()).put(entry.id(), entry);
  }

  @Nullable
  @Override
  public ConductorInboxEntry findById(UUID caseId, String entryId, String tenancyId) {
    var caseEntries = entries.get(caseId);
    return caseEntries != null ? caseEntries.get(entryId) : null;
  }

  @Override
  public List<ConductorInboxEntry> findPending(UUID caseId, String tenancyId) {
    var caseEntries = entries.get(caseId);
    if (caseEntries == null) {
      return List.of();
    }
    return caseEntries.values().stream()
        .filter(e -> e.status() == ConductorInboxEntry.Status.PENDING)
        .toList();
  }

  @Override
  public int countPending(UUID caseId, String tenancyId) {
    var caseEntries = entries.get(caseId);
    if (caseEntries == null) {
      return 0;
    }
    return (int)
        caseEntries.values().stream()
            .filter(e -> e.status() == ConductorInboxEntry.Status.PENDING)
            .count();
  }

  @Override
  public List<ConductorInboxEntry> findAll(UUID caseId, String tenancyId) {
    var caseEntries = entries.get(caseId);
    if (caseEntries == null) {
      return List.of();
    }
    return List.copyOf(caseEntries.values());
  }

  @Override
  public void reset() {
    entries.clear();
  }
}
