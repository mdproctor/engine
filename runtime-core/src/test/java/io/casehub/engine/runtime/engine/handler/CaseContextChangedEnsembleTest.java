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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.context.MutableCaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.CandidateMatchingStrategy;
import io.casehub.api.spi.routing.CbrRetrievalResult;
import io.casehub.api.spi.routing.ConsensusScope;
import io.casehub.api.spi.routing.EnsembleConsensus;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.internal.context.WritableLayerImpl;
import io.casehub.engine.internal.routing.AgentCandidateFactory;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.routing.SubsumptionMatchStrategy;
import io.casehub.engine.internal.worker.NoOpVocabularyRegistry;
import io.casehub.platform.api.routing.NamedStrategy;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that EnsembleConsensus from per-step CBR retrieval is written to the working layer. Refs
 * casehubio/engine#1130.
 */
class CaseContextChangedEnsembleTest {

  private io.casehub.api.spi.event.EventDispatcher eventDispatcher;
  private ExpressionEngineRegistry expressionEngineRegistry;
  private LoopControl loopControl;
  private AgentRoutingStrategy agentRoutingStrategy;
  private CbrRetrievalService cbrRetrievalService;
  private io.casehub.engine.internal.engine.CaseEvaluationSerializer evaluationSerializer;
  private io.casehub.api.spi.DispatchBudget dispatchBudget;
  private io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider;
  private io.casehub.engine.common.spi.CaseDefinitionRegistry caseDefinitionRegistry;
  private CapabilityHealth capabilityHealth;
  private io.casehub.engine.common.spi.scheduler.WorkerExecutionManager executionManager;

  CaseContextChangedEventHandler handler;
  private WritableLayerImpl workingLayer;
  private CaseInstance caseInstance;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    eventDispatcher = mock(io.casehub.api.spi.event.EventDispatcher.class);
    caseDefinitionRegistry = mock(io.casehub.engine.common.spi.CaseDefinitionRegistry.class);
    expressionEngineRegistry = mock(ExpressionEngineRegistry.class);
    loopControl = mock(LoopControl.class);
    agentRoutingStrategy = mock(AgentRoutingStrategy.class);
    executionManager = mock(io.casehub.engine.common.spi.scheduler.WorkerExecutionManager.class);
    capabilityHealth = mock(CapabilityHealth.class);
    cbrRetrievalService = mock(CbrRetrievalService.class);
    evaluationSerializer = mock(io.casehub.engine.internal.engine.CaseEvaluationSerializer.class);
    dispatchBudget = mock(io.casehub.api.spi.DispatchBudget.class);
    traceIdProvider = mock(io.casehub.ledger.api.spi.LedgerTraceIdProvider.class);

    var strategyResolver = new TestStrategyResolver();

    handler =
        new CaseContextChangedEventHandler(
            eventDispatcher,
            mock(io.casehub.engine.common.internal.jq.JQEvaluator.class),
            caseDefinitionRegistry,
            expressionEngineRegistry,
            loopControl,
            strategyResolver,
            new AgentCandidateFactory(strategyResolver),
            executionManager,
            capabilityHealth,
            mock(io.casehub.api.spi.WorkerContextProvider.class),
            mock(io.casehub.api.spi.WorkerProvisioner.class),
            mock(java.util.function.Consumer.class),
            traceIdProvider,
            cbrRetrievalService,
            mock(io.casehub.engine.common.internal.context.BridgeResolver.class),
            mock(io.casehub.engine.internal.engine.SignalSettlementTracker.class),
            mock(io.casehub.engine.internal.acl.WorkerGrantOrchestrator.class),
            mock(java.util.concurrent.ExecutorService.class),
            evaluationSerializer,
            mock(io.casehub.engine.internal.engine.QuiescenceTracker.class),
            mock(io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.class),
            mock(io.casehub.engine.internal.routing.SelectionContextStore.class),
            dispatchBudget,
            mock(io.casehub.engine.common.spi.PlanItemStore.class),
            mock(java.util.function.Consumer.class),
            Optional.empty(),
            mock(io.casehub.engine.common.internal.observation.ObservationRegistry.class),
            mock(io.casehub.engine.common.internal.observation.ContextHistoryBuffer.class),
            mock(io.casehub.engine.common.internal.signal.SignalRegistry.class),
            mock(io.casehub.engine.common.internal.observation.RuleRegistry.class),
            mock(io.casehub.engine.common.internal.convergence.ActivityTracker.class),
            mock(io.casehub.engine.internal.convergence.ConvergenceDetector.class),
            mock(io.casehub.engine.internal.convergence.BudgetEnforcer.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class),
            mock(jakarta.enterprise.inject.Instance.class));

    Capability capability =
        Capability.builder().name("analysis").inputSchema(".").outputSchema(".").build();
    Binding binding =
        Binding.builder()
            .name("analyse")
            .on(new ContextChangeTrigger("."))
            .target(new CapabilityTarget(capability))
            .build();
    Worker worker =
        Worker.builder()
            .name("agent-1")
            .capabilityName("analysis")
            .function(
                new WorkerFunction.Sync<>(
                    Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
            .build();

    CaseMetaModel metaModel = mock(CaseMetaModel.class);
    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("ensemble-test")
            .version("1.0")
            .capabilities(capability)
            .workers(worker)
            .bindings(binding)
            .build();

    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);
    when(expressionEngineRegistry.evaluate(any(), any(CaseContext.class))).thenReturn(true);
    when(executionManager.getActiveWorkCount(any())).thenReturn(0);
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityHealth.CapabilityStatus.Ready());
    Mockito.doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(1)).run();
              return null;
            })
        .when(evaluationSerializer)
        .submit(any(), any());
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(RoutingResult.assigned("agent-1", "test"));
    when(loopControl.select(any(), any())).thenReturn(List.of(binding));
    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());
    when(dispatchBudget.availableCapacity(any())).thenReturn(Integer.MAX_VALUE);

    workingLayer = new WritableLayerImpl(ContextLayer.WORKING, Map.of("someKey", "someValue"));
    MutableCaseContext mutableCtx = mock(MutableCaseContext.class);
    when(mutableCtx.layer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(mutableCtx.writableLayer(ContextLayer.WORKING)).thenReturn(workingLayer);
    when(mutableCtx.asJsonNode()).thenReturn(workingLayer.asJsonNode());
    when(mutableCtx.snapshot()).thenReturn(mutableCtx);

    caseInstance = new CaseInstance();
    caseInstance.setUuid(UUID.randomUUID());
    caseInstance.setState(CaseStatus.RUNNING);
    caseInstance.setCaseMetaModel(metaModel);
    caseInstance.setCaseContext(mutableCtx);
    caseInstance.tenancyId = "test-tenant";
  }

  @Test
  @SuppressWarnings("unchecked")
  void ensembleConsensus_surfacedInWorkingLayer_whenCbrReturnsEnsemble() {
    var ensemble =
        new EnsembleConsensus(
            ConsensusScope.STEP_LEVEL, List.of(), 0.85, 3, List.of("case-1", "case-2"));
    when(cbrRetrievalService.retrieve(any(), any()))
        .thenReturn(new CbrRetrievalResult(List.of(), ensemble));

    handler.handle(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

    Object stored = workingLayer.get("cbrEnsemble");
    assertThat(stored)
        .as("cbrEnsemble must be written to working layer — engine#1130")
        .isNotNull()
        .isInstanceOf(Map.class);
    var ensembleMap = (Map<String, Object>) stored;
    assertThat(ensembleMap.get("ensembleConfidence")).isEqualTo(0.85);
    assertThat(ensembleMap.get("inputCount")).isEqualTo(3);
    assertThat(ensembleMap.get("scope")).isEqualTo("STEP_LEVEL");
  }

  @Test
  void noEnsemble_workingLayerUnchanged_whenCbrReturnsNoEnsemble() {
    when(cbrRetrievalService.retrieve(any(), any())).thenReturn(CbrRetrievalResult.empty());

    handler.handle(
        new CaseContextChangedEvent(
            caseInstance, caseInstance.getCaseContext(), ContextLayer.WORKING));

    assertThat(workingLayer.get("cbrEnsemble"))
        .as("cbrEnsemble must not be set when ensemble is null")
        .isNull();
  }

  static class TestStrategyResolver implements StrategyResolver {
    private final SubsumptionMatchStrategy defaultMatching =
        new SubsumptionMatchStrategy(new NoOpVocabularyRegistry());

    @Override
    @SuppressWarnings("unchecked")
    public <T extends NamedStrategy> T resolve(Class<T> type, String id) {
      if (type == CandidateMatchingStrategy.class) return (T) defaultMatching;
      throw new IllegalStateException("No strategy for " + type + " id=" + id);
    }

    @Override
    public <T extends NamedStrategy> Optional<T> find(Class<T> type, String id) {
      return Optional.empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends NamedStrategy> T defaultStrategy(Class<T> type) {
      if (type == CandidateMatchingStrategy.class) return (T) defaultMatching;
      throw new IllegalStateException("No default for " + type);
    }

    @Override
    public <T extends NamedStrategy> List<T> available(Class<T> type) {
      return List.of();
    }
  }
}
