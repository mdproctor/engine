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
package io.casehub.engine.queue.service;

import io.casehub.engine.queue.event.CaseQueueEntryClaimed;
import io.casehub.engine.queue.event.CaseQueueEntryEscalated;
import io.casehub.engine.queue.event.CaseQueueEntryReleased;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CaseQueueService {

  @Inject CaseQueueEntryStore store;

  @Inject Event<CaseQueueEntryClaimed> claimedEvents;

  @Inject Event<CaseQueueEntryReleased> releasedEvents;

  @Inject Event<CaseQueueEntryEscalated> escalatedEvents;

  public CaseQueueEntry claim(UUID entryId, String tenancyId, String userId) {
    CaseQueueEntry entry = loadAndVerifyTenancy(entryId, tenancyId);
    CaseQueueEntry claimed =
        store
            .claimIfPending(entryId, userId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Entry "
                            + entryId
                            + " is not PENDING (status: "
                            + entry.getStatus()
                            + ")"));
    claimedEvents.fireAsync(new CaseQueueEntryClaimed(claimed, userId));
    return claimed;
  }

  public CaseQueueEntry release(UUID entryId, String tenancyId) {
    CaseQueueEntry entry = loadAndVerifyTenancy(entryId, tenancyId);
    if (entry.getStatus() != QueueEntryStatus.CLAIMED) {
      throw new IllegalStateException(
          "Entry " + entryId + " is not CLAIMED (status: " + entry.getStatus() + ")");
    }
    entry.setStatus(QueueEntryStatus.PENDING);
    entry.setAssignedTo(null);
    entry.setClaimedAt(null);
    store.save(entry);
    releasedEvents.fireAsync(new CaseQueueEntryReleased(entry));
    return entry;
  }

  public CaseQueueEntry escalate(UUID entryId, String tenancyId, UUID targetViewId) {
    CaseQueueEntry entry = loadAndVerifyTenancy(entryId, tenancyId);
    if (entry.getViewId().equals(targetViewId)) {
      throw new IllegalStateException("Case is already in target queue " + targetViewId);
    }
    if (store.findByCaseAndView(entry.getCaseId(), targetViewId).isPresent()) {
      throw new IllegalStateException(
          "Case " + entry.getCaseId() + " already has an entry in target queue " + targetViewId);
    }

    UUID sourceViewId = entry.getViewId();
    String sourceViewName = entry.getViewName();
    entry.setPreviousViewId(sourceViewId);
    entry.setPreviousViewName(sourceViewName);
    entry.setViewId(targetViewId);
    entry.setViewName(null);
    entry.setStatus(QueueEntryStatus.PENDING);
    entry.setAssignedTo(null);
    entry.setClaimedAt(null);
    entry.setEscalatedAt(Instant.now());
    store.save(entry);
    escalatedEvents.fireAsync(new CaseQueueEntryEscalated(entry, sourceViewId, targetViewId));
    return entry;
  }

  public List<CaseQueueEntry> findPending(UUID viewId, String tenancyId) {
    return store.findByView(viewId, tenancyId).stream()
        .filter(e -> e.getStatus() == QueueEntryStatus.PENDING)
        .toList();
  }

  public long countByView(UUID viewId, String tenancyId) {
    return store.countByView(viewId, tenancyId);
  }

  private CaseQueueEntry loadAndVerifyTenancy(UUID entryId, String tenancyId) {
    CaseQueueEntry entry =
        store
            .findById(entryId)
            .orElseThrow(() -> new IllegalStateException("Queue entry not found: " + entryId));
    if (!tenancyId.equals(entry.getTenancyId())) {
      throw new IllegalArgumentException(
          "Tenancy mismatch: expected "
              + tenancyId
              + " but entry belongs to "
              + entry.getTenancyId());
    }
    return entry;
  }
}
