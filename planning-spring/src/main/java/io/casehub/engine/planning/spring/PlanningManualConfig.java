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
package io.casehub.engine.planning.spring;

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
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.routing.GoalAbandonmentEvaluator;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.engine.planning.adaptation.DeeperDecompositionHandler;
import io.casehub.engine.planning.adaptation.DefaultPlanAdaptationEvaluator;
import io.casehub.engine.planning.adaptation.ForwardReplanRevision;
import io.casehub.engine.planning.adaptation.LlmRepairStrategy;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.engine.planning.control.BlackboardPlanConfigurer;
import io.casehub.engine.planning.control.CompoundLifecycleEvaluator;
import io.casehub.engine.planning.control.CompoundStrategyDispatcher;
import io.casehub.engine.planning.control.PlanningStrategy;
import io.casehub.engine.planning.control.PlanningStrategyLoopControl;
import io.casehub.engine.planning.decomposition.DefaultGoalDecomposer;
import io.casehub.engine.planning.decomposition.LlmDecompositionStrategy;
import io.casehub.engine.planning.handler.ActionGateExpiredPlanItemHandler;
import io.casehub.engine.planning.handler.ActionGateRejectedPlanItemHandler;
import io.casehub.engine.planning.handler.CompoundCompletionEvaluator;
import io.casehub.engine.planning.handler.JudgmentPlanItemHandler;
import io.casehub.engine.planning.handler.PlanItemCompletionHandler;
import io.casehub.engine.planning.handler.PlanItemEscalationHandler;
import io.casehub.engine.planning.handler.WorkerOutcomeResolvedHandler;
import io.casehub.engine.planning.handler.WorkerRetryExhaustionHandler;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.planning.subcase.SubCaseCompletionService;
import io.casehub.platform.api.routing.StrategyResolver;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PlanningManualConfig {

  // ── adaptation (Instance<T> → Optional<T>) ──

  @Bean
  public LlmRepairStrategy llmRepairStrategy(Optional<ChatModelProvider> chatModelProvider) {
    return new LlmRepairStrategy(chatModelProvider);
  }

  @Bean
  public ForwardReplanRevision forwardReplanRevision(
      Optional<ChatModelProvider> chatModelProvider) {
    return new ForwardReplanRevision(chatModelProvider);
  }

  @Bean
  public DeeperDecompositionHandler deeperDecompositionHandler(
      StrategyResolver strategyResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      Optional<CbrRetrievalService> cbrRetrievalService) {
    return new DeeperDecompositionHandler(
        strategyResolver,
        caseDefinitionRegistry,
        planItemStore,
        eventLogRepository,
        cbrRetrievalService);
  }

  @Bean
  public DefaultPlanAdaptationEvaluator defaultPlanAdaptationEvaluator(
      BlackboardRegistry registry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      CaseInstanceRepository caseInstanceRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      Optional<CbrRetrievalService> cbrRetrievalService,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      @Value("${casehub.engine.adaptation.max-concurrent:3}") int maxConcurrent,
      @Value("${casehub.engine.decomposition.timeout-ms:30000}") long timeoutMs) {
    return new DefaultPlanAdaptationEvaluator(
        registry,
        planItemStore,
        eventLogRepository,
        caseInstanceRepository,
        caseDefinitionRegistry,
        strategyResolver,
        Optional.empty(),
        cbrRetrievalService,
        compoundCompletionEvaluator,
        maxConcurrent,
        timeoutMs);
  }

  // ── completion (Event<T> → ApplicationEventPublisher) ──

  @Bean
  public PlanItemCompletionApplier planItemCompletionApplier(
      BlackboardRegistry registry,
      CrossTenantCaseInstanceRepository caseInstanceRepository,
      EventDispatcher eventDispatcher,
      JQEvaluator jqEvaluator,
      BridgeResolver bridgeResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      ApplicationEventPublisher publisher) {
    return new PlanItemCompletionApplier(
        registry,
        caseInstanceRepository,
        eventDispatcher,
        jqEvaluator,
        bridgeResolver,
        caseDefinitionRegistry,
        publisher::publishEvent,
        publisher::publishEvent);
  }

  // ── control (Instance<T> → List<T>) ──

  @Bean
  public CompoundStrategyDispatcher compoundStrategyDispatcher(
      List<PlanningStrategy> strategyBeans) {
    var strategies = strategyBeans.stream().collect(Collectors.toMap(PlanningStrategy::id, s -> s));
    return new CompoundStrategyDispatcher(strategies::get);
  }

  @Bean
  public PlanningStrategyLoopControl planningStrategyLoopControl(
      BlackboardRegistry registry,
      CompoundLifecycleEvaluator compoundLifecycleEvaluator,
      CompoundStrategyDispatcher compoundDispatcher,
      List<BlackboardPlanConfigurer> configurers,
      ImplementationRoutingStrategy implementationRoutingStrategy,
      ExpressionEngineRegistry expressionEngineRegistry) {
    return new PlanningStrategyLoopControl(
        registry,
        compoundLifecycleEvaluator,
        compoundDispatcher,
        configurers,
        implementationRoutingStrategy,
        expressionEngineRegistry);
  }

  // ── decomposition (Instance<T> → Optional<T> + @ConfigProperty → @Value) ──

  @Bean
  public LlmDecompositionStrategy llmDecompositionStrategy(
      Optional<ChatModelProvider> chatModelProvider) {
    return new LlmDecompositionStrategy(chatModelProvider);
  }

  @Bean
  public DefaultGoalDecomposer defaultGoalDecomposer(
      StrategyResolver strategyResolver,
      GoalAbandonmentEvaluator abandonmentEvaluator,
      BlackboardRegistry blackboardRegistry,
      PlanItemStore planItemStore,
      EventLogRepository eventLogRepository,
      Optional<CbrRetrievalService> cbrRetrievalService,
      @Value("${casehub.engine.decomposition.timeout-ms:30000}") long timeoutMs) {
    return new DefaultGoalDecomposer(
        strategyResolver,
        abandonmentEvaluator,
        blackboardRegistry,
        planItemStore,
        eventLogRepository,
        cbrRetrievalService,
        timeoutMs);
  }

  // ── handler (Event<T> + Instance<T> → publisher + Optional<T>) ──

  @Bean
  public ActionGateExpiredPlanItemHandler actionGateExpiredPlanItemHandler(
      BlackboardRegistry registry, ApplicationEventPublisher publisher) {
    return new ActionGateExpiredPlanItemHandler(registry, publisher::publishEvent);
  }

  @Bean
  public ActionGateRejectedPlanItemHandler actionGateRejectedPlanItemHandler(
      BlackboardRegistry registry, ApplicationEventPublisher publisher) {
    return new ActionGateRejectedPlanItemHandler(registry, publisher::publishEvent);
  }

  @Bean
  public PlanItemEscalationHandler planItemEscalationHandler(
      BlackboardRegistry registry, ApplicationEventPublisher publisher) {
    return new PlanItemEscalationHandler(registry, publisher::publishEvent);
  }

  @Bean
  public WorkerRetryExhaustionHandler workerRetryExhaustionHandler(
      BlackboardRegistry registry,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      ApplicationEventPublisher publisher,
      Optional<PlanAdaptationEvaluator> planAdaptationEvaluator) {
    return new WorkerRetryExhaustionHandler(
        registry, compoundCompletionEvaluator, publisher::publishEvent, planAdaptationEvaluator);
  }

  @Bean
  public JudgmentPlanItemHandler judgmentPlanItemHandler(
      BlackboardRegistry registry,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Optional<JudgmentScheduler> judgmentScheduler,
      ApplicationEventPublisher publisher) {
    return new JudgmentPlanItemHandler(
        registry, caseDefinitionRegistry, judgmentScheduler, publisher::publishEvent);
  }

  @Bean
  public PlanItemCompletionHandler planItemCompletionHandler(
      BlackboardRegistry registry,
      EventDispatcher eventDispatcher,
      ApplicationEventPublisher publisher,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      Optional<PlanAdaptationEvaluator> planAdaptationEvaluator,
      QuiescenceTracker quiescenceTracker) {
    return new PlanItemCompletionHandler(
        registry,
        eventDispatcher,
        publisher::publishEvent,
        compoundCompletionEvaluator,
        planAdaptationEvaluator,
        quiescenceTracker);
  }

  @Bean
  public WorkerOutcomeResolvedHandler workerOutcomeResolvedHandler(
      BlackboardRegistry registry,
      CompoundCompletionEvaluator compoundCompletionEvaluator,
      EventDispatcher eventDispatcher,
      ApplicationEventPublisher publisher,
      QuiescenceTracker quiescenceTracker,
      Optional<DeeperDecompositionHandler> deeperDecompositionHandler) {
    return new WorkerOutcomeResolvedHandler(
        registry,
        compoundCompletionEvaluator,
        eventDispatcher,
        publisher::publishEvent,
        quiescenceTracker,
        deeperDecompositionHandler);
  }

  // ── subcase (Event<T> → ApplicationEventPublisher) ──

  @Bean
  public SubCaseCompletionService subCaseCompletionService(
      EventLogRepository eventLogRepository,
      JQEvaluator jqEvaluator,
      CaseInstanceCache caseInstanceCache,
      CaseResumptionService caseResumptionService,
      SubCaseGroupRepository subCaseGroupRepository,
      CaseHubRuntime caseHubRuntime,
      EventDispatcher eventDispatcher,
      BlackboardRegistry registry,
      ApplicationEventPublisher publisher,
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
        publisher::publishEvent,
        caseDefinitionRegistry,
        expressionEngineRegistry);
  }
}
