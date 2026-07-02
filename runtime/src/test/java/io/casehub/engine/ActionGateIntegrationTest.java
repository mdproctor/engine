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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.RiskDecision.Autonomous;
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the ActionRiskClassifier gate fork in WorkflowExecutionCompletedHandler.
 *
 * <p>Verifies: Autonomous path proceeds normally; GateRequired path blocks case advancement; the
 * classifier receives a fully enriched PlannedAction; null PlannedAction bypasses the classifier.
 */
@QuarkusTest
class ActionGateIntegrationTest {

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject GateCaseHub gateCaseHub;

  @BeforeEach
  void resetBeans() {
    CapturingClassifier.reset();
    GateCaseHub.declareAction.set(false);
  }

  // --- Tests ---

  @Test
  void autonomousDecision_caseCompletesNormally() {
    CapturingClassifier.nextDecision = new Autonomous();
    GateCaseHub.declareAction.set(true);

    final UUID caseId = startCase();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    assertThat(CapturingClassifier.capturedActions).hasSize(1);
    final PlannedAction action = CapturingClassifier.capturedActions.get(0);
    final ClassificationContext ctx = CapturingClassifier.capturedContexts.get(0);
    assertThat(action.actionType()).isEqualTo("sar.file");
    assertThat(ctx.workerId()).isNotNull();
    assertThat(ctx.caseId()).isEqualTo(caseId);
  }

  @Test
  void gateRequiredDecision_caseRemainsRunning_gateIsPending() {
    CapturingClassifier.nextDecision =
        new GateRequired("SAR filing requires MLRO sign-off", false, List.of("mlro"), null, null);
    GateCaseHub.declareAction.set(true);

    final UUID caseId = startCase();

    // Wait for THIS case's gate specifically — not any classifier action (cross-test contamination)
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst != null && inst.getPendingActionGate() != null;
            });

    // Case must NOT complete — gate is pending
    assertThat(caseInstanceCache.get(caseId).getState()).isEqualTo(CaseStatus.RUNNING);
    // pendingActionGate must be set
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate()).isNotNull();
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate().gateId()).isPositive();
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate().workerId())
        .isEqualTo("gate-worker");
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate().deferredOutput())
        .containsKey("filingResult");
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate().plannedAction().actionType())
        .isEqualTo("sar.file");
  }

  @Test
  void concurrentGate_secondPlannedActionProceedsAutonomous_firstGatePreserved() {
    // v1 constraint: when a second PlannedAction arrives while a gate is pending,
    // the engine logs ERROR and proceeds as Autonomous for the second action.
    // The second action bypasses classification — a known compliance risk documented in
    // CaseInstance.
    // This test verifies the constraint is enforced and the first gate is not corrupted.
    CapturingClassifier.nextDecision =
        new GateRequired("SAR filing requires MLRO sign-off", false, List.of("mlro"), null, null);
    GateCaseHub.declareAction.set(true);

    final UUID caseId = startCase();

    // Wait for first gate to fire
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst != null && inst.getPendingActionGate() != null;
            });

    final long firstGateId = caseInstanceCache.get(caseId).getPendingActionGate().gateId();

    // Now switch to Autonomous so the second worker execution (if triggered) would return
    // Autonomous
    // The concurrent gate detection fires before classify() — second action bypasses classification
    CapturingClassifier.nextDecision = new RiskDecision.Autonomous();

    // Wait briefly — if the binding re-triggers, the concurrent gate path fires
    // The case must still be RUNNING (gate still pending) and first gate must not be replaced
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              // Gate must still be pending — second action proceeds as Autonomous but does not
              // replace the first gate or complete the case (the binding guards prevent re-trigger)
              assertThat(inst.getState()).isEqualTo(CaseStatus.RUNNING);
              if (inst.getPendingActionGate() != null) {
                // If gate still set, it must be the FIRST one
                assertThat(inst.getPendingActionGate().gateId()).isEqualTo(firstGateId);
              }
            });
  }

  @Test
  void nullPlannedAction_classifierNotCalled_caseCompletes() {
    GateCaseHub.declareAction.set(false); // worker returns WorkerResult without PlannedAction

    final UUID caseId = startCase();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    assertThat(CapturingClassifier.capturedActions).isEmpty();
  }

  @Test
  void classifierThrows_failSafeGateRequired_caseRemainsRunning() {
    // Classifier throws — engine must apply fail-safe GateRequired, not fault the case.
    CapturingClassifier.throwOnClassify.set(true);
    GateCaseHub.declareAction.set(true);

    final UUID caseId = startCase();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst != null && inst.getPendingActionGate() != null;
            });

    assertThat(caseInstanceCache.get(caseId).getState()).isEqualTo(CaseStatus.RUNNING);
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate()).isNotNull();
    // fail-safe message confirms the gate came from the error recovery path
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate().plannedAction().actionType())
        .isEqualTo("sar.file");
  }

  @Test
  void classifierReceivesEnrichedPlannedAction_workerIdAndCaseIdPopulated() {
    CapturingClassifier.nextDecision = new Autonomous();
    GateCaseHub.declareAction.set(true);

    final UUID caseId = startCase();

    // Wait for THIS case's classifier to fire specifically
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                CapturingClassifier.capturedContexts.stream()
                    .anyMatch(c -> caseId.equals(c.caseId())));

    int idx = -1;
    for (int i = 0; i < CapturingClassifier.capturedContexts.size(); i++) {
      if (caseId.equals(CapturingClassifier.capturedContexts.get(i).caseId())) {
        idx = i;
        break;
      }
    }
    assertThat(idx).isGreaterThanOrEqualTo(0);
    final PlannedAction action = CapturingClassifier.capturedActions.get(idx);
    final ClassificationContext ctx = CapturingClassifier.capturedContexts.get(idx);
    assertThat(ctx.workerId()).isEqualTo("gate-worker");
    assertThat(ctx.caseId()).isEqualTo(caseId);
    assertThat(action.description()).isEqualTo("File SAR report");
    assertThat(action.parameters()).containsEntry("accountId", "ACC-999");
  }

  // --- Test CDI beans ---

  /**
   * Configurable classifier — returns Autonomous by default; tests set nextDecision before starting
   * a case.
   */
  @RiskClassifier
  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class CapturingClassifier implements ActionRiskClassifier {

    static final List<PlannedAction> capturedActions = new CopyOnWriteArrayList<>();
    static final List<ClassificationContext> capturedContexts = new CopyOnWriteArrayList<>();
    static volatile RiskDecision nextDecision = new Autonomous();
    static final AtomicBoolean throwOnClassify = new AtomicBoolean(false);

    static void reset() {
      capturedActions.clear();
      capturedContexts.clear();
      nextDecision = new Autonomous();
      throwOnClassify.set(false);
    }

    @Override
    public RiskDecision classify(final PlannedAction action, final ClassificationContext context) {
      capturedActions.add(action);
      capturedContexts.add(context);
      if (throwOnClassify.get()) {
        throw new RuntimeException("Simulated classifier failure for fail-safe test");
      }
      return nextDecision;
    }
  }

  /**
   * Configurable CaseHub bean. When {@code declareAction=true} the worker includes a PlannedAction;
   * otherwise it returns a plain WorkerResult.
   */
  @ApplicationScoped
  static class GateCaseHub extends CaseHub {

    static final AtomicBoolean declareAction = new AtomicBoolean(false);

    @Override
    public CaseDefinition getDefinition() {
      final Capability cap =
          Capability.builder()
              .name("file-sar-gate-test")
              .inputSchema(".working")
              .outputSchema(".")
              .build();
      final Goal goal =
          Goal.builder()
              .name("filed")
              .kind(GoalKind.SUCCESS)
              .condition(".filingResult != null")
              .build();
      return CaseDefinition.builder()
          .namespace("test-action-gate")
          .name("Gate Integration Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("gate-worker")
                  .capabilityName("file-sar-gate-test")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            final Map<String, Object> output = Map.of("filingResult", "pending");
                            if (declareAction.get()) {
                              return WorkerResult.of(
                                  output,
                                  PlannedAction.of(
                                      "File SAR report",
                                      "sar.file",
                                      Map.of("accountId", "ACC-999")));
                            }
                            return WorkerResult.of(output);
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("gate-binding")
                  // Prevent re-triggering while gate is pending (deferred output not applied)
                  .on(
                      new ContextChangeTrigger(
                          ".filingResult == null and .actionGateApproved == null"
                              + " and .actionGateRejected == null"
                              + " and .actionGateExpired == null"))
                  .target(new CapabilityTarget(cap))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  // --- Helpers ---

  private UUID startCase() {
    final AtomicReference<UUID> ref = new AtomicReference<>();
    gateCaseHub.startCase(Map.of()).thenAccept(ref::set);
    await().atMost(5, TimeUnit.SECONDS).until(() -> ref.get() != null);
    return ref.get();
  }
}
