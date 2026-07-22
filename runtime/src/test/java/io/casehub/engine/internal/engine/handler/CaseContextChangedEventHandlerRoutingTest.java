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
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.CandidateMatchingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.OutcomeDisposition;
import io.casehub.engine.common.internal.event.WorkerOutcomeResolvedEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
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
 * Unit tests for the sealed RoutingResult pattern-match in
 * CaseContextChangedEventHandler.publishWorkerSchedule. Exercises all three branches: Selected,
 * Unresolvable, and Escalated.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaseContextChangedEventHandlerRoutingTest {

  @Mock EventBus eventBus;
  @Mock JQEvaluator jqEvaluator;
  @Mock CaseDefinitionRegistry caseDefinitionRegistry;
  @Mock ExpressionEngineRegistry expressionEngineRegistry;
  @Mock LoopControl loopControl;
  @Mock StrategyResolver strategyResolver;
  @Mock AgentRoutingStrategy agentRoutingStrategy;
  @Mock WorkerExecutionManager executionManager;
  @Mock CapabilityHealth capabilityHealth;

  @org.mockito.Spy
  io.casehub.engine.internal.routing.AgentCandidateFactory agentCandidateFactory =
      new io.casehub.engine.internal.routing.AgentCandidateFactory(new TestStrategyResolver());

  @Mock io.casehub.api.spi.WorkerContextProvider workerContextProvider;
  @Mock io.casehub.api.spi.WorkerProvisioner workerProvisioner;

  @Mock
  jakarta.enterprise.event.Event<io.casehub.engine.common.spi.event.CaseLifecycleEvent>
      lifecycleEvents;

  @Mock io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider;

  @Mock io.casehub.engine.internal.routing.CbrRetrievalService cbrRetrievalService;

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
            .capabilityName("research")
            .function(
                new WorkerFunction.Sync<>(
                    java.util.Map.class,
                    java.util.Map.class,
                    (input, scope) -> WorkerResult.of(java.util.Map.of())))
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
    when(strategyResolver.resolve(eq(AgentRoutingStrategy.class), any()))
        .thenReturn(agentRoutingStrategy);

    final CaseContext ctx = mock(CaseContext.class);
    final ReadableLayer workingLayer = mock(ReadableLayer.class);
    when(workingLayer.asJsonNode())
        .thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    caseInstance = new CaseInstance();
    caseInstance.setUuid(UUID.randomUUID());
    caseInstance.setState(CaseStatus.RUNNING);
    caseInstance.setCaseMetaModel(metaModel);
    caseInstance.setCaseContext(ctx);

    when(loopControl.select(any(), any())).thenReturn(List.of(binding));
    when(traceIdProvider.currentTraceId()).thenReturn(java.util.Optional.empty());
    when(cbrRetrievalService.retrieve(any(), any())).thenReturn(List.of());
  }

  @Test
  void routing_assigned_publishesWorkerScheduleEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(RoutingResult.assigned("analyst-worker", "selected by test"));

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any());
  }

  @Test
  void routing_unresolvable_triesToProvision_doesNotScheduleWorker() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(RoutingResult.unresolvable("no candidates available"));
    // tryProvision requires a provisioner that has the capability — no-op provisioner won't trigger
    when(workerProvisioner.getCapabilities()).thenReturn(java.util.Set.of());

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus, never()).publish(eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any());
  }

  @Test
  void routing_escalateToOversight_publishesEscalationEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(
            RoutingResult.escalate(
                "research", EscalationReason.BORDERLINE_STALEMATE, "all candidates borderline"));

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus)
        .publish(
            eq(EventBusAddresses.AGENT_ROUTING_ESCALATION), any(AgentRoutingEscalationEvent.class));
  }

  @Test
  void listenLayer_matching_allowsBindingToFire() {
    // Binding with listenLayer="extracted" fires when changedLayer is "extracted"
    final Capability cap =
        Capability.builder()
            .name("research")
            .inputSchema("{ q: .q }")
            .outputSchema("{ r: .r }")
            .build();
    final ContextChangeTrigger layerTrigger =
        new ContextChangeTrigger(
            new io.casehub.api.model.evaluator.JQExpressionEvaluator("."), "extracted");
    final Binding layerBinding =
        Binding.builder()
            .name("layer-binding")
            .on(layerTrigger)
            .target(new CapabilityTarget(cap))
            .build();
    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilityName("research")
            .function(
                new WorkerFunction.Sync<>(
                    java.util.Map.class,
                    java.util.Map.class,
                    (input, scope) -> WorkerResult.of(java.util.Map.of())))
            .build();

    final io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    final CaseDefinition layerDef =
        CaseDefinition.builder()
            .namespace("test")
            .name("LayerTest")
            .version("1.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(layerBinding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(layerDef);
    when(loopControl.select(any(), any())).thenReturn(List.of(layerBinding));
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(RoutingResult.assigned("analyst-worker", "selected by test"));

    final CaseContext ctx = mock(CaseContext.class);
    final ReadableLayer workingLayer = mock(ReadableLayer.class);
    when(workingLayer.asJsonNode())
        .thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    final CaseInstance inst = new CaseInstance();
    inst.setUuid(UUID.randomUUID());
    inst.setState(CaseStatus.RUNNING);
    inst.setCaseMetaModel(metaModel);
    inst.setCaseContext(ctx);

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(inst, ctx, "extracted"));

    verify(eventBus).publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }

  @Test
  void listenLayer_nonMatching_suppressesBinding() {
    // Binding with listenLayer="extracted" must NOT fire when changedLayer is "working"
    final Capability cap =
        Capability.builder()
            .name("research")
            .inputSchema("{ q: .q }")
            .outputSchema("{ r: .r }")
            .build();
    final ContextChangeTrigger layerTrigger =
        new ContextChangeTrigger(
            new io.casehub.api.model.evaluator.JQExpressionEvaluator("."), "extracted");
    final Binding layerBinding =
        Binding.builder()
            .name("layer-binding")
            .on(layerTrigger)
            .target(new CapabilityTarget(cap))
            .build();
    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilityName("research")
            .function(
                new WorkerFunction.Sync<>(
                    java.util.Map.class,
                    java.util.Map.class,
                    (input, scope) -> WorkerResult.of(java.util.Map.of())))
            .build();

    final io.casehub.engine.common.internal.model.CaseMetaModel metaModel =
        mock(io.casehub.engine.common.internal.model.CaseMetaModel.class);
    final CaseDefinition layerDef =
        CaseDefinition.builder()
            .namespace("test")
            .name("LayerTest")
            .version("1.0")
            .capabilities(cap)
            .workers(worker)
            .bindings(layerBinding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(layerDef);
    // loopControl.select is never reached because the binding is filtered out before it
    when(loopControl.select(any(), any())).thenReturn(List.of());

    final CaseContext ctx = mock(CaseContext.class);
    final ReadableLayer workingLayer = mock(ReadableLayer.class);
    when(workingLayer.asJsonNode())
        .thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(ctx.asJsonNode()).thenReturn(com.fasterxml.jackson.databind.node.NullNode.instance);
    when(ctx.snapshot()).thenReturn(ctx);

    final CaseInstance inst = new CaseInstance();
    inst.setUuid(UUID.randomUUID());
    inst.setState(CaseStatus.RUNNING);
    inst.setCaseMetaModel(metaModel);
    inst.setCaseContext(ctx);

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(inst, ctx, ContextLayer.WORKING));

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
  }

  @Test
  void tryProvision_provisionerHasCapability_firesWorkerStartedLifecycleEvent() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(RoutingResult.unresolvable("no candidates available"));
    when(workerProvisioner.getCapabilities()).thenReturn(java.util.Set.of("research"));
    when(workerContextProvider.buildContext(any(), any(), any(), any()))
        .thenReturn(
            new io.casehub.api.model.WorkerContext(
                "desc",
                null,
                null,
                java.util.List.of(),
                io.casehub.api.context.PropagationContext.createRoot(),
                java.util.Map.of()));
    when(workerProvisioner.provision(any(), any()))
        .thenReturn(io.casehub.api.spi.ProvisionResult.empty());

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

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

  @Test
  void allCandidatesExcluded_publishesWorkerOutcomeResolvedExhausted() {
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode workingNode = mapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode outcomesNode = mapper.createObjectNode();
    com.fasterxml.jackson.databind.node.ObjectNode bindingOutcome = mapper.createObjectNode();
    bindingOutcome.put("status", "DECLINED");
    bindingOutcome.put("attempts", 1);
    com.fasterxml.jackson.databind.node.ArrayNode excludedAgents = mapper.createArrayNode();
    excludedAgents.add("analyst-worker");
    bindingOutcome.set("excludedAgents", excludedAgents);
    outcomesNode.set("research-binding", bindingOutcome);
    workingNode.set("_outcomes", outcomesNode);

    CaseContext ctx = mock(CaseContext.class);
    ReadableLayer workingLayer = mock(ReadableLayer.class);
    when(workingLayer.asJsonNode()).thenReturn(workingNode);
    when(ctx.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(ctx.asJsonNode()).thenReturn(workingNode);
    when(ctx.snapshot()).thenReturn(ctx);

    caseInstance.setCaseContext(ctx);

    handler.onCaseStateContextChangedEventHandler(
        new CaseContextChangedEvent(caseInstance, ctx, ContextLayer.WORKING));

    verify(eventBus, never())
        .publish(eq(EventBusAddresses.WORKER_SCHEDULE), any(WorkerScheduleEvent.class));
    verify(eventBus)
        .publish(
            eq(EventBusAddresses.WORKER_OUTCOME_RESOLVED),
            argThat(
                (WorkerOutcomeResolvedEvent e) ->
                    e.bindingName().equals("research-binding")
                        && e.capabilityName().equals("research")
                        && e.disposition() == OutcomeDisposition.EXHAUSTED));
  }

  /**
   * Minimal StrategyResolver for constructing AgentCandidateFactory in unit tests. Returns the
   * default SubsumptionMatchStrategy with NoOpVocabularyRegistry.
   */
  static class TestStrategyResolver implements StrategyResolver {
    private final io.casehub.engine.internal.routing.SubsumptionMatchStrategy defaultMatching =
        new io.casehub.engine.internal.routing.SubsumptionMatchStrategy(
            new io.casehub.engine.internal.worker.NoOpVocabularyRegistry());

    @Override
    @SuppressWarnings("unchecked")
    public <T extends io.casehub.platform.api.routing.NamedStrategy> T resolve(
        Class<T> type, String id) {
      if (type == CandidateMatchingStrategy.class) {
        return (T) defaultMatching;
      }
      throw new IllegalStateException("No strategy for type " + type + " with id " + id);
    }

    @Override
    public <T extends io.casehub.platform.api.routing.NamedStrategy> java.util.Optional<T> find(
        Class<T> type, String id) {
      return java.util.Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends io.casehub.platform.api.routing.NamedStrategy> T defaultStrategy(
        Class<T> type) {
      if (type == CandidateMatchingStrategy.class) {
        return (T) defaultMatching;
      }
      throw new IllegalStateException("No default strategy for type " + type);
    }

    @Override
    public <T extends io.casehub.platform.api.routing.NamedStrategy> java.util.List<T> available(
        Class<T> type) {
      return java.util.List.of();
    }
  }
}
