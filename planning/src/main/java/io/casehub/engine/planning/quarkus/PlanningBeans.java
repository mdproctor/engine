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
package io.casehub.engine.planning.quarkus;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.api.spi.routing.ImplementationRoutingStrategy;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanAdaptationEvaluator;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.PlanItemObsoleteEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.routing.GoalAbandonmentEvaluator;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.engine.planning.adaptation.CostCeilingMetaReasoner;
import io.casehub.engine.planning.adaptation.DeeperDecompositionHandler;
import io.casehub.engine.planning.adaptation.DefaultPlanAdaptationEvaluator;
import io.casehub.engine.planning.adaptation.EveryStepTrigger;
import io.casehub.engine.planning.adaptation.ForwardReplanRevision;
import io.casehub.engine.planning.adaptation.GoapRepairStrategy;
import io.casehub.engine.planning.adaptation.LlmRepairStrategy;
import io.casehub.engine.planning.adaptation.OnFailureTrigger;
import io.casehub.engine.planning.adaptation.ProgressGatedTrigger;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.engine.planning.control.AdaptivePlanningStrategy;
import io.casehub.engine.planning.control.BlackboardPlanConfigurer;
import io.casehub.engine.planning.control.ChoreographyStrategy;
import io.casehub.engine.planning.control.CompoundLifecycleEvaluator;
import io.casehub.engine.planning.control.CompoundStrategyDispatcher;
import io.casehub.engine.planning.control.GoapPlanningStrategy;
import io.casehub.engine.planning.control.PlanningStrategy;
import io.casehub.engine.planning.control.PlanningStrategyLoopControl;
import io.casehub.engine.planning.control.SequentialPlanningStrategy;
import io.casehub.engine.planning.decomposition.DefaultGoalDecomposer;
import io.casehub.engine.planning.decomposition.ExplicitHtnDecompositionStrategy;
import io.casehub.engine.planning.decomposition.GoapDecompositionStrategy;
import io.casehub.engine.planning.decomposition.LlmDecompositionStrategy;
import io.casehub.engine.planning.decomposition.PortfolioDecompositionStrategy;
import io.casehub.engine.planning.handler.ActionGateExpiredPlanItemHandler;
import io.casehub.engine.planning.handler.ActionGateRejectedPlanItemHandler;
import io.casehub.engine.planning.handler.CaseEvictionHandler;
import io.casehub.engine.planning.handler.CompoundCompletionEvaluator;
import io.casehub.engine.planning.handler.JudgmentPlanItemHandler;
import io.casehub.engine.planning.handler.PlanItemCompletionHandler;
import io.casehub.engine.planning.handler.PlanItemEscalationHandler;
import io.casehub.engine.planning.handler.WorkerOutcomeResolvedHandler;
import io.casehub.engine.planning.handler.WorkerRetryExhaustionHandler;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.planning.snapshot.PlanningCasePlanModelSnapshotProvider;
import io.casehub.engine.planning.store.NoOpPlanItemStore;
import io.casehub.engine.planning.subcase.SubCaseCompletionService;
import io.casehub.engine.planning.subcase.SubCaseExecutionHandler;
import io.casehub.engine.planning.subcase.SubCaseGroupLifecycleEvent;
import io.casehub.platform.api.routing.StrategyResolver;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlanningBeans {

  private static final Logger LOG = Logger.getLogger(PlanningBeans.class);

  // ── adaptation ──

  @Produces
  @ApplicationScoped
  EveryStepTrigger everyStepTrigger() {
    return new EveryStepTrigger();
  }

  @Produces
  @ApplicationScoped
  OnFailureTrigger onFailureTrigger() {
    return new OnFailureTrigger();
  }

  @Produces
  @ApplicationScoped
  CostCeilingMetaReasoner costCeilingMetaReasoner() {
    return new CostCeilingMetaReasoner();
  }

  @Produces
  @ApplicationScoped
  ProgressGatedTrigger progressGatedTrigger(EventLogRepository eventLogRepository) {
    return new ProgressGatedTrigger(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  GoapRepairStrategy goapRepairStrategy() {
    return new GoapRepairStrategy();
  }

  @Produces
  @ApplicationScoped
  LlmRepairStrategy llmRepairStrategy(Instance<ChatModelProvider> chatModelProviders) {
    return new LlmRepairStrategy(
        chatModelProviders.isResolvable()
            ? Optional.of(chatModelProviders.get())
            : Optional.empty());
  }

  @Produces
  @ApplicationScoped
  ForwardReplanRevision forwardReplanRevision(Instance<ChatModelProvider> chatModelProviders) {
    return new ForwardReplanRevision(
        chatModelProviders.isResolvable()
            ? Optional.of(chatModelProviders.get())
            : Optional.empty());
  }

  @Produces
  @ApplicationScoped
  DeeperDecompositionHandler deeperDecompositionHandler(
      StrategyResolver strategyResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      Instance<CbrRetrievalService> cbrRetrievalService) {
    return new DeeperDecompositionHandler(
        strategyResolver,
        caseDefinitionRegistry,
        planItemStore,
        eventLogRepository,
        cbrRetrievalService.isResolvable()
            ? Optional.of(cbrRetrievalService.get())
            : Optional.empty());
  }

  @Produces
  @ApplicationScoped
  DefaultPlanAdaptationEvaluator defaultPlanAdaptationEvaluator(
      BlackboardRegistry registry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      CaseInstanceRepository caseInstanceRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      @io.quarkus.arc.All Instance<Object> agentMemoryRetriever,
      Instance<CbrRetrievalService> cbrRetrievalService,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      @ConfigProperty(name = "casehub.engine.adaptation.max-concurrent", defaultValue = "3")
          int maxConcurrent,
      @ConfigProperty(name = "casehub.engine.decomposition.timeout-ms", defaultValue = "30000")
          long timeoutMs) {
    return new DefaultPlanAdaptationEvaluator(
        registry,
        planItemStore,
        eventLogRepository,
        caseInstanceRepository,
        caseDefinitionRegistry,
        strategyResolver,
        Optional.empty(),
        cbrRetrievalService.isResolvable()
            ? Optional.of(cbrRetrievalService.get())
            : Optional.empty(),
        compoundCompletionEvaluator,
        maxConcurrent,
        timeoutMs);
  }

  // ── completion ──

  @Produces
  @ApplicationScoped
  GateCompletionApplier gateCompletionApplier(EventDispatcher eventDispatcher) {
    return new GateCompletionApplier(eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  PlanItemCompletionApplier planItemCompletionApplier(
      BlackboardRegistry registry,
      CrossTenantCaseInstanceRepository caseInstanceRepository,
      EventDispatcher eventDispatcher,
      JQEvaluator jqEvaluator,
      BridgeResolver bridgeResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      Event<PlanItemObsoleteEvent> planItemObsoleteEvents) {
    return new PlanItemCompletionApplier(
        registry,
        caseInstanceRepository,
        eventDispatcher,
        jqEvaluator,
        bridgeResolver,
        caseDefinitionRegistry,
        event -> planItemStateChangedEvents.fireAsync(event),
        event -> planItemObsoleteEvents.fireAsync(event));
  }

  // ── control ──

  @Produces
  @ApplicationScoped
  AdaptivePlanningStrategy adaptivePlanningStrategy() {
    return new AdaptivePlanningStrategy();
  }

  @Produces
  @ApplicationScoped
  ChoreographyStrategy choreographyStrategy() {
    return new ChoreographyStrategy();
  }

  @Produces
  @ApplicationScoped
  SequentialPlanningStrategy sequentialPlanningStrategy() {
    return new SequentialPlanningStrategy();
  }

  @Produces
  @ApplicationScoped
  GoapPlanningStrategy goapPlanningStrategy() {
    return new GoapPlanningStrategy();
  }

  @Produces
  @ApplicationScoped
  CompoundLifecycleEvaluator compoundLifecycleEvaluator(
      ExpressionEngineRegistry expressionEngineRegistry, EventDispatcher eventDispatcher) {
    return new CompoundLifecycleEvaluator(expressionEngineRegistry, eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  CompoundStrategyDispatcher compoundStrategyDispatcher(Instance<PlanningStrategy> strategyBeans) {
    var strategies =
        StreamSupport.stream(strategyBeans.spliterator(), false)
            .collect(Collectors.toMap(PlanningStrategy::id, s -> s));
    return new CompoundStrategyDispatcher(strategies::get);
  }

  @Produces
  @ApplicationScoped
  PlanningStrategyLoopControl planningStrategyLoopControl(
      BlackboardRegistry registry,
      CompoundLifecycleEvaluator compoundLifecycleEvaluator,
      CompoundStrategyDispatcher compoundDispatcher,
      Instance<BlackboardPlanConfigurer> configurers,
      ImplementationRoutingStrategy implementationRoutingStrategy,
      ExpressionEngineRegistry expressionEngineRegistry) {
    return new PlanningStrategyLoopControl(
        registry,
        compoundLifecycleEvaluator,
        compoundDispatcher,
        StreamSupport.stream(configurers.spliterator(), false).toList(),
        implementationRoutingStrategy,
        expressionEngineRegistry);
  }

  // ── decomposition ──

  @Produces
  @ApplicationScoped
  ExplicitHtnDecompositionStrategy explicitHtnDecompositionStrategy() {
    return new ExplicitHtnDecompositionStrategy();
  }

  @Produces
  @ApplicationScoped
  GoapDecompositionStrategy goapDecompositionStrategy() {
    return new GoapDecompositionStrategy();
  }

  @Produces
  @ApplicationScoped
  LlmDecompositionStrategy llmDecompositionStrategy(
      Instance<ChatModelProvider> chatModelProviders) {
    return new LlmDecompositionStrategy(
        chatModelProviders.isResolvable()
            ? Optional.of(chatModelProviders.get())
            : Optional.empty());
  }

  @Produces
  @ApplicationScoped
  PortfolioDecompositionStrategy portfolioDecompositionStrategy(StrategyResolver strategyResolver) {
    return new PortfolioDecompositionStrategy(strategyResolver);
  }

  @Produces
  @ApplicationScoped
  DefaultGoalDecomposer defaultGoalDecomposer(
      StrategyResolver strategyResolver,
      GoalAbandonmentEvaluator abandonmentEvaluator,
      BlackboardRegistry blackboardRegistry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      Instance<CbrRetrievalService> cbrRetrievalService,
      @ConfigProperty(name = "casehub.engine.decomposition.timeout-ms", defaultValue = "30000")
          long timeoutMs) {
    return new DefaultGoalDecomposer(
        strategyResolver,
        abandonmentEvaluator,
        blackboardRegistry,
        planItemStore,
        eventLogRepository,
        cbrRetrievalService.isResolvable()
            ? Optional.of(cbrRetrievalService.get())
            : Optional.empty(),
        timeoutMs);
  }

  // ── handler ──

  @Produces
  @ApplicationScoped
  CaseEvictionHandler caseEvictionHandler(BlackboardRegistry registry) {
    return new CaseEvictionHandler(registry);
  }

  @Produces
  @ApplicationScoped
  CompoundCompletionEvaluator compoundCompletionEvaluator(EventDispatcher eventDispatcher) {
    return new CompoundCompletionEvaluator(eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  ActionGateExpiredPlanItemHandler actionGateExpiredPlanItemHandler(
      BlackboardRegistry registry, Event<PlanItemStateChangedEvent> planItemStateChangedEvents) {
    return new ActionGateExpiredPlanItemHandler(
        registry, event -> planItemStateChangedEvents.fireAsync(event));
  }

  @Produces
  @ApplicationScoped
  ActionGateRejectedPlanItemHandler actionGateRejectedPlanItemHandler(
      BlackboardRegistry registry, Event<PlanItemStateChangedEvent> planItemStateChangedEvents) {
    return new ActionGateRejectedPlanItemHandler(
        registry, event -> planItemStateChangedEvents.fireAsync(event));
  }

  @Produces
  @ApplicationScoped
  PlanItemEscalationHandler planItemEscalationHandler(
      BlackboardRegistry registry, Event<PlanItemStateChangedEvent> planItemStateChangedEvents) {
    return new PlanItemEscalationHandler(
        registry, event -> planItemStateChangedEvents.fireAsync(event));
  }

  @Produces
  @ApplicationScoped
  WorkerRetryExhaustionHandler workerRetryExhaustionHandler(
      BlackboardRegistry registry,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      Instance<PlanAdaptationEvaluator> planAdaptationEvaluator) {
    return new WorkerRetryExhaustionHandler(
        registry,
        compoundCompletionEvaluator,
        event -> planItemStateChangedEvents.fireAsync(event),
        planAdaptationEvaluator.isResolvable()
            ? Optional.of(planAdaptationEvaluator.get())
            : Optional.empty());
  }

  @Produces
  @ApplicationScoped
  JudgmentPlanItemHandler judgmentPlanItemHandler(
      BlackboardRegistry registry,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Instance<JudgmentScheduler> judgmentScheduler,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents) {
    return new JudgmentPlanItemHandler(
        registry,
        caseDefinitionRegistry,
        judgmentScheduler.isResolvable() ? Optional.of(judgmentScheduler.get()) : Optional.empty(),
        event -> planItemStateChangedEvents.fireAsync(event));
  }

  @Produces
  @ApplicationScoped
  PlanItemCompletionHandler planItemCompletionHandler(
      BlackboardRegistry registry,
      EventDispatcher eventDispatcher,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      Instance<PlanAdaptationEvaluator> planAdaptationEvaluator,
      QuiescenceTracker quiescenceTracker) {
    return new PlanItemCompletionHandler(
        registry,
        eventDispatcher,
        event -> planItemStateChangedEvents.fireAsync(event),
        compoundCompletionEvaluator,
        planAdaptationEvaluator.isResolvable()
            ? Optional.of(planAdaptationEvaluator.get())
            : Optional.empty(),
        quiescenceTracker);
  }

  @Produces
  @ApplicationScoped
  WorkerOutcomeResolvedHandler workerOutcomeResolvedHandler(
      BlackboardRegistry registry,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      EventDispatcher eventDispatcher,
      Event<PlanItemStateChangedEvent> planItemStateChangedEvents,
      QuiescenceTracker quiescenceTracker,
      Instance<DeeperDecompositionHandler> deeperDecompositionHandler) {
    return new WorkerOutcomeResolvedHandler(
        registry,
        compoundCompletionEvaluator,
        eventDispatcher,
        event -> planItemStateChangedEvents.fireAsync(event),
        quiescenceTracker,
        deeperDecompositionHandler.isResolvable()
            ? Optional.of(deeperDecompositionHandler.get())
            : Optional.empty());
  }

  // ── registry ──

  @Produces
  @ApplicationScoped
  BlackboardRegistry blackboardRegistry(PlanItemStore planItemStore) {
    return new BlackboardRegistry(planItemStore);
  }

  // ── snapshot ──

  @Produces
  @ApplicationScoped
  PlanningCasePlanModelSnapshotProvider planningCasePlanModelSnapshotProvider(
      BlackboardRegistry registry) {
    return new PlanningCasePlanModelSnapshotProvider(registry);
  }

  // ── store ──

  @Produces
  @DefaultBean
  NoOpPlanItemStore noOpPlanItemStore() {
    return new NoOpPlanItemStore();
  }

  // ── subcase ──

  @Produces
  @ApplicationScoped
  SubCaseCompletionService subCaseCompletionService(
      EventLogRepository eventLogRepository,
      JQEvaluator jqEvaluator,
      CaseInstanceCache caseInstanceCache,
      CaseResumptionService caseResumptionService,
      SubCaseGroupRepository subCaseGroupRepository,
      CaseHubRuntime caseHubRuntime,
      EventDispatcher eventDispatcher,
      BlackboardRegistry registry,
      Event<SubCaseGroupLifecycleEvent> groupLifecycleEvents,
      CaseDefinitionRegistry caseDefinitionRegistry,
      ExpressionEngineRegistry expressionEngineRegistry) {
    return new SubCaseCompletionService(
        eventLogRepository,
        jqEvaluator,
        caseInstanceCache,
        caseResumptionService,
        subCaseGroupRepository,
        caseHubRuntime,
        eventDispatcher,
        registry,
        event -> groupLifecycleEvents.fireAsync(event),
        caseDefinitionRegistry,
        expressionEngineRegistry);
  }

  @Produces
  @ApplicationScoped
  SubCaseExecutionHandler subCaseExecutionHandler(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry caseDefinitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      EventLogRepository eventLogRepository,
      PendingWorkRegistry pendingWorkRegistry,
      SubCaseGroupRepository subCaseGroupRepository,
      BlackboardRegistry registry,
      CaseInstanceCache caseInstanceCache) {
    return new SubCaseExecutionHandler(
        caseHubRuntime,
        caseDefinitionRegistry,
        caseInstanceRepository,
        eventLogRepository,
        pendingWorkRegistry,
        subCaseGroupRepository,
        registry,
        caseInstanceCache);
  }
}
