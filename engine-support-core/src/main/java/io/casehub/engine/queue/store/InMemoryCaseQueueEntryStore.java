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
package io.casehub.engine.queue.store;

import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InMemoryCaseQueueEntryStore implements CaseQueueEntryStore {

  private final ConcurrentHashMap<UUID, CaseQueueEntry> store = new ConcurrentHashMap<>();

  @Override
  public CaseQueueEntry save(CaseQueueEntry entry) {
    store.put(entry.getId(), entry);
    return entry;
  }

  @Override
  public CaseQueueEntry upsertByCaseAndView(CaseQueueEntry entry) {
    Optional<CaseQueueEntry> existing = findByCaseAndView(entry.getCaseId(), entry.getViewId());
    if (existing.isPresent()) {
      CaseQueueEntry ex = existing.get();
      ex.setViewName(entry.getViewName());
      ex.setTenancyId(entry.getTenancyId());
      return ex;
    }
    return save(entry);
  }

  @Override
  public Optional<CaseQueueEntry> findById(UUID id) {
    return Optional.ofNullable(store.get(id));
  }

  @Override
  public Optional<CaseQueueEntry> findByCaseAndView(UUID caseId, UUID viewId) {
    return store.values().stream()
        .filter(e -> e.getCaseId().equals(caseId) && e.getViewId().equals(viewId))
        .findFirst();
  }

  @Override
  public List<CaseQueueEntry> findByView(UUID viewId, String tenancyId) {
    return store.values().stream()
        .filter(e -> e.getViewId().equals(viewId) && tenancyId.equals(e.getTenancyId()))
        .toList();
  }

  @Override
  public List<CaseQueueEntry> findByCaseId(UUID caseId) {
    return store.values().stream().filter(e -> e.getCaseId().equals(caseId)).toList();
  }

  @Override
  public long countByView(UUID viewId, String tenancyId) {
    return store.values().stream()
        .filter(e -> e.getViewId().equals(viewId) && tenancyId.equals(e.getTenancyId()))
        .count();
  }

  @Override
  public boolean delete(UUID id) {
    return store.remove(id) != null;
  }

  @Override
  public void deleteByCaseId(UUID caseId) {
    store.values().removeIf(e -> e.getCaseId().equals(caseId));
  }

  @Override
  public synchronized Optional<CaseQueueEntry> claimIfPending(UUID entryId, String userId) {
    CaseQueueEntry entry = store.get(entryId);
    if (entry == null || entry.getStatus() != QueueEntryStatus.PENDING) {
      return Optional.empty();
    }
    entry.setStatus(QueueEntryStatus.CLAIMED);
    entry.setAssignedTo(userId);
    entry.setClaimedAt(Instant.now());
    return Optional.of(entry);
  }
}
