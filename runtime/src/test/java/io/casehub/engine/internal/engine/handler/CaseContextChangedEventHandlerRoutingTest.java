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
package io.casehub.engine.internal.engine.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.api.spi.ReactiveWorkerContextProvider;
import io.casehub.api.spi.ReactiveWorkerProvisioner;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for the sealed AgentAssignment pattern-match in
 * CaseContextChangedEventHandler.publishWorkerSchedule. Exercises all three branches: Assigned,
 * Unresolvable, and EscalateToOversight.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseContextChangedEventHandlerRoutingTest {

  @Mock EventBus eventBus;
  @Mock JQEvaluator jqEvaluator;
  @Mock CaseDefinitionRegistry caseDefinitionRegistry;
  @Mock ExpressionEngineRegistry expressionEngineRegistry;
  @Mock LoopControl loopControl;
  @Mock AgentRoutingStrategy agentRoutingStrategy;
  @Mock WorkerExecutionManager executionManager;
  @Mock CapabilityHealth capabilityHealth;
  @Mock ReactiveWorkerContextProvider reactiveWorkerContextProvider;
  @Mock ReactiveWorkerProvisioner reactiveWorkerProvisioner;

  @Mock
  jakarta.enterprise.event.Event<io.casehub.engine.common.spi.event.CaseLifecycleEvent>
      lifecycleEvents;

  @Mock io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider;

  @InjectMocks CaseContextChangedEventHandler handler;

  private CaseInstance caseInstance;
  private CaseDefinition definition;

  @BeforeEach
  void setUp() {
    final Capability capability =
        Capability.builder()
            .name("research")
            .inputSchema("{ q: .q }")
            .outputSchema("{ r: .r }")
            .build();
    final ContextChangeTrigger trigger = new ContextChangeTrigger(".");
    final Binding binding =
        Binding.builder()
            .name("research-binding")
            .on(trigger)
            .target(new CapabilityTarget(capability))
            .build();
    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilities(capability)
            .function(input -> WorkerResult.of(java.util.Map.of()))
            .build();

    final CaseMetaModel metaModel = mock(CaseMetaModel.class);
    definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("Test")
            .version("1.0")
            .capabilities(capability)
            .workers(worker)
            .bindings(binding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(expressionEngineRegistry.evaluate(
            any(io.casehub.api.model.evaluator.ExpressionEvaluator.class), any(CaseContext.class)))
        .thenReturn(true);
    when(executionManager.getActiveWorkCount(any())).thenReturn(0);
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityHealth.CapabilityStatus.Ready());

    final CaseContext ctx = mock(CaseContext.class);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    caseInstance = new CaseInstance();
    caseInstance.setUuid(UUID.randomUUID());
    caseInstance.setState(CaseStatus.RUNNING);
    caseInstance.setCaseMetaModel(metaModel);
    caseInstance.setCaseContext(ctx);

    when(loopControl.select(any(), any())).thenReturn(Uni.createFrom().item(List.of(binding)));
    when(traceIdProvider.currentTraceId()).thenReturn(java.util.Optional.empty());
  }

  @Test
  void routing_assigned_publishesWorkerScheduleEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    handler
        .onCaseStateContextChangedEventHandler(
            new CaseContextChangedEvent(
                caseInstance, caseInstance.getCaseContext(), ContextPanel.WORKING))
        .await()
        .indefinitely();

    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any());
  }

  @Test
  void routing_unresolvable_triesToProvision_doesNotScheduleWorker() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.unresolvable()));
    // tryProvision requires a provisioner that has the capability — no-op provisioner won't trigger
    when(reactiveWorkerProvisioner.getCapabilities())
        .thenReturn(Uni.createFrom().item(java.util.Set.of()));

    handler
        .onCaseStateContextChangedEventHandler(
            new CaseContextChangedEvent(
                caseInstance, caseInstance.getCaseContext(), ContextPanel.WORKING))
        .await()
        .indefinitely();

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any());
  }

  @Test
  void routing_escalateToOversight_publishesEscalationEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(
            Uni.createFrom()
                .item(AgentAssignment.escalate("research", EscalationReason.BORDERLINE_STALEMATE)));

    handler
        .onCaseStateContextChangedEventHandler(
            new CaseContextChangedEvent(
                caseInstance, caseInstance.getCaseContext(), ContextPanel.WORKING))
        .await()
        .indefinitely();

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus)
        .publish(
            eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any(AgentRoutingEscalationEvent.class));
  }

  @Test
  void listenPanel_matching_allowsBindingToFire() {
    // Binding with listenPanel="extracted" fires when changedPanel is "extracted"
    final Capability cap =
        Capability.builder()
            .name("research")
            .inputSchema("{ q: .q }")
            .outputSchema("{ r: .r }")
            .build();
    final ContextChangeTrigger panelTrigger =
        new ContextChangeTrigger(
            new io.casehub.api.model.evaluator.JQExpressionEvaluator("."), "extracted");
    final Binding panelBinding =
        Binding.builder()
            .name("panel-binding")
            .on(panelTrigger)
            .target(new CapabilityTarget(cap))
            .build();
    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilities(cap)
            .function(input -> WorkerResult.of(java.util.Map.of()))
            .build();

    final io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    final CaseDefinition panelDef =
        CaseDefinition.builder()
            .namespace("test")
            .name("PanelTest")
            .version("1.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(panelBinding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(panelDef);
    when(loopControl.select(any(), any())).thenReturn(Uni.createFrom().item(List.of(panelBinding)));
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    final CaseContext ctx = mock(CaseContext.class);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    final CaseInstance inst = new CaseInstance();
    inst.setUuid(UUID.randomUUID());
    inst.setState(CaseStatus.RUNNING);
    inst.setCaseMetaModel(metaModel);
    inst.setCaseContext(ctx);

    handler
        .onCaseStateContextChangedEventHandler(new CaseContextChangedEvent(inst, ctx, "extracted"))
        .await()
        .indefinitely();

    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }

  @Test
  void listenPanel_nonMatching_suppressesBinding() {
    // Binding with listenPanel="extracted" must NOT fire when changedPanel is "working"
    final Capability cap =
        Capability.builder()
            .name("research")
            .inputSchema("{ q: .q }")
            .outputSchema("{ r: .r }")
            .build();
    final ContextChangeTrigger panelTrigger =
        new ContextChangeTrigger(
            new io.casehub.api.model.evaluator.JQExpressionEvaluator("."), "extracted");
    final Binding panelBinding =
        Binding.builder()
            .name("panel-binding")
            .on(panelTrigger)
            .target(new CapabilityTarget(cap))
            .build();
    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilities(cap)
            .function(input -> WorkerResult.of(java.util.Map.of()))
            .build();

    final io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    final CaseDefinition panelDef =
        CaseDefinition.builder()
            .namespace("test")
            .name("PanelTest")
            .version("1.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(panelBinding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(panelDef);
    // loopControl.select is never reached because the binding is filtered out before it
    when(loopControl.select(any(), any())).thenReturn(Uni.createFrom().item(List.of()));

    final CaseContext ctx = mock(CaseContext.class);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    final CaseInstance inst = new CaseInstance();
    inst.setUuid(UUID.randomUUID());
    inst.setState(CaseStatus.RUNNING);
    inst.setCaseMetaModel(metaModel);
    inst.setCaseContext(ctx);

    handler
        .onCaseStateContextChangedEventHandler(
            new CaseContextChangedEvent(inst, ctx, ContextPanel.WORKING))
        .await()
        .indefinitely();

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }

  @Test
  void tryProvision_provisionerHasCapability_firesWorkerStartedLifecycleEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.unresolvable()));
    when(reactiveWorkerProvisioner.getCapabilities())
        .thenReturn(Uni.createFrom().item(java.util.Set.of("research")));
    when(reactiveWorkerContextProvider.buildContext(any(), any(), any()))
        .thenReturn(
            Uni.createFrom()
                .item(
                    new io.casehub.api.model.WorkerContext(
                        "desc",
                        null,
                        null,
                        java.util.List.of(),
                        io.casehub.api.context.PropagationContext.createRoot(),
                        java.util.Map.of())));
    when(reactiveWorkerProvisioner.provision(any(), any()))
        .thenReturn(Uni.createFrom().item(io.casehub.api.spi.ProvisionResult.empty()));

    handler
        .onCaseStateContextChangedEventHandler(
            new CaseContextChangedEvent(
                caseInstance, caseInstance.getCaseContext(), ContextPanel.WORKING))
        .await()
        .indefinitely();

    verify(lifecycleEvents)
        .fireAsync(
            argThat(
                e ->
                    caseInstance.getUuid().equals(e.caseId())
                        && "WorkerStarted".equals(e.eventType())
                        && "ProvisionWorker".equals(e.commandType())
                        && "RUNNING".equals(e.caseStatus())
                        && "System".equals(e.actorRole())));
    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }
}
