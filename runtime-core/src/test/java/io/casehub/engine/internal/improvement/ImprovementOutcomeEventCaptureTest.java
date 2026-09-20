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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.model.stigmergy.ImprovementOutcome;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementOutcomeEventCaptureTest {

  private ImprovementOutcomeEventCapture capture;
  private RecordingEventLogRepository eventLogRepo;
  private SignalRegistry signalRegistry;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private UUID caseId;

  @BeforeEach
  void setUp() {
    eventLogRepo = new RecordingEventLogRepository();
    signalRegistry = new SignalRegistry();
    budgetEnforcer = new ImprovementBudgetEnforcer();
    capture =
        new ImprovementOutcomeEventCapture(
            new ImprovementOutcomeRecorder(eventLogRepo),
            new ImprovementSignalProjector(signalRegistry),
            new ImprovementCbrProjector(),
            budgetEnforcer);
    caseId = UUID.randomUUID();
  }

  @Test
  void captureRecordsAllThreeLayers() {
    var improvementCaseId = UUID.randomUUID();
    budgetEnforcer.recordStart(
        improvementCaseId,
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            java.util.List.of("pom.xml"),
            20,
            java.util.Map.of()));

    var outcome =
        new ImprovementOutcome(
            caseId,
            improvementCaseId,
            "dependency-update",
            "hibernate-core",
            ImprovementOutcome.OutcomeStatus.MERGED,
            "https://pr/1",
            0,
            1.5,
            -2,
            Instant.now(),
            Map.of());
    var event = new ImprovementCaseCompleted(caseId, "tenant-1", outcome);

    capture.onImprovementComplete(event);

    var entries =
        eventLogRepo.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.IMPROVEMENT_OUTCOME), "tenant-1");
    assertThat(entries).hasSize(1);

    var signals = signalRegistry.getAllSignals(caseId);
    assertThat(signals).containsKey("improvement:outcome:positive:pr-merged");

    assertThat(budgetEnforcer.activeCount()).isEqualTo(0);
  }

  static class RecordingEventLogRepository implements EventLogRepository {
    final List<EventLog> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(EventLog eventLog, String tenancyId) {
      eventLog.tenancyId = tenancyId;
      entries.add(eventLog);
    }

    @Override
    public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
      append(eventLog, tenancyId);
      return 1L;
    }

    @Override
    public EventLog findById(Long id, String tenancyId) {
      return null;
    }

    @Override
    public List<EventLog> findSchedulingEvents(
        UUID caseId, String workerId, Instant after, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseAndTypes(
        UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
      return entries.stream()
          .filter(
              e ->
                  e.getCaseId().equals(caseId)
                      && types.contains(e.getEventType())
                      && tenancyId.equals(e.tenancyId))
          .toList();
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByWorkerAndType(
        String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseWithFilters(
        UUID caseId,
        Collection<CaseHubEventType> eventTypes,
        Collection<EventStreamType> streamTypes,
        String tenancyId) {
      return List.of();
    }
  }
}
