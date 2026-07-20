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
package io.casehub.engine.queue.entry;

import io.casehub.engine.queue.event.CaseQueueEntryRevoked;
import io.casehub.engine.queue.event.CaseQueueEvent;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CaseQueueEntryManager {

  private static final Logger LOG = Logger.getLogger(CaseQueueEntryManager.class);

  @Inject CaseQueueEntryStore store;

  @Inject Event<CaseQueueEntryRevoked> revokedEvents;

  public void onQueueEvent(@Observes CaseQueueEvent event) {
    switch (event.eventType()) {
      case ADDED -> handleAdded(event);
      case REMOVED -> handleRemoved(event);
      case CHANGED -> {}
    }
  }

  private void handleAdded(CaseQueueEvent event) {
    Optional<CaseQueueEntry> existing =
        store.findByCaseAndView(event.caseId(), event.queueViewId());

    if (existing.isPresent()) {
      CaseQueueEntry entry = existing.get();
      if (entry.getStatus() == QueueEntryStatus.REVOKED) {
        entry.setStatus(QueueEntryStatus.PENDING);
        entry.setCreatedAt(Instant.now());
        entry.setAssignedTo(null);
        entry.setClaimedAt(null);
        entry.setEscalatedAt(null);
        entry.setPreviousViewId(null);
        entry.setPreviousViewName(null);
        store.save(entry);
        LOG.debugf(
            "Re-activated REVOKED entry for caseId=%s viewId=%s",
            event.caseId(), event.queueViewId());
      }
      return;
    }

    CaseQueueEntry entry =
        new CaseQueueEntry(
            UUID.randomUUID(),
            event.caseId(),
            event.tenancyId(),
            event.queueViewId(),
            event.queueName(),
            QueueEntryStatus.PENDING,
            Instant.now());
    store.save(entry);
  }

  private void handleRemoved(CaseQueueEvent event) {
    Optional<CaseQueueEntry> existing =
        store.findByCaseAndView(event.caseId(), event.queueViewId());
    if (existing.isEmpty()) {
      return;
    }

    CaseQueueEntry entry = existing.get();
    if (entry.getStatus() == QueueEntryStatus.PENDING) {
      store.delete(entry.getId());
    } else if (entry.getStatus() == QueueEntryStatus.CLAIMED) {
      String previousAssignee = entry.getAssignedTo();
      entry.setStatus(QueueEntryStatus.REVOKED);
      store.save(entry);
      revokedEvents.fireAsync(new CaseQueueEntryRevoked(entry, previousAssignee));
    }
  }
}
