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
package io.casehub.engine.runtime.engine.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventLogReplayRecoveryStrategyTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private StubEventLogRepository eventLogRepo;
  private EventLogReplayRecoveryStrategy strategy;

  @BeforeEach
  void setUp() {
    eventLogRepo = new StubEventLogRepository();
    strategy = new EventLogReplayRecoveryStrategy(eventLogRepo);
  }

  @Test
  void recoverFromCaseStartedEvent() {
    UUID caseId = UUID.randomUUID();
    ObjectNode layerDoc = MAPPER.createObjectNode();
    layerDoc.putObject("working").put("status", "active");

    eventLogRepo.events = List.of(eventLog(caseId, CaseHubEventType.CASE_STARTED, layerDoc, null));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    assertEquals("active", ctx.getString("status"));
  }

  @Test
  void recoverAppliesWorkerCompletionContextChanges() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("count", 0);

    ObjectNode metadata = MAPPER.createObjectNode();
    ObjectNode changes = metadata.putObject("contextChanges");
    ObjectNode countChange = changes.putObject("count");
    countChange.put("before", 0);
    countChange.put("after", 5);

    eventLogRepo.events =
        List.of(
            eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null),
            eventLog(caseId, CaseHubEventType.WORKER_EXECUTION_COMPLETED, null, metadata));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    assertEquals(5, ctx.getInt("count"));
  }

  @Test
  void recoverAppliesSubcaseCompletedPayload() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("status", "initial");

    ObjectNode subcasePayload = MAPPER.createObjectNode();
    subcasePayload.put("result", "sub-done");

    eventLogRepo.events =
        List.of(
            eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null),
            eventLog(caseId, CaseHubEventType.SUBCASE_COMPLETED, subcasePayload, null));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    assertEquals("sub-done", ctx.getString("result"));
    assertEquals("initial", ctx.getString("status"));
  }

  @Test
  void recoverReplaysScopedWorkerOutputContextChanges() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("status", "running");

    ObjectNode scopedMeta = MAPPER.createObjectNode();
    ObjectNode scopedChanges = scopedMeta.putObject("contextChanges");
    ObjectNode progressChange = scopedChanges.putObject("progress");
    progressChange.put("before", (String) null);
    progressChange.put("after", "50%");

    EventLog scopedEvent =
        eventLog(caseId, CaseHubEventType.SCOPED_WORKER_OUTPUT, null, scopedMeta);
    scopedEvent.setWorkerId("persistent-worker-1");

    eventLogRepo.events =
        List.of(eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null), scopedEvent);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    assertEquals("running", ctx.getString("status"));
    assertEquals("50%", ctx.getString("progress"));
  }

  @Test
  void recoverReplaysContextSignalAppliedEvent() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("status", "initial");

    ObjectNode signalMeta = MAPPER.createObjectNode();
    signalMeta.put("bindingName", "test-binding");
    ObjectNode contextChanges = signalMeta.putObject("contextChanges");
    contextChanges.putObject("priority").put("after", "high");
    contextChanges.putObject("assignee").put("after", "agent-1");

    eventLogRepo.events =
        List.of(
            eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null),
            eventLog(caseId, CaseHubEventType.CONTEXT_SIGNAL_APPLIED, null, signalMeta));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    assertEquals("initial", ctx.getString("status"));
    assertEquals("high", ctx.getString("priority"));
    assertEquals("agent-1", ctx.getString("assignee"));
  }

  @Test
  void onContextChangedIsNoOp() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());

    strategy.onContextChanged(instance, null);

    assertNull(instance.getContextSnapshot());
  }

  @Test
  void recoverInitializesEpisodicBaseline() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("status", "active");

    eventLogRepo.events =
        List.of(eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    io.casehub.api.context.ReadableLayer episodic =
        ctx.layer(io.casehub.api.context.ContextLayer.EPISODIC);
    org.junit.jupiter.api.Assertions.assertNotNull(episodic.get("workers"));
    org.junit.jupiter.api.Assertions.assertNotNull(episodic.get("milestones"));
    org.junit.jupiter.api.Assertions.assertNotNull(episodic.get("goals"));
  }

  @Test
  void recoverReplaysGoalReachedIntoEpisodicLayer() {
    UUID caseId = UUID.randomUUID();
    ObjectNode startPayload = MAPPER.createObjectNode();
    startPayload.putObject("working").put("status", "active");

    ObjectNode goalMetadata = MAPPER.createObjectNode();
    goalMetadata.put("name", "data-collected");
    goalMetadata.put("kind", "COMPLETION");

    eventLogRepo.events =
        List.of(
            eventLog(caseId, CaseHubEventType.CASE_STARTED, startPayload, null),
            eventLog(caseId, CaseHubEventType.GOAL_REACHED, null, goalMetadata));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);

    CaseContext ctx = strategy.recover(instance);
    io.casehub.api.context.ReadableLayer episodic =
        ctx.layer(io.casehub.api.context.ContextLayer.EPISODIC);
    @SuppressWarnings("unchecked")
    List<String> goals = (List<String>) episodic.get("goals");
    org.junit.jupiter.api.Assertions.assertNotNull(goals);
    org.junit.jupiter.api.Assertions.assertTrue(goals.contains("data-collected"));
  }

  private static final EnumSet<CaseHubEventType> NON_MUTATING_TYPES =
      EnumSet.of(
          CaseHubEventType.CASE_COMPLETED,
          CaseHubEventType.CASE_FAULTED,
          CaseHubEventType.CASE_CANCELLED,
          CaseHubEventType.CASE_STATUS_CHANGED,
          CaseHubEventType.TASK_CREATED,
          CaseHubEventType.TASK_COMPLETED,
          CaseHubEventType.TASK_FAILED,
          CaseHubEventType.TASK_CANCELLED,
          CaseHubEventType.WORKER_SCHEDULED,
          CaseHubEventType.WORKER_EXECUTION_STARTED,
          CaseHubEventType.WORKER_EXECUTION_FAILED,
          CaseHubEventType.WORKER_OUTCOME_DECLINED,
          CaseHubEventType.WORKER_OUTCOME_FAILED,
          CaseHubEventType.WORKER_OUTCOME_EXPIRED,
          CaseHubEventType.WORK_SUBMITTED,
          CaseHubEventType.WORK_COMPLETED,
          CaseHubEventType.MILESTONE_REACHED,
          CaseHubEventType.GOAL_DECOMPOSED,
          CaseHubEventType.PLAN_ADAPTED,
          CaseHubEventType.PLAN_DEEPENED,
          CaseHubEventType.PLAN_CONCEDED,
          CaseHubEventType.CONTINGENCY_ACTIVATED,
          CaseHubEventType.GOAL_REVISED,
          CaseHubEventType.GOAL_FORMED,
          CaseHubEventType.GOAL_PROPOSED,
          CaseHubEventType.GOAL_REMOVED,
          CaseHubEventType.CONSTRAINTS_INFEASIBLE,
          CaseHubEventType.SUBCASE_STARTED,
          CaseHubEventType.WORKFLOW_STEP_DISPATCHED,
          CaseHubEventType.WORKFLOW_STEP_COMPLETED,
          CaseHubEventType.WORKFLOW_STEP_FAILED,
          CaseHubEventType.ACTION_GATE_PENDING,
          CaseHubEventType.ACTION_GATE_APPROVED,
          CaseHubEventType.ACTION_GATE_REJECTED,
          CaseHubEventType.ACTION_GATE_EXPIRED,
          CaseHubEventType.ACTION_GATE_CANCELLED,
          CaseHubEventType.ORCHESTRATION_STARTED,
          CaseHubEventType.ORCHESTRATION_COMPLETED,
          CaseHubEventType.AGENT_ROUTED,
          CaseHubEventType.AGENT_DISPATCHED,
          CaseHubEventType.AGENT_COMPLETED,
          CaseHubEventType.AGENT_FAILED,
          CaseHubEventType.ORCHESTRATION_ESCALATED,
          CaseHubEventType.PATTERN_CHECKPOINT,
          CaseHubEventType.RECOVERY_ESCALATED,
          CaseHubEventType.RECOVERY_REPLAN,
          CaseHubEventType.REACT_CYCLE,
          CaseHubEventType.JUDGMENT_YIELDED,
          CaseHubEventType.JUDGMENT_RESPONDED,
          CaseHubEventType.JUDGMENT_VERIFIED,
          CaseHubEventType.JUDGMENT_ESCALATED,
          CaseHubEventType.OBSERVER_REGISTERED,
          CaseHubEventType.OBSERVATION_DETECTED,
          CaseHubEventType.PHEROMONE_DEPOSITED,
          CaseHubEventType.PHEROMONE_EXPIRED,
          CaseHubEventType.INTEREST_REGISTERED,
          CaseHubEventType.INTEREST_DEREGISTERED,
          CaseHubEventType.RULE_REGISTERED,
          CaseHubEventType.RULE_FIRED,
          CaseHubEventType.BUDGET_EXHAUSTED,
          CaseHubEventType.CONVERGENCE_DETECTED,
          CaseHubEventType.OUTPUT_CONVERGENCE_DETECTED,
          CaseHubEventType.STIGMERGY_CASE_INITIALIZED,
          CaseHubEventType.STIGMERGY_AGENT_JOINED,
          CaseHubEventType.STIGMERGY_AGENT_ACTIVATED,
          CaseHubEventType.STIGMERGY_AGENT_DEPARTED,
          CaseHubEventType.SIGNAL_CONSENSUS_DETECTED,
          CaseHubEventType.COORDINATION_STORM_DETECTED,
          CaseHubEventType.INTEREST_CONVERGENCE_DETECTED,
          CaseHubEventType.SWARM_ROLE_EMERGED,
          CaseHubEventType.SWARM_ROLE_DISSOLVED,
          CaseHubEventType.SWARM_ROLE_SHIFT,
          CaseHubEventType.SWARM_TEAM_FORMED,
          CaseHubEventType.SWARM_TEAM_DISSOLVED,
          CaseHubEventType.SWARM_TEAM_SHIFT,
          CaseHubEventType.SWARM_PROGRESS,
          CaseHubEventType.SWARM_PROVISION_REQUESTED,
          CaseHubEventType.SWARM_PROVISION_COMPLETED,
          CaseHubEventType.SWARM_PROVISION_FAILED,
          CaseHubEventType.SWARM_PROVISION_VETOED,
          CaseHubEventType.SWARM_PROVISION_BUDGET_EXHAUSTED,
          CaseHubEventType.SWARM_AGENT_TERMINATED,
          CaseHubEventType.IMPROVEMENT_GOAL_FORMED,
          CaseHubEventType.IMPROVEMENT_BUDGET_DENIED,
          CaseHubEventType.IMPROVEMENT_OUTCOME,
          CaseHubEventType.CIRCUIT_BREAKER_TRIPPED,
          CaseHubEventType.CIRCUIT_BREAKER_RECOVERING,
          CaseHubEventType.CIRCUIT_BREAKER_RESET,
          CaseHubEventType.CAPABILITY_AREA_CHANGED,
          CaseHubEventType.REGRESSION_DETECTED,
          CaseHubEventType.ROLLBACK_STARTED,
          CaseHubEventType.IMPROVEMENT_CONFLICT_DETECTED,
          CaseHubEventType.COMPLIANCE_LEVEL_CHANGED,
          CaseHubEventType.READINESS_EVALUATED,
          CaseHubEventType.CATEGORY_PAUSED,
          CaseHubEventType.CATEGORY_UNPAUSED,
          CaseHubEventType.DENY_PATTERN_ADDED,
          CaseHubEventType.DENY_PATTERN_REMOVED,
          CaseHubEventType.TICK_EVALUATED,
          CaseHubEventType.TICK_HEARTBEAT,
          CaseHubEventType.GATE_PENDING,
          CaseHubEventType.GATE_RESOLVED,
          CaseHubEventType.WATCH_PATTERN_ADDED,
          CaseHubEventType.WATCH_PATTERN_REMOVED,
          CaseHubEventType.IMPROVEMENT_BLOCKED,
          CaseHubEventType.IMPROVEMENT_UNBLOCKED);

  @Test
  void allEventTypes_eitherReplayedOrExplicitlyNonMutating() {
    EnumSet<CaseHubEventType> accounted =
        EnumSet.copyOf(EventLogReplayRecoveryStrategy.REPLAYED_TYPES);
    accounted.addAll(NON_MUTATING_TYPES);

    EnumSet<CaseHubEventType> unaccounted = EnumSet.complementOf(accounted);

    assertThat(unaccounted)
        .as(
            "New CaseHubEventType values must be added to either "
                + "EventLogReplayRecoveryStrategy.REPLAYED_TYPES (if context-mutating) "
                + "or NON_MUTATING_TYPES in this test (if not). Unaccounted: %s",
            unaccounted)
        .isEmpty();
  }

  private EventLog eventLog(
      UUID caseId,
      CaseHubEventType type,
      com.fasterxml.jackson.databind.JsonNode payload,
      com.fasterxml.jackson.databind.JsonNode metadata) {
    EventLog e = new EventLog();
    e.setCaseId(caseId);
    e.setEventType(type);
    e.setTimestamp(Instant.now());
    e.setPayload(payload);
    e.setMetadata(metadata);
    return e;
  }

  static class StubEventLogRepository implements CrossTenantEventLogRepository {
    List<EventLog> events = List.of();

    @Override
    public List<EventLog> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
      return events.stream()
          .filter(e -> e.getCaseId().equals(caseId) && types.contains(e.getEventType()))
          .toList();
    }

    @Override
    public List<EventLog> findByTypes(Collection<CaseHubEventType> types) {
      return events.stream().filter(e -> types.contains(e.getEventType())).toList();
    }

    @Override
    public List<String> findSubmittedWorkWithoutCompletion() {
      return List.of();
    }

    @Override
    public List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type) {
      return List.of();
    }

    @Override
    public java.util.Optional<EventLog> findById(Long id) {
      return java.util.Optional.empty();
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type) {
      return List.of();
    }
  }
}
