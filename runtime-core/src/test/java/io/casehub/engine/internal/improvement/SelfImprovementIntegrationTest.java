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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.model.stigmergy.ImprovementConfig;
import io.casehub.api.model.stigmergy.ImprovementOutcome;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.internal.improvement.worker.ImprovementIntegrationWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfImprovementIntegrationTest {

  private SignalRegistry signalRegistry;
  private ImprovementSignalContext signalContext;
  private ImprovementBudgetEnforcer budgetEnforcer;
  private ImprovementGoalFormationStrategy goalStrategy;
  private ImprovementOutcomeRecorder outcomeRecorder;
  private ImprovementSignalProjector signalProjector;
  private ImprovementIntegrationWorker integrationWorker;
  private RecordingEventLogRepository eventLogRepo;

  private UUID caseId;
  private String tenancyId;

  @BeforeEach
  void setUp() {
    signalRegistry = new SignalRegistry();
    signalContext = new ImprovementSignalContext();
    budgetEnforcer = new ImprovementBudgetEnforcer();
    goalStrategy =
        new ImprovementGoalFormationStrategy(budgetEnforcer, signalRegistry, signalContext);
    eventLogRepo = new RecordingEventLogRepository();
    outcomeRecorder = new ImprovementOutcomeRecorder(eventLogRepo);
    signalProjector = new ImprovementSignalProjector(signalRegistry);
    integrationWorker = new ImprovementIntegrationWorker(eventLogRepo);
    caseId = UUID.randomUUID();
    tenancyId = "tenant-1";
  }

  @Test
  void fullLifecycle_signalToOutcome() {
    var config = new ImprovementConfig(null, 2, null, null, null);
    String signalName = "improvement:dependency:staleness:major-behind";

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);
    signalContext.register(
        caseId,
        signalName,
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of("pom.xml"),
            20,
            Map.of()));

    var proposal = goalStrategy.proposeImprovements(caseId, config);
    assertThat(proposal).isNotNull();
    assertThat(proposal.goals()).hasSize(1);
    var goal = proposal.goals().get(0);
    assertThat(goal.attributes()).containsEntry("improvement.category", "dependency-update");

    var improvementCaseId = UUID.randomUUID();
    budgetEnforcer.recordStart(
        improvementCaseId,
        new ImprovementRequest(
            "operational",
            "dependency-update",
            "hibernate-core",
            "casehubio/engine",
            List.of("pom.xml"),
            20,
            Map.of()));

    var outcome =
        new ImprovementOutcome(
            caseId,
            improvementCaseId,
            "dependency-update",
            "hibernate-core",
            ImprovementOutcome.OutcomeStatus.MERGED,
            "https://github.com/casehubio/engine/pull/1234",
            0,
            2.5,
            -3,
            Instant.now(),
            Map.of());

    outcomeRecorder.record(caseId, tenancyId, outcome);
    signalProjector.project(caseId, outcome);

    var eventLogs =
        eventLogRepo.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.IMPROVEMENT_OUTCOME), tenancyId);
    assertThat(eventLogs).hasSize(1);
    assertThat(eventLogs.get(0).getPayload().get("category").asText())
        .isEqualTo("dependency-update");
    assertThat(eventLogs.get(0).getPayload().get("status").asText()).isEqualTo("MERGED");

    var signals = signalRegistry.getAllSignals(caseId);
    assertThat(signals).containsKey("improvement:outcome:positive:pr-merged");

    budgetEnforcer.recordCompletion(improvementCaseId);
    assertThat(budgetEnforcer.activeCount()).isEqualTo(0);
  }

  @Test
  void noProposalWithoutRegisteredContext() {
    var config = new ImprovementConfig(null, 2, null, null, null);
    String signalName = "improvement:quality:lint:violation";

    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-1", 100);
    signalRegistry.deposit(caseId, signalName, 1.0, Duration.ofHours(1), "agent-2", 100);

    var proposal = goalStrategy.proposeImprovements(caseId, config);

    assertThat(proposal).isNull();
  }

  @Test
  void reviewGateBlocksWithoutApproval() {
    assertThrows(
        IllegalStateException.class, () -> integrationWorker.verifyReviewGate(caseId, tenancyId));
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
