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
import io.casehub.api.spi.RiskDecision.GateRequired;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import io.casehub.engine.common.internal.event.ActionGateScheduleEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
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
 * Integration tests for gate resolution handlers — ActionGateApprovedHandler,
 * ActionGateRejectedHandler, ActionGateExpiredHandler.
 *
 * <p>The work-adapter module is not on the runtime test classpath, so tests manually publish
 * ActionGate*Event on the Vert.x event bus (simulating what ActionGateCompletionApplier would do
 * after a human approves/rejects the gate WorkItem).
 */
@QuarkusTest
class ActionGateResolutionTest {

  @Inject CaseInstanceCache caseInstanceCache;
  @Inject EventBus eventBus;
  @Inject ResolutionCaseHub resolutionCaseHub;

  @BeforeEach
  void resetBeans() {
    ResolutionClassifier.reset();
    ResolutionCaseHub.declareAction.set(true);
  }

  @Test
  void gateApproved_caseCompletes_deferredOutputApplied() {
    ResolutionClassifier.nextDecision =
        new GateRequired("SAR filing", false, List.of("mlro"), null, null);

    final UUID caseId = startCase();

    // Wait for gate to fire and pendingActionGate to be set
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst != null && inst.getPendingActionGate() != null;
            });

    final long gateId = caseInstanceCache.get(caseId).getPendingActionGate().gateId();

    // Simulate work-adapter publishing ActionGateApprovedEvent after human approves
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_APPROVED,
        new ActionGateApprovedEvent(
            caseId, gateId, "{\"approverNote\": \"approved\"}", "mlro-user"));

    // Case should now complete — approved handler re-fires WorkflowExecutionCompleted
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    // pendingActionGate must be cleared
    assertThat(caseInstanceCache.get(caseId).getPendingActionGate()).isNull();
    // deferred output must be in case context
    assertThat(caseInstanceCache.get(caseId).getCaseContext().get("gateWorkerOutput")).isNotNull();
    // actionGateApproved signal must be in case context
    assertThat(caseInstanceCache.get(caseId).getCaseContext().get("actionGateApproved"))
        .isNotNull();
  }

  @Test
  void gateRejected_caseRemainsRunning_rejectionSignalInContext() {
    ResolutionClassifier.nextDecision =
        new GateRequired("SAR filing", false, List.of("mlro"), null, null);

    final UUID caseId = startCase();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst != null && inst.getPendingActionGate() != null;
            });

    final long gateId = caseInstanceCache.get(caseId).getPendingActionGate().gateId();

    // Simulate rejection from work-adapter
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_REJECTED,
        new ActionGateRejectedEvent(
            caseId, gateId, "{\"reason\": \"insufficient evidence\"}", "mlro-user"));

    // Wait for both: gate cleared AND signal written (gate cleared first per handler ordering)
    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(
            () -> {
              final var inst = caseInstanceCache.get(caseId);
              return inst.getPendingActionGate() == null
                  && inst.getCaseContext().get("actionGateRejected") != null;
            });

    assertThat(caseInstanceCache.get(caseId).getPendingActionGate()).isNull();
    assertThat(caseInstanceCache.get(caseId).getCaseContext().get("actionGateRejected"))
        .isNotNull();
    // Case stays RUNNING (no completion binding for rejection in this test)
    assertThat(caseInstanceCache.get(caseId).getState()).isEqualTo(CaseStatus.RUNNING);
  }

  @Test
  void terminalStateguard_approvedAfterCaseTermination_doesNotCorruptState() {
    ResolutionClassifier.nextDecision = new RiskDecision.Autonomous();

    // Start case with Autonomous — completes normally
    ResolutionCaseHub.declareAction.set(false);
    final UUID caseId = startCase();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));

    // Publishing ActionGateApprovedEvent for a completed case — should be a no-op
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_APPROVED,
        new ActionGateApprovedEvent(caseId, 999L, null, null));

    // Case should remain COMPLETED without errors
    await()
        .pollDelay(500, TimeUnit.MILLISECONDS)
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .isEqualTo(CaseStatus.COMPLETED));
  }

  // --- Supporting CDI beans ---

  @RiskClassifier
  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class ResolutionClassifier implements ActionRiskClassifier {

    static volatile RiskDecision nextDecision = new RiskDecision.Autonomous();

    static void reset() {
      // Default to Autonomous to prevent cross-test contamination when multiple test classes
      // share the same Quarkus instance. Tests that need GateRequired set it explicitly.
      nextDecision = new RiskDecision.Autonomous();
    }

    @Override
    public RiskDecision classify(final PlannedAction action, final ClassificationContext context) {
      return nextDecision;
    }
  }

  @ApplicationScoped
  static class ResolutionCaseHub extends CaseHub {

    static final AtomicBoolean declareAction = new AtomicBoolean(true);

    @Override
    public CaseDefinition getDefinition() {
      final Capability cap =
          Capability.builder()
              .name("resolution-gate-cap")
              .inputSchema(".working")
              .outputSchema(".")
              .build();
      final Goal goal =
          Goal.builder()
              .name("complete")
              .kind(GoalKind.SUCCESS)
              .condition(".gateWorkerOutput != null")
              .build();
      return CaseDefinition.builder()
          .namespace("test-gate-resolution")
          .name("Gate Resolution Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("resolution-worker")
                  .capabilityName("resolution-gate-cap")
                  .function(
                      new WorkerFunction.Sync(
                          input -> {
                            final Map<String, Object> output =
                                Map.of("gateWorkerOutput", "produced");
                            if (declareAction.get()) {
                              return WorkerResult.of(
                                  output,
                                  PlannedAction.of("File resolution", "resolution.file", Map.of()));
                            }
                            return WorkerResult.of(output);
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("resolution-binding")
                  // Only fire when not gated and not completed — prevents re-triggering while
                  // pendingActionGate is set (deferred output not yet applied to context)
                  .on(
                      new ContextChangeTrigger(
                          ".gateWorkerOutput == null and .actionGateRejected == null"
                              + " and .actionGateApproved == null"
                              + " and .actionGateExpired == null"))
                  .target(new CapabilityTarget(cap))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }

  // --- Codec-registering stubs — @ConsumeEvent triggers Quarkus codec registration ---

  /**
   * Registers event bus codecs for gate resolution event types. Without @ConsumeEvent handlers,
   * Quarkus doesn't register codecs for these types and publish() fails.
   *
   * <p>The real handlers (ActionGateApprovedHandler, ActionGateRejectedHandler, etc.) also consume
   * these addresses and register their own codecs. These stubs coexist as additional consumers —
   * Vert.x publish() fan-out means all consumers receive the event.
   */
  @ApplicationScoped
  static class GateCodecStubs {

    static final List<ActionGateApprovedEvent> capturedApproved = new CopyOnWriteArrayList<>();
    static final List<ActionGateRejectedEvent> capturedRejected = new CopyOnWriteArrayList<>();

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_APPROVED)
    void onApproved(final ActionGateApprovedEvent e) {
      capturedApproved.add(e);
    }

    @ConsumeEvent(EventBusAddresses.ACTION_GATE_REJECTED)
    void onRejected(final ActionGateRejectedEvent e) {
      capturedRejected.add(e);
    }

    // ACTION_GATE_SCHEDULE codec — registered by ActionGateWorkItemHandler stub below
    @ConsumeEvent(EventBusAddresses.ACTION_GATE_SCHEDULE)
    void onSchedule(final ActionGateScheduleEvent e) {
      // no-op — just registers codec
    }
  }

  // --- Helpers ---

  private UUID startCase() {
    final AtomicReference<UUID> ref = new AtomicReference<>();
    resolutionCaseHub.startCase(Map.of()).thenAccept(ref::set);
    await().atMost(5, TimeUnit.SECONDS).until(() -> ref.get() != null);
    return ref.get();
  }
}
