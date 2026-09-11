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
package io.casehub.engine.internal.spring;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.engine.LoopControl;
import io.casehub.api.model.ai.ChatModelProvider;
import io.casehub.api.model.cbr.CbrCaseTypeRegistration;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.api.spi.DataChannelFactory;
import io.casehub.api.spi.DispatchBudget;
import io.casehub.api.spi.FailureClassifier;
import io.casehub.api.spi.StepOutcomeObserver;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.api.spi.WorkerProvisioner;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.api.spi.routing.GoalRemovalService;
import io.casehub.api.spi.routing.RoutingOutcomeRecorder;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.DispositionEvolution;
import io.casehub.eidos.api.DispositionHealth;
import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.GoalEvolution;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.engine.common.internal.channel.DataChannelRegistry;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.spi.ActionGateScheduler;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.Resettable;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy;
import io.casehub.engine.internal.acl.WorkerGrantOrchestrator;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.engine.CaseEvaluationSerializer;
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler;
import io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler;
import io.casehub.engine.internal.engine.handler.CaseContextChangedEventHandler;
import io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler;
import io.casehub.engine.internal.engine.handler.ContextOutputApplier;
import io.casehub.engine.internal.engine.handler.ExpectationValidator;
import io.casehub.engine.internal.engine.handler.GoalReachedEventHandler;
import io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler;
import io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler;
import io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler;
import io.casehub.engine.internal.engine.handler.WorkflowExecutionCompletedHandler;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.engine.internal.memory.AgentExperienceRecorder;
import io.casehub.engine.internal.memory.AgentMemoryRetriever;
import io.casehub.engine.internal.recovery.CaseRecoveryStateRegistry;
import io.casehub.engine.internal.routing.AgentCandidateFactory;
import io.casehub.engine.internal.routing.AgentGoalCompletionMarker;
import io.casehub.engine.internal.routing.BehavioralComplianceRecorder;
import io.casehub.engine.internal.routing.CbrRetrievalService;
import io.casehub.engine.internal.routing.GoalAbandonmentEvaluator;
import io.casehub.engine.internal.routing.GoalFormationEvaluator;
import io.casehub.engine.internal.routing.GoalOutcomeRecorder;
import io.casehub.engine.internal.routing.GoalRevisionEvaluator;
import io.casehub.engine.internal.routing.GoalSignalProvider;
import io.casehub.engine.internal.routing.LlmGoalFormationStrategy;
import io.casehub.engine.internal.routing.LlmGoalRevisionStrategy;
import io.casehub.engine.internal.routing.PersonalitySignalProvider;
import io.casehub.engine.internal.routing.PersonalitySignalRecorder;
import io.casehub.engine.internal.routing.SelectionContextStore;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.engine.internal.work.CaseResumptionService;
import io.casehub.engine.internal.worker.CompositeWorkerExecutionManager;
import io.casehub.engine.internal.worker.DefaultWorkerFunctionProviderRegistry;
import io.casehub.engine.internal.worker.FailureCritiqueService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.PlanAdapter;
import io.casehub.platform.api.routing.StrategyResolver;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class RuntimeManualConfig {

  @Bean
  public SpringEventDispatcher springEventDispatcher(ApplicationEventPublisher publisher) {
    return new SpringEventDispatcher(publisher);
  }

  @Bean
  public ExecutorService runtimeVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  public GoalReachedEventHandler goalReachedEventHandler(
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      ApplicationEventPublisher publisher,
      LedgerTraceIdProvider traceIdProvider) {
    return new GoalReachedEventHandler(
        caseDefinitionRegistry,
        eventDispatcher,
        eventLogRepository,
        event -> publisher.publishEvent(event),
        traceIdProvider);
  }

  @Bean
  public MilestoneCompletedEventHandler milestoneCompletedEventHandler(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      io.casehub.engine.common.spi.scheduler.JobScheduler scheduler,
      ApplicationEventPublisher publisher,
      LedgerTraceIdProvider traceIdProvider) {
    return new MilestoneCompletedEventHandler(
        eventLogRepository,
        eventDispatcher,
        scheduler,
        event -> publisher.publishEvent(event),
        traceIdProvider);
  }

  @Bean
  public MilestoneActivatedEventHandler milestoneActivatedEventHandler(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      io.casehub.engine.common.spi.scheduler.JobScheduler scheduler,
      ApplicationEventPublisher publisher,
      LedgerTraceIdProvider traceIdProvider) {
    return new MilestoneActivatedEventHandler(
        eventLogRepository,
        eventDispatcher,
        scheduler,
        event -> publisher.publishEvent(event),
        traceIdProvider);
  }

  @Bean
  public ActionGateRejectedHandler actionGateRejectedHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      RecoveryCoordinator recoveryCoordinator,
      CaseInstanceRepository caseInstanceRepository,
      Optional<RoutingOutcomeRecorder> outcomeRecorder) {
    return new ActionGateRejectedHandler(
        caseInstanceCache,
        eventLogRepository,
        eventDispatcher,
        workerStatusListener,
        recoveryCoordinator,
        caseInstanceRepository,
        outcomeRecorder);
  }

  @Bean
  public ActionGateExpiredHandler actionGateExpiredHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      CaseInstanceRepository caseInstanceRepository,
      Optional<RoutingOutcomeRecorder> outcomeRecorder) {
    return new ActionGateExpiredHandler(
        caseInstanceCache,
        eventLogRepository,
        eventDispatcher,
        workerStatusListener,
        caseInstanceRepository,
        outcomeRecorder);
  }

  @Bean
  public AgentMemoryRetriever agentMemoryRetriever(Optional<CaseMemoryStore> caseMemoryStore) {
    return new AgentMemoryRetriever(caseMemoryStore);
  }

  @Bean
  public GoalOutcomeRecorder goalOutcomeRecorder(
      Optional<GoalSignalStore> goalSignalStore, CaseDefinitionRegistry caseDefinitionRegistry) {
    return new GoalOutcomeRecorder(goalSignalStore, caseDefinitionRegistry);
  }

  @Bean
  public FailureCritiqueService failureCritiqueService(
      Optional<ChatModelProvider> chatModelProvider) {
    return new FailureCritiqueService(chatModelProvider);
  }

  @Bean
  public PersonalitySignalRecorder personalitySignalRecorder(
      Optional<DispositionSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Optional<DispositionHealth> dispositionHealth,
      Optional<DispositionEvolution> dispositionEvolution) {
    return new PersonalitySignalRecorder(
        signalStore, caseDefinitionRegistry, dispositionHealth, dispositionEvolution);
  }

  @Bean
  public BehavioralComplianceRecorder behavioralComplianceRecorder(
      Optional<BehavioralSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Optional<PlanItemStore> planItemStore,
      VocabularyRegistry vocabularyRegistry) {
    return new BehavioralComplianceRecorder(
        signalStore, caseDefinitionRegistry, planItemStore, vocabularyRegistry);
  }

  @Bean
  public GoalRevisionEvaluator goalRevisionEvaluator(
      Optional<GoalSignalStore> goalSignalStore,
      Optional<GoalEvolution> goalEvolution,
      Optional<AgentRegistry> agentRegistry,
      GoalRemovalService goalRemovalService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @Value("${casehub.goal-revision.enabled:false}") boolean enabled,
      @Value("${casehub.goal-revision.strategy:default}") String strategyId,
      @Value("${casehub.goal-revision.min-outcomes:3}") int minOutcomes,
      @Value("${casehub.goal-revision.importance-threshold:0.3}") double importanceThreshold) {
    return new GoalRevisionEvaluator(
        goalSignalStore,
        goalEvolution,
        agentRegistry,
        goalRemovalService,
        caseDefinitionRegistry,
        strategyResolver,
        eventLogRepository,
        enabled,
        strategyId,
        minOutcomes,
        importanceThreshold);
  }

  @Bean
  public GoalFormationEvaluator goalFormationEvaluator(
      Optional<AgentRegistry> agentRegistry,
      Optional<io.casehub.api.spi.routing.GoalFormationService> goalFormationService,
      Optional<CaseMemoryStore> caseMemoryStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @Value("${casehub.engine.goal.formation.enabled:false}") boolean enabled,
      @Value("${casehub.engine.goal.formation.auto-approve:true}") boolean autoApprove,
      @Value("${casehub.engine.goal.formation.strategy:llm}") String strategyId,
      @Value("${casehub.engine.goal.formation.max-new-per-reflection:2}") int maxNewPerReflection,
      @Value("${casehub.engine.goal.formation.cooldown-minutes:60}") long cooldownMinutes,
      @Value("${casehub.engine.goal.formation.max-memories:20}") int maxMemories) {
    return new GoalFormationEvaluator(
        agentRegistry,
        goalFormationService,
        caseMemoryStore,
        caseDefinitionRegistry,
        strategyResolver,
        eventLogRepository,
        enabled,
        autoApprove,
        strategyId,
        maxNewPerReflection,
        cooldownMinutes,
        maxMemories);
  }

  @Bean
  public AgentExperienceRecorder agentExperienceRecorder(
      Optional<io.casehub.neocortex.memory.experience.ExperienceRecorder> experienceRecorder,
      Optional<io.casehub.neocortex.memory.reflection.ReflectionOrchestrator>
          reflectionOrchestrator,
      CaseDefinitionRegistry caseDefinitionRegistry,
      GoalFormationEvaluator goalFormationEvaluator,
      Optional<CaseMemoryStore> caseMemoryStore,
      Optional<MeterRegistry> meterRegistry,
      @Value("${casehub.reasoning.enabled:true}") boolean reasoningEnabled) {
    return new AgentExperienceRecorder(
        experienceRecorder,
        reflectionOrchestrator,
        caseDefinitionRegistry,
        goalFormationEvaluator,
        caseMemoryStore,
        meterRegistry,
        reasoningEnabled);
  }

  @Bean
  public CbrRetrievalService cbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrCaseMemoryStore cbrStore,
      PlanAdapter planAdapter,
      List<CbrCaseTypeRegistration> registrations) {
    return new CbrRetrievalService(jqEvaluator, cbrStore, planAdapter, registrations);
  }

  @Bean
  public CaseStatusChangedHandler caseStatusChangedHandler(
      EventDispatcher eventDispatcher,
      CaseInstanceRepository caseInstanceRepository,
      SchedulerService schedulerService,
      ApplicationEventPublisher publisher,
      CaseChannelProvider caseChannelProvider,
      LedgerTraceIdProvider traceIdProvider,
      List<CaseOutcomeObserver> outcomeObservers,
      CaseCompletionTracker caseCompletionTracker,
      ScopedWorkerRegistry scopedWorkerRegistry,
      ContextOutputApplier contextOutputApplier,
      WorkerGrantOrchestrator workerGrantOrchestrator,
      DataChannelRegistry dataChannelRegistry,
      CaseRecoveryStateRegistry recoveryStateRegistry,
      CompoundLockRegistry compoundLockRegistry) {
    return new CaseStatusChangedHandler(
        eventDispatcher,
        caseInstanceRepository,
        schedulerService,
        event -> publisher.publishEvent(event),
        caseChannelProvider,
        traceIdProvider,
        outcomeObservers,
        caseCompletionTracker,
        scopedWorkerRegistry,
        contextOutputApplier,
        workerGrantOrchestrator,
        dataChannelRegistry,
        recoveryStateRegistry,
        compoundLockRegistry);
  }

  @Bean
  public WorkerScheduleEventHandler workerScheduleEventHandler(
      WorkerExecutionManager workflowExecutionManager,
      io.casehub.api.spi.WorkerExecutionGuard workerExecutionGuard,
      QuiescenceTracker quiescenceTracker,
      WorkerContextProvider workerContextProvider,
      CaseChannelProvider caseChannelProvider,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      ExpressionEngineRegistry expressionEngineRegistry,
      BridgeResolver bridgeResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      AgentMemoryRetriever agentMemoryRetriever,
      @Value("${casehub.idempotency.window:#{null}}") Optional<Duration> idempotencyWindow) {
    return new WorkerScheduleEventHandler(
        workflowExecutionManager,
        workerExecutionGuard,
        quiescenceTracker,
        workerContextProvider,
        caseChannelProvider,
        eventDispatcher,
        eventLogRepository,
        expressionEngineRegistry,
        bridgeResolver,
        caseDefinitionRegistry,
        agentMemoryRetriever,
        idempotencyWindow);
  }

  @Bean
  public WorkflowExecutionCompletedHandler workflowExecutionCompletedHandler(
      EventDispatcher eventDispatcher,
      ApplicationEventPublisher publisher,
      EventLogRepository eventLogRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      CaseResumptionService caseResumptionService,
      WorkerStatusListener workerStatusListener,
      LedgerTraceIdProvider traceIdProvider,
      ActionRiskClassifier actionRiskClassifier,
      CaseInstanceRepository caseInstanceRepository,
      SignalSettlementTracker settlementTracker,
      QuiescenceTracker quiescenceTracker,
      PersonalitySignalRecorder personalitySignalRecorder,
      GoalOutcomeRecorder goalOutcomeRecorder,
      BehavioralComplianceRecorder behavioralComplianceRecorder,
      AgentGoalCompletionMarker agentGoalCompletionMarker,
      AgentExperienceRecorder agentExperienceRecorder,
      GoalRevisionEvaluator goalRevisionEvaluator,
      WorkerGrantOrchestrator workerGrantOrchestrator,
      ContextOutputApplier contextOutputApplier,
      StrategyResolver strategyResolver,
      RecoveryCoordinator recoveryCoordinator,
      FailureClassifier failureClassifier,
      ExpectationValidator expectationValidator,
      FailureCritiqueService failureCritiqueService,
      SelectionContextStore selectionContextStore,
      Optional<RoutingOutcomeRecorder> outcomeRecorder,
      Optional<ActionGateScheduler> actionGateScheduler,
      Optional<StepOutcomeObserver> stepOutcomeObserver) {
    return new WorkflowExecutionCompletedHandler(
        eventDispatcher,
        event -> publisher.publishEvent(event),
        event -> publisher.publishEvent(event),
        eventLogRepository,
        caseDefinitionRegistry,
        caseResumptionService,
        workerStatusListener,
        traceIdProvider,
        actionRiskClassifier,
        caseInstanceRepository,
        settlementTracker,
        quiescenceTracker,
        personalitySignalRecorder,
        goalOutcomeRecorder,
        behavioralComplianceRecorder,
        agentGoalCompletionMarker,
        agentExperienceRecorder,
        goalRevisionEvaluator,
        workerGrantOrchestrator,
        contextOutputApplier,
        strategyResolver,
        recoveryCoordinator,
        failureClassifier,
        expectationValidator,
        failureCritiqueService,
        selectionContextStore,
        outcomeRecorder,
        actionGateScheduler,
        stepOutcomeObserver);
  }

  @Bean
  public CaseContextChangedEventHandler caseContextChangedEventHandler(
      EventDispatcher eventDispatcher,
      JQEvaluator jqEvaluator,
      CaseDefinitionRegistry caseDefinitionRegistry,
      ExpressionEngineRegistry expressionEngineRegistry,
      LoopControl loopControl,
      StrategyResolver strategyResolver,
      AgentCandidateFactory agentCandidateFactory,
      WorkerExecutionManager executionManager,
      CapabilityHealth capabilityHealth,
      WorkerContextProvider workerContextProvider,
      WorkerProvisioner workerProvisioner,
      ApplicationEventPublisher publisher,
      LedgerTraceIdProvider traceIdProvider,
      CbrRetrievalService cbrRetrievalService,
      BridgeResolver bridgeResolver,
      SignalSettlementTracker settlementTracker,
      WorkerGrantOrchestrator workerGrantOrchestrator,
      ExecutorService runtimeVirtualThreadExecutor,
      CaseEvaluationSerializer evaluationSerializer,
      QuiescenceTracker quiescenceTracker,
      ScopedWorkerRegistry scopedWorkerRegistry,
      SelectionContextStore selectionContextStore,
      DispatchBudget dispatchBudget,
      PlanItemStore planItemStore,
      Optional<JudgmentScheduler> judgmentScheduler) {
    return new CaseContextChangedEventHandler(
        eventDispatcher,
        jqEvaluator,
        caseDefinitionRegistry,
        expressionEngineRegistry,
        loopControl,
        strategyResolver,
        agentCandidateFactory,
        executionManager,
        capabilityHealth,
        workerContextProvider,
        workerProvisioner,
        event -> publisher.publishEvent(event),
        traceIdProvider,
        cbrRetrievalService,
        bridgeResolver,
        settlementTracker,
        workerGrantOrchestrator,
        runtimeVirtualThreadExecutor,
        evaluationSerializer,
        quiescenceTracker,
        scopedWorkerRegistry,
        selectionContextStore,
        dispatchBudget,
        planItemStore,
        event -> publisher.publishEvent(event),
        judgmentScheduler);
  }

  @Bean
  public io.casehub.engine.internal.engine.EngineResetService engineResetService(
      List<Resettable> resettables) {
    return new io.casehub.engine.internal.engine.EngineResetService(resettables);
  }

  @Bean
  public DefaultWorkerFunctionProviderRegistry defaultWorkerFunctionProviderRegistry(
      List<WorkerFunctionProvider> providers) {
    return new DefaultWorkerFunctionProviderRegistry(providers);
  }

  @Bean
  public CompositeWorkerExecutionManager compositeWorkerExecutionManager(
      WorkerExecutionRoutingStrategy routingStrategy, List<WorkerExecutionManager> backends) {
    return new CompositeWorkerExecutionManager(routingStrategy, backends);
  }

  @Bean
  public GoalAbandonmentEvaluator goalAbandonmentEvaluator(
      Optional<GoalSignalStore> signalStore,
      @Value("${casehub.engine.goal.abandonment-threshold:5}") int threshold) {
    return new GoalAbandonmentEvaluator(signalStore, threshold);
  }

  @Bean
  public PersonalitySignalProvider personalitySignalProvider(
      Optional<DispositionHealth> dispositionHealth) {
    return new PersonalitySignalProvider(dispositionHealth);
  }

  @Bean
  public GoalSignalProvider goalSignalProvider(Optional<GoalAbandonmentEvaluator> evaluator) {
    return new GoalSignalProvider(evaluator);
  }

  @Bean
  public LlmGoalFormationStrategy llmGoalFormationStrategy(
      Optional<ChatModelProvider> chatModelProvider) {
    return new LlmGoalFormationStrategy(chatModelProvider);
  }

  @Bean
  public LlmGoalRevisionStrategy llmGoalRevisionStrategy(
      Optional<ChatModelProvider> chatModelProvider) {
    return new LlmGoalRevisionStrategy(chatModelProvider);
  }

  @Bean
  public io.casehub.engine.common.internal.executor.WorkerExecutionOrchestrator
      workerExecutionOrchestrator(
          io.casehub.engine.common.internal.executor.WorkerExecutor workerExecutor,
          CaseDefinitionRegistry caseDefinitionRegistry,
          WorkerContextProvider workerContextProvider,
          EventDispatcher eventDispatcher,
          WorkerExecutionRecoveryService recoveryService,
          CrossTenantEventLogRepository crossTenantEventLogRepository,
          EventLogRepository eventLogRepository,
          WorkerExecutionConfig executionConfig,
          BridgeResolver bridgeResolver,
          WorkerStatusListener workerStatusListener,
          ApplicationEventPublisher publisher,
          LedgerTraceIdProvider traceIdProvider) {
    return new io.casehub.engine.common.internal.executor.WorkerExecutionOrchestrator(
        workerExecutor,
        caseDefinitionRegistry,
        workerContextProvider,
        eventDispatcher,
        recoveryService,
        crossTenantEventLogRepository,
        eventLogRepository,
        executionConfig,
        bridgeResolver,
        workerStatusListener,
        e -> publisher.publishEvent(e),
        traceIdProvider);
  }

  @Bean
  public WorkerRuntimeFactory workerRuntimeFactory(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      CaseCompletionTracker caseCompletionTracker,
      DataChannelRegistry channelRegistry,
      DataChannelFactory defaultChannelFactory) {
    return new WorkerRuntimeFactory(
        caseHubRuntime,
        definitionRegistry,
        caseInstanceCache,
        caseCompletionTracker,
        channelRegistry,
        defaultChannelFactory);
  }
}
