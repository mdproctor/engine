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
package io.casehub.engine.rest.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.plan.JoinType;
import io.casehub.engine.plan.execution.AgendaItemSnapshot;
import io.casehub.engine.plan.execution.CasePlanModelSnapshot;
import io.casehub.engine.plan.execution.DagResultSnapshot;
import io.casehub.engine.plan.execution.NodeStateSnapshot;
import io.casehub.engine.plan.snapshot.DagNodeSnapshot;
import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionStateSnapshotTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-17T10:00:00Z");

  // --- state derivation ---

  @Test
  void composeWithPlanModelOnly_stateIsRunning() {
    var planModel =
        planModelWith(agenda("p1", "worker-a", "RUNNING"), agenda("p2", "worker-b", "PENDING"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);

    assertEquals(CASE_ID.toString(), snapshot.executionId());
    assertEquals("RUNNING", snapshot.state());
    assertNull(snapshot.result());
    assertEquals(2, snapshot.activeAgents().size());
    assertTrue(snapshot.completedAgents().isEmpty());
    assertEquals(NOW, snapshot.startedAt());
    assertNull(snapshot.completedAt());
  }

  @Test
  void composeWithSuccessfulDagResult_stateIsComplete() {
    var planModel = planModelWith(agenda("p1", "worker-a", "COMPLETED"));
    var dagResult =
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Completed", null)),
            Map.of(),
            true,
            Duration.ofSeconds(5),
            NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, dagResult);

    assertEquals("COMPLETE", snapshot.state());
    assertEquals("COMPLETED", snapshot.result());
    assertEquals(NOW, snapshot.completedAt());
  }

  @Test
  void composeWithFailedDagResult_stateIsFaulted() {
    var planModel = planModelWith(agenda("p1", "worker-a", "FAULTED"));
    var dagResult =
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Failed", "timeout exceeded")),
            Map.of(),
            false,
            Duration.ofSeconds(30),
            NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, dagResult);

    assertEquals("FAULTED", snapshot.state());
    assertEquals("FAILED", snapshot.result());
  }

  @Test
  void composeWithEmptyPlanModel_stateIsIdle() {
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModelWith(), null, null);
    assertEquals("IDLE", snapshot.state());
  }

  @Test
  void deriveState_cancelledWhenAllNodesCancelled() {
    var dagResult =
        new DagResultSnapshot(
            Map.of(
                "n1", new NodeStateSnapshot("Cancelled", null),
                "n2", new NodeStateSnapshot("Cancelled", null)),
            Map.of(),
            false,
            Duration.ZERO,
            NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, null, null, dagResult);

    assertEquals("CANCELLED", snapshot.state());
    assertEquals("CANCELLED", snapshot.result());
  }

  @Test
  void deriveState_completeNotCancelledWhenEmptyNodeStates() {
    var dagResult = new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ZERO, NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, null, null, dagResult);
    assertEquals("COMPLETE", snapshot.state());
    assertEquals("COMPLETED", snapshot.result());
  }

  @Test
  void deriveState_runningWhenAllAgendaItemsTerminal() {
    var planModel =
        planModelWith(agenda("p1", "worker-a", "COMPLETED"), agenda("p2", "worker-b", "FAULTED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("RUNNING", snapshot.state());
  }

  @Test
  void deriveState_waitingForAgentWhenAllDelegated() {
    var planModel =
        planModelWith(agenda("p1", "worker-a", "DELEGATED"), agenda("p2", "worker-b", "DELEGATED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("WAITING_FOR_AGENT", snapshot.state());
  }

  @Test
  void deriveState_waitingForEventWhenAllSuspended() {
    var planModel =
        planModelWith(agenda("p1", "worker-a", "SUSPENDED"), agenda("p2", "worker-b", "COMPLETED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("WAITING_FOR_EVENT", snapshot.state());
  }

  @Test
  void deriveState_runningWhenMixedActiveStatuses() {
    var planModel =
        planModelWith(agenda("p1", "worker-a", "RUNNING"), agenda("p2", "worker-b", "DELEGATED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("RUNNING", snapshot.state());
  }

  // --- agent split ---

  @Test
  void composeWithMixedAgenda_splitsActiveAndCompleted() {
    var planModel =
        planModelWith(
            agenda("p1", "worker-a", "RUNNING"),
            agenda("p2", "worker-b", "COMPLETED"),
            agenda("p3", "worker-c", "PENDING"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);

    assertEquals(2, snapshot.activeAgents().size());
    assertEquals(1, snapshot.completedAgents().size());
    assertEquals("p2", snapshot.completedAgents().get(0).agentRef().id());
    assertEquals("SUCCESS", snapshot.completedAgents().get(0).status());
  }

  @Test
  void composeWithDagResultOnly_fallsBackToNodeStates() {
    var nodes =
        Map.of(
            "n1",
            new DagNodeSnapshot(
                "n1", "t1", "Do analysis", "analyst-worker", Set.of(), JoinType.ALL_OF));
    var dagPlan = new DagPlanSnapshot(nodes, NOW);
    var dagResult =
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Completed", null)),
            Map.of(),
            true,
            Duration.ofSeconds(10),
            NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, null, dagPlan, dagResult);

    assertEquals("COMPLETE", snapshot.state());
    assertEquals(1, snapshot.completedAgents().size());
    assertEquals("analyst-worker", snapshot.completedAgents().get(0).agentRef().name());
    assertEquals("SUCCESS", snapshot.completedAgents().get(0).status());
    assertTrue(snapshot.activeAgents().isEmpty());
  }

  // --- target type (#913) ---

  @Test
  void activeAgent_usesTargetTypeFromAgenda() {
    var planModel =
        planModelWith(new AgendaItemSnapshot("p1", "review", "RUNNING", "review task", "HUMAN"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("HUMAN", snapshot.activeAgents().get(0).type());
  }

  @Test
  void completedAgent_usesTargetTypeFromAgenda() {
    var planModel =
        planModelWith(new AgendaItemSnapshot("p1", "review", "COMPLETED", "review task", "HUMAN"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("HUMAN", snapshot.completedAgents().get(0).agentRef().type());
  }

  @Test
  void agentRefType_defaultsToWorkerWhenNull() {
    var planModel = planModelWith(agenda("p1", "w1", "RUNNING"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals("WORKER", snapshot.activeAgents().get(0).type());
  }

  // --- TIMEOUT detection (#914) ---

  @Test
  void mapTaskStatus_faultedWithTimeoutReasonToTimeout() {
    assertEquals(
        "TIMEOUT", ExecutionStateSnapshot.mapTaskStatusToResult("FAULTED", "timeout exceeded"));
  }

  @Test
  void mapTaskStatus_faultedWithExpiredReasonToTimeout() {
    assertEquals(
        "TIMEOUT",
        ExecutionStateSnapshot.mapTaskStatusToResult("FAULTED", "Worker expired after 60s"));
  }

  @Test
  void mapTaskStatus_faultedWithTimedOutReasonToTimeout() {
    assertEquals(
        "TIMEOUT", ExecutionStateSnapshot.mapTaskStatusToResult("FAULTED", "Task timed out"));
  }

  @Test
  void mapTaskStatus_faultedWithNonTimeoutReasonToFailure() {
    assertEquals(
        "FAILURE", ExecutionStateSnapshot.mapTaskStatusToResult("FAULTED", "connection refused"));
  }

  @Test
  void mapTaskStatus_faultedWithNullReasonToFailure() {
    assertEquals("FAILURE", ExecutionStateSnapshot.mapTaskStatusToResult("FAULTED", null));
  }

  @Test
  void mapTaskStatus_completedToSuccess() {
    assertEquals("SUCCESS", ExecutionStateSnapshot.mapTaskStatusToResult("COMPLETED", null));
  }

  @Test
  void mapTaskStatus_rejectedToDeclined() {
    assertEquals("DECLINED", ExecutionStateSnapshot.mapTaskStatusToResult("REJECTED", null));
  }

  // --- per-agent duration (#915) ---

  @Test
  void completedAgent_includesDurationFromDagResult() {
    var dagPlan =
        new DagPlanSnapshot(
            Map.of(
                "n1", new DagNodeSnapshot("n1", "p1", "task", "worker", Set.of(), JoinType.ALL_OF)),
            NOW);
    var dagResult =
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Completed", null)),
            Map.of(),
            true,
            Duration.ofSeconds(5),
            NOW,
            Map.of("n1", 5000L));
    var planModel = planModelWith(agenda("p1", "worker", "COMPLETED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, dagPlan, dagResult);

    assertEquals(5000L, snapshot.completedAgents().get(0).duration());
  }

  @Test
  void completedAgent_durationNullWhenNoDurations() {
    var planModel = planModelWith(agenda("p1", "worker", "COMPLETED"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertNull(snapshot.completedAgents().get(0).duration());
  }

  // --- strategy fields (#916) ---

  @Test
  void model_populatesStrategiesFromCaseDefinition() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("strategy-test")
            .version("1.0.0")
            .agentRouting("cbr")
            .decompositionStrategy("llm")
            .build();
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModelWith(), null, null, definition);

    assertEquals("cbr", snapshot.model().routingStrategy());
    assertEquals("llm", snapshot.model().decompositionStrategy());
  }

  @Test
  void model_nullStrategiesWithoutDefinition() {
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModelWith(), null, null);
    assertNull(snapshot.model().routingStrategy());
    assertNull(snapshot.model().decompositionStrategy());
  }

  // --- error propagation ---

  @Test
  void completedAgent_propagatesErrorFromNodeState() {
    var dagPlan =
        new DagPlanSnapshot(
            Map.of(
                "n1", new DagNodeSnapshot("n1", "p1", "task", "worker", Set.of(), JoinType.ALL_OF)),
            NOW);
    var planModel = planModelWith(agenda("p1", "worker", "FAULTED"));
    var dagResult =
        new DagResultSnapshot(
            Map.of("n1", new NodeStateSnapshot("Failed", "connection refused")),
            Map.of(),
            false,
            Duration.ofSeconds(5),
            NOW);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, dagPlan, dagResult);

    assertEquals("connection refused", snapshot.completedAgents().get(0).error());
  }

  // --- pattern detection ---

  @Test
  void detectPattern_parallelWhenNoNodeDependencies() {
    assertEquals("PARALLEL", ExecutionStateSnapshot.detectPattern(dagPlanWith("n1", "n2", "n3")));
  }

  @Test
  void detectPattern_sequenceWhenLinearChain() {
    var nodes = new LinkedHashMap<String, DagNodeSnapshot>();
    nodes.put("n1", dagNode("n1", Set.of()));
    nodes.put("n2", dagNode("n2", Set.of("n1")));
    nodes.put("n3", dagNode("n3", Set.of("n2")));
    assertEquals("SEQUENCE", ExecutionStateSnapshot.detectPattern(new DagPlanSnapshot(nodes, NOW)));
  }

  @Test
  void detectPattern_htnWhenMultipleDependencies() {
    var nodes = new LinkedHashMap<String, DagNodeSnapshot>();
    nodes.put("n1", dagNode("n1", Set.of()));
    nodes.put("n2", dagNode("n2", Set.of()));
    nodes.put("n3", dagNode("n3", Set.of("n1", "n2")));
    assertEquals("HTN", ExecutionStateSnapshot.detectPattern(new DagPlanSnapshot(nodes, NOW)));
  }

  @Test
  void detectPattern_sequenceWhenNullPlan() {
    assertEquals("SEQUENCE", ExecutionStateSnapshot.detectPattern(null));
  }

  @Test
  void detectPattern_sequenceWhenSingleNode() {
    assertEquals("SEQUENCE", ExecutionStateSnapshot.detectPattern(dagPlanWith("n1")));
  }

  // --- node kind mapping ---

  @Test
  void mapNodeKind_completedToSuccess() {
    assertEquals("SUCCESS", ExecutionStateSnapshot.mapNodeKindToResult("Completed"));
  }

  @Test
  void mapNodeKind_skippedToDeclined() {
    assertEquals("DECLINED", ExecutionStateSnapshot.mapNodeKindToResult("Skipped"));
  }

  @Test
  void mapNodeKind_failedToFailure() {
    assertEquals("FAILURE", ExecutionStateSnapshot.mapNodeKindToResult("Failed"));
  }

  // --- model and timing ---

  @Test
  void modelAlwaysPresent() {
    var snapshot =
        ExecutionStateSnapshot.compose(
            CASE_ID,
            null,
            null,
            new DagResultSnapshot(Map.of(), Map.of(), true, Duration.ZERO, NOW));
    assertNotNull(snapshot.model());
    assertNotNull(snapshot.model().failurePolicy());
    assertEquals("RETRY_BROADER", snapshot.model().failurePolicy().routingFailureAction());
    assertEquals("ESCALATE", snapshot.model().failurePolicy().aggregationFailureAction());
  }

  @Test
  void startedAt_prefersDagPlanTimestamp() {
    var earlier = NOW.minusSeconds(60);
    var planModel = planModelWith(agenda("p1", "w1", "RUNNING"));
    var dagPlan = new DagPlanSnapshot(Map.of("n1", dagNode("n1", Set.of())), earlier);
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, dagPlan, null);
    assertEquals(earlier, snapshot.startedAt());
  }

  @Test
  void startedAt_fallsToPlanModelWhenNoDagPlan() {
    var planModel = planModelWith(agenda("p1", "w1", "RUNNING"));
    var snapshot = ExecutionStateSnapshot.compose(CASE_ID, planModel, null, null);
    assertEquals(NOW, snapshot.startedAt());
  }

  // --- helpers ---

  private static AgendaItemSnapshot agenda(String id, String binding, String status) {
    return new AgendaItemSnapshot(id, binding, status, binding + " task");
  }

  private static CasePlanModelSnapshot planModelWith(AgendaItemSnapshot... items) {
    return new CasePlanModelSnapshot(
        CASE_ID, List.of(items), null, null, Map.of(), List.of(), List.of(), NOW);
  }

  private static DagNodeSnapshot dagNode(String id, Set<String> dependsOn) {
    return new DagNodeSnapshot(id, id, "task-" + id, "executor-" + id, dependsOn, JoinType.ALL_OF);
  }

  private static DagPlanSnapshot dagPlanWith(String... nodeIds) {
    var nodes = new LinkedHashMap<String, DagNodeSnapshot>();
    for (var id : nodeIds) {
      nodes.put(id, dagNode(id, Set.of()));
    }
    return new DagPlanSnapshot(nodes, NOW);
  }
}
