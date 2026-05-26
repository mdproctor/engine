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
package io.casehub.resilience.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Worker;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Re-executes a dead-letter entry by recovering the original input from the EventLog and publishing
 * a fresh {@link WorkerScheduleEvent}. Returns empty if the entry cannot be replayed (not found,
 * wrong status, EventLog missing, case terminal, definition missing).
 */
@ApplicationScoped
public class DeadLetterReplayService {

  private static final Logger LOG = Logger.getLogger(DeadLetterReplayService.class);

  private final DeadLetterQueue deadLetterQueue;
  private final EventLogRepository eventLogRepository;
  private final CaseInstanceRepository caseInstanceRepository;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EventBus eventBus;

  @Inject
  public DeadLetterReplayService(
      DeadLetterQueue deadLetterQueue,
      EventLogRepository eventLogRepository,
      CaseInstanceRepository caseInstanceRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventBus eventBus) {
    this.deadLetterQueue = deadLetterQueue;
    this.eventLogRepository = eventLogRepository;
    this.caseInstanceRepository = caseInstanceRepository;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.eventBus = eventBus;
  }

  /**
   * Replays the dead-letter entry with the given ID. Returns the entry on success, empty if the
   * entry cannot be replayed.
   */
  public Optional<DeadLetterEntry> replay(String deadLetterId) {
    DeadLetterEntry entry = deadLetterQueue.findById(deadLetterId);

    if (entry == null) {
      LOG.warnf("DLQ replay: entry not found: %s", deadLetterId);
      return Optional.empty();
    }
    if (entry.status() != DeadLetterStatus.PENDING_REVIEW) {
      LOG.debugf("DLQ replay: entry %s is %s, skipping", deadLetterId, entry.status());
      return Optional.empty();
    }
    return doReplay(entry);
  }

  /** Replays all PENDING_REVIEW entries. Used by the auto-replay scheduler. */
  public List<DeadLetterEntry> replayPending() {
    return deadLetterQueue
        .query(DeadLetterQuery.withStatus(DeadLetterStatus.PENDING_REVIEW))
        .stream()
        .map(this::doReplay)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  private Optional<DeadLetterEntry> doReplay(DeadLetterEntry entry) {
    UUID caseId = entry.caseId();
    String workerId = entry.workerId();
    String idempotencyHash = entry.idempotencyHash();

    List<EventLog> scheduledEvents =
        eventLogRepository
            .findByCaseAndWorkerAndType(caseId, workerId, CaseHubEventType.WORKER_SCHEDULED)
            .await()
            .atMost(Duration.ofSeconds(10));

    EventLog originalScheduled =
        scheduledEvents.stream()
            .filter(
                e -> {
                  JsonNode meta = e.getMetadata();
                  JsonNode hashNode = meta == null ? null : meta.get("inputDataHash");
                  return hashNode != null && idempotencyHash.equals(hashNode.asText());
                })
            .findFirst()
            .orElse(null);

    if (originalScheduled == null) {
      LOG.warnf(
          "DLQ replay: no WORKER_SCHEDULED EventLog for caseId=%s workerId=%s hash=%s",
          caseId, workerId, idempotencyHash);
      return Optional.empty();
    }

    CaseInstance caseInstance =
        caseInstanceRepository.findByUuid(caseId).await().atMost(Duration.ofSeconds(10));

    if (caseInstance == null) {
      LOG.warnf("DLQ replay: CaseInstance not found for caseId=%s", caseId);
      return Optional.empty();
    }
    if (isTerminal(caseInstance.getState())) {
      LOG.warnf(
          "DLQ replay: case %s is %s — cannot accept new work", caseId, caseInstance.getState());
      return Optional.empty();
    }

    CaseDefinition definition =
        caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    if (definition == null) {
      LOG.warnf("DLQ replay: no CaseDefinition for caseId=%s", caseId);
      return Optional.empty();
    }

    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> w.getName().equals(workerId))
            .findFirst()
            .orElse(null);

    if (worker == null) {
      LOG.warnf(
          "DLQ replay: worker '%s' not found in CaseDefinition for caseId=%s", workerId, caseId);
      return Optional.empty();
    }

    Capability capability = worker.getCapabilities().stream().findFirst().orElse(null);
    if (capability == null) {
      LOG.warnf("DLQ replay: worker '%s' has no capabilities", workerId);
      return Optional.empty();
    }

    eventBus.publish(
        EventBusAddresses.WORKER_SCHEDULE,
        new WorkerScheduleEvent(caseInstance, worker, capability));

    entry.incrementReplayAttempts();
    deadLetterQueue.markReplayed(entry.deadLetterId());

    LOG.infof(
        "DLQ replay: submitted worker '%s' for caseId=%s (attempt %d)",
        workerId, caseId, entry.replayAttempts());

    return Optional.of(entry);
  }

  private static boolean isTerminal(CaseStatus state) {
    return state == CaseStatus.COMPLETED
        || state == CaseStatus.FAULTED
        || state == CaseStatus.CANCELLED;
  }
}
