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
package io.casehub.engine.runtime.quarkus;

import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.spi.CaseChannelProvider;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerExecutionGuard;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.api.spi.routing.RoutingOutcomeRecorder;
import io.casehub.engine.common.internal.channel.DataChannelRegistry;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.executor.MilestoneSLAOrchestrator;
import io.casehub.engine.common.internal.executor.RetryOrchestrator;
import io.casehub.engine.common.internal.executor.ScheduledTriggerOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.executor.WorkerExecutionOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.runtime.acl.WorkerGrantOrchestrator;
import io.casehub.engine.runtime.acl.WorkerIdentityResolver;
import io.casehub.engine.runtime.engine.CaseCompletionTracker;
import io.casehub.engine.runtime.engine.CaseEvaluationSerializer;
import io.casehub.engine.runtime.engine.QuiescenceTracker;
import io.casehub.engine.runtime.engine.SignalSettlementTracker;
import io.casehub.engine.runtime.engine.handler.ActionGateApprovedHandler;
import io.casehub.engine.runtime.engine.handler.ActionGateExpiredHandler;
import io.casehub.engine.runtime.engine.handler.ActionGateRejectedHandler;
import io.casehub.engine.runtime.engine.handler.AgentRoutingEscalationHandler;
import io.casehub.engine.runtime.engine.handler.CaseStatusChangedHandler;
import io.casehub.engine.runtime.engine.handler.ContextOutputApplier;
import io.casehub.engine.runtime.engine.handler.ContextSignalEventHandler;
import io.casehub.engine.runtime.engine.handler.GoalReachedEventHandler;
import io.casehub.engine.runtime.engine.handler.JudgmentCompletedHandler;
import io.casehub.engine.runtime.engine.handler.JudgmentEscalationHandler;
import io.casehub.engine.runtime.engine.handler.JudgmentExpiredHandler;
import io.casehub.engine.runtime.engine.handler.MilestoneActivatedEventHandler;
import io.casehub.engine.runtime.engine.handler.MilestoneCompletedEventHandler;
import io.casehub.engine.runtime.engine.handler.MilestoneSLAViolatedEventHandler;
import io.casehub.engine.runtime.engine.handler.ScopedWorkerTerminationHandler;
import io.casehub.engine.runtime.engine.handler.WorkerRetriesExhaustedEventHandler;
import io.casehub.engine.runtime.engine.handler.WorkerScheduleEventHandler;
import io.casehub.engine.runtime.memory.AgentMemoryRetriever;
import io.casehub.engine.runtime.milestone.MilestoneLifecycleManager;
import io.casehub.engine.runtime.recovery.CaseRecoveryStateRegistry;
import io.casehub.engine.runtime.routing.DefaultGoalFormationService;
import io.casehub.engine.runtime.routing.DefaultGoalRemovalService;
import io.casehub.engine.runtime.routing.LlmGoalFormationStrategy;
import io.casehub.engine.runtime.routing.LlmGoalRevisionStrategy;
import io.casehub.engine.runtime.routing.SelectionContextStore;
import io.casehub.engine.runtime.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.WorkerAuthorizationPolicy;
import io.casehub.platform.api.acl.WorkerCredentialStore;
import io.casehub.platform.api.routing.StrategyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.stream.StreamSupport;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RuntimeBeans {

  private static final Logger LOG = Logger.getLogger(RuntimeBeans.class);

  @Produces
  @ApplicationScoped
  GoalReachedEventHandler goalReachedEventHandler(
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider) {
    return new GoalReachedEventHandler(
        caseDefinitionRegistry,
        eventDispatcher,
        eventLogRepository,
        event ->
            lifecycleEvents
                .fireAsync(event)
                .whenComplete(
                    (v, t) -> {
                      if (t != null) {
                        LOG.warnf(t, "CaseLifecycleEvent observer failed for GoalReached");
                      }
                    }),
        traceIdProvider);
  }

  @Produces
  @ApplicationScoped
  MilestoneSLAViolatedEventHandler milestoneSLAViolatedEventHandler(
      EventLogRepository eventLogRepository, EventDispatcher eventDispatcher) {
    return new MilestoneSLAViolatedEventHandler(eventLogRepository, eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  ContextSignalEventHandler contextSignalEventHandler(
      EventDispatcher eventDispatcher, EventLogRepository eventLogRepository) {
    return new ContextSignalEventHandler(eventDispatcher, eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  ScopedWorkerTerminationHandler scopedWorkerTerminationHandler(
      ScopedWorkerRegistry scopedWorkerRegistry,
      DataChannelRegistry dataChannelRegistry,
      io.casehub.engine.common.internal.observation.ObservationRegistry observationRegistry,
      io.casehub.engine.common.internal.observation.RuleRegistry ruleRegistry) {
    return new ScopedWorkerTerminationHandler(
        scopedWorkerRegistry, dataChannelRegistry, observationRegistry, ruleRegistry);
  }

  @Produces
  @ApplicationScoped
  JudgmentExpiredHandler judgmentExpiredHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher) {
    return new JudgmentExpiredHandler(caseInstanceCache, eventLogRepository, eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  AgentRoutingEscalationHandler agentRoutingEscalationHandler(CaseChannelProvider channelProvider) {
    return new AgentRoutingEscalationHandler(channelProvider);
  }

  @Produces
  @ApplicationScoped
  SignalSettlementTracker signalSettlementTracker() {
    return new SignalSettlementTracker();
  }

  @Produces
  @ApplicationScoped
  ContextOutputApplier contextOutputApplier(
      CaseDefinitionRegistry caseDefinitionRegistry, ContextDiffStrategy contextDiffStrategy) {
    return new ContextOutputApplier(caseDefinitionRegistry, contextDiffStrategy);
  }

  @Produces
  @ApplicationScoped
  WorkerRetriesExhaustedEventHandler workerRetriesExhaustedEventHandler(
      CaseInstanceCache caseInstanceCache,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      SignalSettlementTracker settlementTracker) {
    return new WorkerRetriesExhaustedEventHandler(
        caseInstanceCache, eventDispatcher, workerStatusListener, settlementTracker);
  }

  @Produces
  @ApplicationScoped
  MilestoneCompletedEventHandler milestoneCompletedEventHandler(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      JobScheduler scheduler,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider) {
    return new MilestoneCompletedEventHandler(
        eventLogRepository,
        eventDispatcher,
        scheduler,
        event ->
            lifecycleEvents
                .fireAsync(event)
                .whenComplete(
                    (v, t) -> {
                      if (t != null) {
                        LOG.warnf(t, "CaseLifecycleEvent observer failed for MilestoneCompleted");
                      }
                    }),
        traceIdProvider);
  }

  @Produces
  @ApplicationScoped
  MilestoneActivatedEventHandler milestoneActivatedEventHandler(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      JobScheduler scheduler,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider) {
    return new MilestoneActivatedEventHandler(
        eventLogRepository,
        eventDispatcher,
        scheduler,
        event ->
            lifecycleEvents
                .fireAsync(event)
                .whenComplete(
                    (v, t) -> {
                      if (t != null) {
                        LOG.warnf(t, "CaseLifecycleEvent observer failed for MilestoneActivated");
                      }
                    }),
        traceIdProvider);
  }

  @Produces
  @ApplicationScoped
  MilestoneLifecycleManager milestoneLifecycleManager(
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      CaseDefinitionRegistry caseDefinitionRegistry,
      ExpressionEngineRegistry expressionEngineRegistry) {
    return new MilestoneLifecycleManager(
        eventLogRepository, eventDispatcher, caseDefinitionRegistry, expressionEngineRegistry);
  }

  @Produces
  @ApplicationScoped
  JudgmentCompletedHandler judgmentCompletedHandler(
      CaseInstanceCache caseInstanceCache,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      StrategyResolver strategyResolver,
      JudgmentNodeExecutor judgmentNodeExecutor) {
    return new JudgmentCompletedHandler(
        caseInstanceCache,
        caseDefinitionRegistry,
        eventLogRepository,
        eventDispatcher,
        strategyResolver,
        judgmentNodeExecutor);
  }

  @Produces
  @ApplicationScoped
  JudgmentEscalationHandler judgmentEscalationHandler(
      EventLogRepository eventLogRepository,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventDispatcher eventDispatcher,
      JudgmentNodeExecutor judgmentNodeExecutor) {
    return new JudgmentEscalationHandler(
        eventLogRepository,
        caseDefinitionRegistry,
        strategyResolver,
        eventDispatcher,
        judgmentNodeExecutor);
  }

  @Produces
  @ApplicationScoped
  WorkerIdentityResolver workerIdentityResolver() {
    return new WorkerIdentityResolver();
  }

  @Produces
  @ApplicationScoped
  WorkerGrantOrchestrator workerGrantOrchestrator(
      AccessControlProvider accessControlProvider,
      WorkerCredentialStore credentialStore,
      WorkerIdentityResolver identityResolver,
      WorkerAuthorizationPolicy authorizationPolicy) {
    return new WorkerGrantOrchestrator(
        accessControlProvider, credentialStore, identityResolver, authorizationPolicy);
  }

  @Produces
  @ApplicationScoped
  ActionGateApprovedHandler actionGateApprovedHandler(
      CaseInstanceCache caseInstanceCache,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      BridgeResolver bridgeResolver,
      CaseInstanceRepository caseInstanceRepository) {
    return new ActionGateApprovedHandler(
        caseInstanceCache,
        caseDefinitionRegistry,
        eventLogRepository,
        eventDispatcher,
        bridgeResolver,
        caseInstanceRepository);
  }

  @Produces
  @ApplicationScoped
  ActionGateRejectedHandler actionGateRejectedHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      RecoveryCoordinator recoveryCoordinator,
      CaseInstanceRepository caseInstanceRepository,
      Instance<RoutingOutcomeRecorder> outcomeRecorderInstance) {
    return new ActionGateRejectedHandler(
        caseInstanceCache,
        eventLogRepository,
        eventDispatcher,
        workerStatusListener,
        recoveryCoordinator,
        caseInstanceRepository,
        outcomeRecorderInstance.isResolvable()
            ? java.util.Optional.of(outcomeRecorderInstance.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  ActionGateExpiredHandler actionGateExpiredHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      WorkerStatusListener workerStatusListener,
      CaseInstanceRepository caseInstanceRepository,
      Instance<RoutingOutcomeRecorder> outcomeRecorderInstance) {
    return new ActionGateExpiredHandler(
        caseInstanceCache,
        eventLogRepository,
        eventDispatcher,
        workerStatusListener,
        caseInstanceRepository,
        outcomeRecorderInstance.isResolvable()
            ? java.util.Optional.of(outcomeRecorderInstance.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  QuiescenceTracker quiescenceTracker() {
    return new QuiescenceTracker();
  }

  @Produces
  @ApplicationScoped
  CaseCompletionTracker caseCompletionTracker() {
    return new CaseCompletionTracker();
  }

  @Produces
  @ApplicationScoped
  CaseRecoveryStateRegistry caseRecoveryStateRegistry() {
    return new CaseRecoveryStateRegistry();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.recovery.CaseRecoveryService caseRecoveryService(
      io.casehub.engine.common.spi.cache.CaseInstanceCache caseInstanceCache,
      io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository crossTenantRepository,
      CaseCompletionTracker caseCompletionTracker,
      io.casehub.api.spi.event.EventDispatcher eventDispatcher,
      io.casehub.engine.common.spi.CaseInstanceRepository caseInstanceRepository,
      io.casehub.api.spi.CaseChannelProvider caseChannelProvider,
      io.casehub.engine.runtime.scheduler.SchedulerService schedulerService) {
    return new io.casehub.engine.runtime.recovery.CaseRecoveryService(
        caseInstanceCache,
        crossTenantRepository,
        caseCompletionTracker,
        eventDispatcher,
        caseInstanceRepository,
        caseChannelProvider,
        schedulerService);
  }

  @Produces
  @ApplicationScoped
  SelectionContextStore selectionContextStore() {
    return new SelectionContextStore();
  }

  @Produces
  @ApplicationScoped
  CaseEvaluationSerializer caseEvaluationSerializer(QuiescenceTracker quiescenceTracker) {
    return new CaseEvaluationSerializer(quiescenceTracker);
  }

  @Produces
  @ApplicationScoped
  SchedulerService schedulerService(
      JobScheduler scheduler, CaseDefinitionRegistry caseDefinitionRegistry) {
    return new SchedulerService(scheduler, caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  CaseStatusChangedHandler caseStatusChangedHandler(
      EventDispatcher eventDispatcher,
      CaseInstanceRepository caseInstanceRepository,
      SchedulerService schedulerService,
      Event<CaseLifecycleEvent> lifecycleEvents,
      CaseChannelProvider caseChannelProvider,
      LedgerTraceIdProvider traceIdProvider,
      Instance<CaseOutcomeObserver> outcomeObservers,
      CaseCompletionTracker caseCompletionTracker,
      ScopedWorkerRegistry scopedWorkerRegistry,
      ContextOutputApplier contextOutputApplier,
      WorkerGrantOrchestrator workerGrantOrchestrator,
      DataChannelRegistry dataChannelRegistry,
      CaseRecoveryStateRegistry recoveryStateRegistry,
      CompoundLockRegistry compoundLockRegistry,
      io.casehub.engine.common.internal.observation.ObservationRegistry observationRegistry,
      io.casehub.engine.common.internal.observation.ContextHistoryBuffer contextHistoryBuffer,
      io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry,
      io.casehub.engine.common.internal.observation.RuleRegistry ruleRegistry,
      io.casehub.engine.common.internal.convergence.ActivityTracker activityTracker,
      io.casehub.engine.runtime.convergence.ConvergenceDetector convergenceDetector,
      Instance<io.casehub.engine.runtime.stigmergy.StigmergyCoordinator> stigmergyCoordinator,
      Instance<io.casehub.engine.runtime.stigmergy.RoleTracker> roleTracker,
      Instance<io.casehub.engine.runtime.stigmergy.TeamDetector> teamDetector,
      Instance<io.casehub.engine.runtime.stigmergy.SwarmProgressTracker> swarmProgressTracker,
      Instance<io.casehub.engine.runtime.stigmergy.SwarmProvisioner> swarmProvisioner) {
    return new CaseStatusChangedHandler(
        eventDispatcher,
        caseInstanceRepository,
        schedulerService,
        event -> {
          try {
            lifecycleEvents.fireAsync(event).toCompletableFuture().join();
          } catch (Exception t) {
            LOG.warnf(t, "CaseLifecycleEvent observer failed for CaseStatusChanged");
          }
        },
        caseChannelProvider,
        traceIdProvider,
        StreamSupport.stream(outcomeObservers.spliterator(), false).toList(),
        caseCompletionTracker,
        scopedWorkerRegistry,
        contextOutputApplier,
        workerGrantOrchestrator,
        dataChannelRegistry,
        recoveryStateRegistry,
        compoundLockRegistry,
        observationRegistry,
        contextHistoryBuffer,
        signalRegistry,
        ruleRegistry,
        activityTracker,
        convergenceDetector,
        stigmergyCoordinator,
        roleTracker,
        teamDetector,
        swarmProgressTracker,
        swarmProvisioner);
  }

  @Produces
  @ApplicationScoped
  AgentMemoryRetriever agentMemoryRetriever(Instance<CaseMemoryStore> caseMemoryStore) {
    return new AgentMemoryRetriever(
        caseMemoryStore.isResolvable()
            ? java.util.Optional.of(caseMemoryStore.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.AgentGoalCompletionMarker agentGoalCompletionMarker(
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.runtime.routing.AgentGoalCompletionMarker(caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.GoalOutcomeRecorder goalOutcomeRecorder(
      Instance<io.casehub.eidos.api.GoalSignalStore> goalSignalStore,
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.runtime.routing.GoalOutcomeRecorder(
        goalSignalStore.isResolvable()
            ? java.util.Optional.of(goalSignalStore.get())
            : java.util.Optional.empty(),
        caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.AgentCandidateFactory agentCandidateFactory(
      StrategyResolver strategyResolver) {
    return new io.casehub.engine.runtime.routing.AgentCandidateFactory(strategyResolver);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.handler.ExpectationValidator expectationValidator(
      io.casehub.engine.common.internal.monitoring.ExpectedEffectResolver effectResolver) {
    return new io.casehub.engine.runtime.engine.handler.ExpectationValidator(effectResolver);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.FailureCritiqueService failureCritiqueService(
      Instance<io.casehub.api.model.ai.ChatModelProvider> chatModelProvider) {
    return new io.casehub.engine.runtime.worker.FailureCritiqueService(
        chatModelProvider.isResolvable()
            ? java.util.Optional.of(chatModelProvider.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.work.PendingWorkRegistry pendingWorkRegistry(
      @io.casehub.engine.common.qualifier.CrossTenant
          io.casehub.engine.common.spi.CrossTenantEventLogRepository eventLogRepository) {
    return new io.casehub.engine.runtime.work.PendingWorkRegistry(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.work.CaseResumptionService caseResumptionService(
      CaseInstanceRepository caseInstanceRepository,
      io.casehub.engine.runtime.work.PendingWorkRegistry pendingWorkRegistry) {
    return new io.casehub.engine.runtime.work.CaseResumptionService(
        caseInstanceRepository, pendingWorkRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.PersonalitySignalRecorder personalitySignalRecorder(
      Instance<io.casehub.eidos.api.DispositionSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Instance<io.casehub.eidos.api.DispositionHealth> dispositionHealth,
      Instance<io.casehub.eidos.api.DispositionEvolution> dispositionEvolution) {
    return new io.casehub.engine.runtime.routing.PersonalitySignalRecorder(
        signalStore.isResolvable()
            ? java.util.Optional.of(signalStore.get())
            : java.util.Optional.empty(),
        caseDefinitionRegistry,
        dispositionHealth.isResolvable()
            ? java.util.Optional.of(dispositionHealth.get())
            : java.util.Optional.empty(),
        dispositionEvolution.isResolvable()
            ? java.util.Optional.of(dispositionEvolution.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.BehavioralComplianceRecorder behavioralComplianceRecorder(
      Instance<io.casehub.eidos.api.BehavioralSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Instance<io.casehub.engine.common.spi.PlanItemStore> planItemStore,
      io.casehub.eidos.api.VocabularyRegistry vocabularyRegistry) {
    return new io.casehub.engine.runtime.routing.BehavioralComplianceRecorder(
        signalStore.isResolvable()
            ? java.util.Optional.of(signalStore.get())
            : java.util.Optional.empty(),
        caseDefinitionRegistry,
        planItemStore.isResolvable()
            ? java.util.Optional.of(planItemStore.get())
            : java.util.Optional.empty(),
        vocabularyRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.GoalRevisionEvaluator goalRevisionEvaluator(
      Instance<io.casehub.eidos.api.GoalSignalStore> goalSignalStore,
      Instance<io.casehub.eidos.api.GoalEvolution> goalEvolution,
      Instance<io.casehub.eidos.api.AgentRegistry> agentRegistry,
      io.casehub.api.spi.routing.GoalRemovalService goalRemovalService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.goal-revision.enabled",
              defaultValue = "false")
          boolean enabled,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.goal-revision.strategy",
              defaultValue = "default")
          String strategyId,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.goal-revision.min-outcomes",
              defaultValue = "3")
          int minOutcomes,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.goal-revision.importance-threshold",
              defaultValue = "0.3")
          double importanceThreshold) {
    return new io.casehub.engine.runtime.routing.GoalRevisionEvaluator(
        goalSignalStore.isResolvable()
            ? java.util.Optional.of(goalSignalStore.get())
            : java.util.Optional.empty(),
        goalEvolution.isResolvable()
            ? java.util.Optional.of(goalEvolution.get())
            : java.util.Optional.empty(),
        agentRegistry.isResolvable()
            ? java.util.Optional.of(agentRegistry.get())
            : java.util.Optional.empty(),
        goalRemovalService,
        caseDefinitionRegistry,
        strategyResolver,
        eventLogRepository,
        enabled,
        strategyId,
        minOutcomes,
        importanceThreshold);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.CbrRetrievalService cbrRetrievalService(
      io.casehub.engine.common.internal.jq.JQEvaluator jqEvaluator,
      io.casehub.neocortex.memory.cbr.CbrRecordStore cbrStore,
      io.casehub.neocortex.memory.cbr.CbrPlanAdapter planAdapter,
      io.casehub.neocortex.memory.cbr.CbrPlanEnsembleAnalyzer ensembleAnalyzer,
      @io.quarkus.arc.All Instance<io.casehub.api.model.cbr.CbrCaseTypeRegistration> registrations,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.cbr.ensemble-timeout-ms",
              defaultValue = "5000")
          long ensembleTimeoutMs) {
    return new io.casehub.engine.runtime.routing.CbrRetrievalService(
        jqEvaluator,
        cbrStore,
        planAdapter,
        ensembleAnalyzer,
        StreamSupport.stream(registrations.spliterator(), false).toList(),
        ensembleTimeoutMs);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.GoalFormationEvaluator goalFormationEvaluator(
      Instance<io.casehub.eidos.api.AgentRegistry> agentRegistry,
      Instance<io.casehub.api.spi.routing.GoalFormationService> goalFormationService,
      Instance<CaseMemoryStore> caseMemoryStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.enabled",
              defaultValue = "false")
          boolean enabled,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.auto-approve",
              defaultValue = "true")
          boolean autoApprove,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.strategy",
              defaultValue = "llm")
          String strategyId,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.max-new-per-reflection",
              defaultValue = "2")
          int maxNewPerReflection,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.cooldown-minutes",
              defaultValue = "60")
          long cooldownMinutes,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.formation.max-memories",
              defaultValue = "20")
          int maxMemories) {
    return new io.casehub.engine.runtime.routing.GoalFormationEvaluator(
        agentRegistry.isResolvable()
            ? java.util.Optional.of(agentRegistry.get())
            : java.util.Optional.empty(),
        goalFormationService.isResolvable()
            ? java.util.Optional.of(goalFormationService.get())
            : java.util.Optional.empty(),
        caseMemoryStore.isResolvable()
            ? java.util.Optional.of(caseMemoryStore.get())
            : java.util.Optional.empty(),
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

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.memory.AgentExperienceRecorder agentExperienceRecorder(
      Instance<io.casehub.neocortex.memory.experience.ExperienceRecorder> experienceRecorder,
      Instance<io.casehub.neocortex.memory.reflection.ReflectionOrchestrator>
          reflectionOrchestrator,
      CaseDefinitionRegistry caseDefinitionRegistry,
      io.casehub.engine.runtime.routing.GoalFormationEvaluator goalFormationEvaluator,
      Instance<CaseMemoryStore> caseMemoryStore,
      Instance<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.reasoning.enabled",
              defaultValue = "true")
          boolean reasoningEnabled) {
    return new io.casehub.engine.runtime.memory.AgentExperienceRecorder(
        experienceRecorder.isResolvable()
            ? java.util.Optional.of(experienceRecorder.get())
            : java.util.Optional.empty(),
        reflectionOrchestrator.isResolvable()
            ? java.util.Optional.of(reflectionOrchestrator.get())
            : java.util.Optional.empty(),
        caseDefinitionRegistry,
        goalFormationEvaluator,
        caseMemoryStore.isResolvable()
            ? java.util.Optional.of(caseMemoryStore.get())
            : java.util.Optional.empty(),
        meterRegistry.isResolvable()
            ? java.util.Optional.of(meterRegistry.get())
            : java.util.Optional.empty(),
        reasoningEnabled);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.CbrCacheEvictionHandler cbrCacheEvictionHandler(
      io.casehub.engine.runtime.routing.CbrRetrievalService cbrRetrievalService) {
    return new io.casehub.engine.runtime.routing.CbrCacheEvictionHandler(cbrRetrievalService);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.handler.ScopedWorkerOutputHandler scopedWorkerOutputHandler(
      ContextOutputApplier contextOutputApplier,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      io.casehub.engine.runtime.memory.AgentExperienceRecorder agentExperienceRecorder,
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.runtime.engine.handler.ScopedWorkerOutputHandler(
        contextOutputApplier,
        eventLogRepository,
        eventDispatcher,
        agentExperienceRecorder,
        caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  WorkerScheduleEventHandler workerScheduleEventHandler(
      WorkerExecutionManager workflowExecutionManager,
      WorkerExecutionGuard workerExecutionGuard,
      QuiescenceTracker quiescenceTracker,
      WorkerContextProvider workerContextProvider,
      CaseChannelProvider caseChannelProvider,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      ExpressionEngineRegistry expressionEngineRegistry,
      BridgeResolver bridgeResolver,
      CaseDefinitionRegistry caseDefinitionRegistry,
      AgentMemoryRetriever agentMemoryRetriever,
      @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.idempotency.window")
          java.util.Optional<java.time.Duration> idempotencyWindow) {
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

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.handler.WorkflowExecutionCompletedHandler
      workflowExecutionCompletedHandler(
          EventDispatcher eventDispatcher,
          Event<CaseLifecycleEvent> lifecycleEvents,
          Event<io.casehub.engine.common.spi.event.WorkerDecisionEvent> workerDecisionEvents,
          EventLogRepository eventLogRepository,
          CaseDefinitionRegistry caseDefinitionRegistry,
          io.casehub.engine.runtime.work.CaseResumptionService caseResumptionService,
          WorkerStatusListener workerStatusListener,
          LedgerTraceIdProvider traceIdProvider,
          io.casehub.api.spi.ActionRiskClassifier actionRiskClassifier,
          CaseInstanceRepository caseInstanceRepository,
          SignalSettlementTracker settlementTracker,
          QuiescenceTracker quiescenceTracker,
          io.casehub.engine.runtime.routing.PersonalitySignalRecorder personalitySignalRecorder,
          io.casehub.engine.runtime.routing.GoalOutcomeRecorder goalOutcomeRecorder,
          io.casehub.engine.runtime.routing.BehavioralComplianceRecorder
              behavioralComplianceRecorder,
          io.casehub.engine.runtime.routing.AgentGoalCompletionMarker agentGoalCompletionMarker,
          io.casehub.engine.runtime.memory.AgentExperienceRecorder agentExperienceRecorder,
          io.casehub.engine.runtime.routing.GoalRevisionEvaluator goalRevisionEvaluator,
          WorkerGrantOrchestrator workerGrantOrchestrator,
          ContextOutputApplier contextOutputApplier,
          StrategyResolver strategyResolver,
          RecoveryCoordinator recoveryCoordinator,
          io.casehub.api.spi.FailureClassifier failureClassifier,
          io.casehub.engine.runtime.engine.handler.ExpectationValidator expectationValidator,
          io.casehub.engine.runtime.worker.FailureCritiqueService failureCritiqueService,
          SelectionContextStore selectionContextStore,
          Instance<io.casehub.api.spi.routing.RoutingOutcomeRecorder> outcomeRecorder,
          Instance<io.casehub.engine.common.spi.ActionGateScheduler> actionGateScheduler,
          Instance<io.casehub.api.spi.StepOutcomeObserver> stepOutcomeObserver) {
    return new io.casehub.engine.runtime.engine.handler.WorkflowExecutionCompletedHandler(
        eventDispatcher,
        event ->
            lifecycleEvents
                .fireAsync(event)
                .whenComplete(
                    (v, t) -> {
                      if (t != null) {
                        LOG.warnf(t, "CaseLifecycleEvent observer failed");
                      }
                    }),
        event ->
            workerDecisionEvents
                .fireAsync(event)
                .whenComplete(
                    (v, t) -> {
                      if (t != null) {
                        LOG.warnf(t, "WorkerDecisionEvent observer failed");
                      }
                    }),
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
        outcomeRecorder.isResolvable()
            ? java.util.Optional.of(outcomeRecorder.get())
            : java.util.Optional.empty(),
        actionGateScheduler.isResolvable()
            ? java.util.Optional.of(actionGateScheduler.get())
            : java.util.Optional.empty(),
        StreamSupport.stream(stepOutcomeObserver.spliterator(), false).toList());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.handler.CaseContextChangedEventHandler
      caseContextChangedEventHandler(
          io.casehub.api.spi.event.EventDispatcher eventDispatcher,
          io.casehub.engine.common.internal.jq.JQEvaluator jqEvaluator,
          io.casehub.engine.common.spi.CaseDefinitionRegistry caseDefinitionRegistry,
          io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry,
          io.casehub.api.engine.LoopControl loopControl,
          io.casehub.platform.api.routing.StrategyResolver strategyResolver,
          io.casehub.engine.runtime.routing.AgentCandidateFactory agentCandidateFactory,
          io.casehub.engine.common.spi.scheduler.WorkerExecutionManager executionManager,
          io.casehub.eidos.api.CapabilityHealth capabilityHealth,
          io.casehub.api.spi.WorkerContextProvider workerContextProvider,
          io.casehub.api.spi.WorkerProvisioner workerProvisioner,
          jakarta.enterprise.event.Event<io.casehub.engine.common.spi.event.CaseLifecycleEvent>
              lifecycleEvents,
          io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider,
          io.casehub.engine.runtime.routing.CbrRetrievalService cbrRetrievalService,
          io.casehub.engine.common.internal.context.BridgeResolver bridgeResolver,
          io.casehub.engine.runtime.engine.SignalSettlementTracker settlementTracker,
          io.casehub.engine.runtime.acl.WorkerGrantOrchestrator workerGrantOrchestrator,
          @io.quarkus.virtual.threads.VirtualThreads
              java.util.concurrent.ExecutorService virtualThreads,
          io.casehub.engine.runtime.engine.CaseEvaluationSerializer evaluationSerializer,
          io.casehub.engine.runtime.engine.QuiescenceTracker quiescenceTracker,
          io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry scopedWorkerRegistry,
          io.casehub.engine.runtime.routing.SelectionContextStore selectionContextStore,
          io.casehub.api.spi.DispatchBudget dispatchBudget,
          io.casehub.engine.common.spi.PlanItemStore planItemStore,
          jakarta.enterprise.event.Event<io.casehub.engine.common.spi.event.CaseContextUpdatedEvent>
              caseContextUpdatedEvents,
          jakarta.enterprise.inject.Instance<io.casehub.engine.common.spi.JudgmentScheduler>
              judgmentScheduler,
          io.casehub.engine.common.internal.observation.ObservationRegistry observationRegistry,
          io.casehub.engine.common.internal.observation.ContextHistoryBuffer contextHistoryBuffer,
          io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry,
          io.casehub.engine.common.internal.observation.RuleRegistry ruleRegistry,
          io.casehub.engine.common.internal.convergence.ActivityTracker activityTracker,
          io.casehub.engine.runtime.convergence.ConvergenceDetector convergenceDetector,
          io.casehub.engine.runtime.convergence.BudgetEnforcer budgetEnforcer,
          Instance<io.casehub.engine.runtime.stigmergy.StigmergyCoordinator> stigmergyCoordinator,
          Instance<io.casehub.engine.runtime.stigmergy.RoleTracker> roleTracker,
          Instance<io.casehub.engine.runtime.stigmergy.TeamDetector> teamDetector,
          Instance<io.casehub.engine.runtime.stigmergy.SwarmProgressTracker> swarmProgressTracker,
          Instance<io.casehub.engine.runtime.stigmergy.SwarmProvisioner> swarmProvisioner,
          Instance<io.casehub.engine.runtime.improvement.ImprovementGoalFormationStrategy>
              improvementStrategy,
          Instance<io.casehub.api.spi.routing.GoalFormationService> goalFormationService) {
    return new io.casehub.engine.runtime.engine.handler.CaseContextChangedEventHandler(
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
        event -> {
          try {
            lifecycleEvents.fireAsync(event).toCompletableFuture().join();
          } catch (Exception t) {
            LOG.warnf(t, "CaseLifecycleEvent observer failed for CaseContextChanged");
          }
        },
        traceIdProvider,
        cbrRetrievalService,
        bridgeResolver,
        settlementTracker,
        workerGrantOrchestrator,
        virtualThreads,
        evaluationSerializer,
        quiescenceTracker,
        scopedWorkerRegistry,
        selectionContextStore,
        dispatchBudget,
        planItemStore,
        event -> caseContextUpdatedEvents.fireAsync(event),
        judgmentScheduler.isResolvable()
            ? java.util.Optional.of(judgmentScheduler.get())
            : java.util.Optional.empty(),
        observationRegistry,
        contextHistoryBuffer,
        signalRegistry,
        ruleRegistry,
        activityTracker,
        convergenceDetector,
        budgetEnforcer,
        stigmergyCoordinator,
        roleTracker,
        teamDetector,
        swarmProgressTracker,
        swarmProvisioner,
        improvementStrategy,
        goalFormationService);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.convergence.BudgetEnforcer budgetEnforcer() {
    return new io.casehub.engine.runtime.convergence.BudgetEnforcer();
  }

  // --- Task 6: Simple services ---

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.LambdaExpressionEngine lambdaExpressionEngine() {
    return new io.casehub.engine.runtime.engine.LambdaExpressionEngine();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.ExactMatchStrategy exactMatchStrategy() {
    return new io.casehub.engine.runtime.routing.ExactMatchStrategy();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.AllowAllWorkerExecutionGuard allowAllWorkerExecutionGuard() {
    return new io.casehub.engine.runtime.worker.AllowAllWorkerExecutionGuard();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.ReYieldEscalator reYieldEscalator() {
    return new io.casehub.engine.runtime.worker.ReYieldEscalator();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.FaultEscalator faultEscalator() {
    return new io.casehub.engine.runtime.worker.FaultEscalator();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.EvidencePresenceVerifier evidencePresenceVerifier() {
    return new io.casehub.engine.runtime.worker.EvidencePresenceVerifier();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.WorkloadSignalProvider workloadSignalProvider() {
    return new io.casehub.engine.runtime.routing.WorkloadSignalProvider();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.JQExpressionEngine jqExpressionEngine(
      io.casehub.engine.common.internal.jq.JQEvaluator jqEvaluator) {
    return new io.casehub.engine.runtime.engine.JQExpressionEngine(jqEvaluator);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.cache.CaseInstanceCacheImpl caseInstanceCacheImpl() {
    return new io.casehub.engine.runtime.engine.cache.CaseInstanceCacheImpl();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.DefaultCaseEventRecorder defaultCaseEventRecorder(
      io.casehub.engine.common.spi.EventLogRepository eventLogRepository) {
    return new io.casehub.engine.runtime.engine.DefaultCaseEventRecorder(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.ConstraintHumanTaskRoutingStrategy
      constraintHumanTaskRoutingStrategy(
          io.casehub.api.engine.ExpressionEngineRegistry expressionRegistry,
          io.casehub.api.spi.routing.WorkloadDataProvider workloadProvider) {
    return new io.casehub.engine.runtime.routing.ConstraintHumanTaskRoutingStrategy(
        expressionRegistry, workloadProvider);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.engine.EngineResetService engineResetService(
      Instance<io.casehub.engine.common.spi.Resettable> resettables) {
    return new io.casehub.engine.runtime.engine.EngineResetService(resettables.stream().toList());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.config.impl.ConfigSecretManager configSecretManager(
      io.casehub.engine.common.internal.config.ConfigManager configManager) {
    return new io.casehub.engine.runtime.config.impl.ConfigSecretManager(configManager);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.DefaultWorkerFunctionProviderRegistry
      defaultWorkerFunctionProviderRegistry(
          Instance<io.casehub.api.spi.WorkerFunctionProvider> providers) {
    return new io.casehub.engine.runtime.worker.DefaultWorkerFunctionProviderRegistry(
        providers.stream().toList());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.worker.CompositeWorkerExecutionManager compositeWorkerExecutionManager(
      io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy routingStrategy,
      @io.casehub.engine.common.spi.scheduler.WorkerBackend
          Instance<WorkerExecutionManager> backends) {
    return new io.casehub.engine.runtime.worker.CompositeWorkerExecutionManager(
        routingStrategy, backends.stream().toList());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.ExperienceSignalProvider experienceSignalProvider() {
    return new io.casehub.engine.runtime.routing.ExperienceSignalProvider();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.GoalAbandonmentEvaluator goalAbandonmentEvaluator(
      Instance<io.casehub.eidos.api.GoalSignalStore> signalStore,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.engine.goal.abandonment-threshold",
              defaultValue = "5")
          int threshold) {
    return new io.casehub.engine.runtime.routing.GoalAbandonmentEvaluator(
        signalStore.isResolvable()
            ? java.util.Optional.of(signalStore.get())
            : java.util.Optional.empty(),
        threshold);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.PersonalitySignalProvider personalitySignalProvider(
      Instance<io.casehub.eidos.api.DispositionHealth> dispositionHealth) {
    return new io.casehub.engine.runtime.routing.PersonalitySignalProvider(
        dispositionHealth.isResolvable()
            ? java.util.Optional.of(dispositionHealth.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.GoalSignalProvider goalSignalProvider(
      Instance<io.casehub.engine.runtime.routing.GoalAbandonmentEvaluator> evaluator) {
    return new io.casehub.engine.runtime.routing.GoalSignalProvider(
        evaluator.isResolvable()
            ? java.util.Optional.of(evaluator.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.routing.CbrHumanTaskRoutingStrategy cbrHumanTaskRoutingStrategy() {
    return new io.casehub.engine.runtime.routing.CbrHumanTaskRoutingStrategy();
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.config.impl.DefaultConfigContext defaultConfigContext(
      io.casehub.engine.common.internal.config.ConfigManager configManager,
      io.casehub.engine.common.internal.config.SecretManager secretManager) {
    return new io.casehub.engine.runtime.config.impl.DefaultConfigContext(
        configManager, secretManager);
  }

  @Produces
  @ApplicationScoped
  DefaultGoalRemovalService defaultGoalRemovalService(
      io.casehub.eidos.api.AgentRegistry agentRegistry, EventLogRepository eventLogRepository) {
    return new DefaultGoalRemovalService(agentRegistry, eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  DefaultGoalFormationService defaultGoalFormationService(
      io.casehub.eidos.api.AgentRegistry agentRegistry, EventLogRepository eventLogRepository) {
    return new DefaultGoalFormationService(agentRegistry, eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  LlmGoalFormationStrategy llmGoalFormationStrategy(
      Instance<io.casehub.api.model.ai.ChatModelProvider> chatModelProvider) {
    return new LlmGoalFormationStrategy(
        chatModelProvider.isResolvable()
            ? java.util.Optional.of(chatModelProvider.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  LlmGoalRevisionStrategy llmGoalRevisionStrategy(
      Instance<io.casehub.api.model.ai.ChatModelProvider> chatModelProvider) {
    return new LlmGoalRevisionStrategy(
        chatModelProvider.isResolvable()
            ? java.util.Optional.of(chatModelProvider.get())
            : java.util.Optional.empty());
  }

  // --- common-core EventDispatcher-dependent producers ---

  @Produces
  @ApplicationScoped
  MilestoneSLAOrchestrator milestoneSLAOrchestrator(
      CaseInstanceCache caseInstanceCache,
      @CrossTenant CrossTenantCaseInstanceRepository caseInstanceRepository,
      @CrossTenant CrossTenantEventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher) {
    return new MilestoneSLAOrchestrator(
        caseInstanceCache, caseInstanceRepository, eventLogRepository, eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  RetryOrchestrator retryOrchestrator(
      EventLogRepository eventLogRepository,
      WorkerExecutionRecoveryService recoveryService,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventDispatcher eventDispatcher,
      RecoveryCoordinator recoveryCoordinator) {
    return new RetryOrchestrator(
        eventLogRepository,
        recoveryService,
        caseDefinitionRegistry,
        eventDispatcher,
        recoveryCoordinator);
  }

  @Produces
  @ApplicationScoped
  ScheduledTriggerOrchestrator scheduledTriggerOrchestrator(
      CaseDefinitionRegistry caseDefinitionRegistry,
      WorkerExecutionRecoveryService recoveryService,
      ScopedWorkerRegistry scopedWorkerRegistry,
      io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry,
      EventDispatcher eventDispatcher) {
    return new ScheduledTriggerOrchestrator(
        caseDefinitionRegistry,
        recoveryService,
        scopedWorkerRegistry,
        expressionEngineRegistry,
        eventDispatcher);
  }

  @Produces
  @ApplicationScoped
  WorkerExecutionOrchestrator workerExecutionOrchestrator(
      WorkerExecutor workerExecutor,
      CaseDefinitionRegistry caseDefinitionRegistry,
      WorkerContextProvider workerContextProvider,
      EventDispatcher eventDispatcher,
      WorkerExecutionRecoveryService recoveryService,
      @CrossTenant CrossTenantEventLogRepository crossTenantEventLogRepository,
      EventLogRepository eventLogRepository,
      WorkerExecutionConfig executionConfig,
      BridgeResolver bridgeResolver,
      WorkerStatusListener workerStatusListener,
      Event<CaseLifecycleEvent> lifecycleEvents,
      LedgerTraceIdProvider traceIdProvider) {
    return new WorkerExecutionOrchestrator(
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
        e -> lifecycleEvents.fireAsync(e),
        traceIdProvider);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.executor.WorkerRuntimeFactory workerRuntimeFactory(
      io.casehub.api.engine.CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      io.casehub.engine.runtime.engine.CaseCompletionTracker caseCompletionTracker,
      DataChannelRegistry channelRegistry,
      io.casehub.api.spi.DataChannelFactory defaultChannelFactory,
      io.casehub.engine.common.internal.observation.ObservationRegistry observationRegistry,
      io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry,
      io.casehub.engine.common.spi.PlanItemStore planItemStore,
      io.casehub.engine.common.internal.observation.RuleRegistry ruleRegistry,
      io.casehub.engine.common.internal.convergence.ActivityTracker activityTracker,
      Instance<io.casehub.engine.runtime.stigmergy.RoleTracker> roleTracker,
      Instance<io.casehub.engine.runtime.stigmergy.TeamDetector> teamDetector,
      Instance<io.casehub.engine.runtime.stigmergy.SwarmProgressTracker> swarmProgressTracker,
      Instance<io.casehub.engine.runtime.stigmergy.StigmergyCoordinator> stigmergyCoordinator) {
    var factory =
        new io.casehub.engine.runtime.executor.WorkerRuntimeFactory(
            caseHubRuntime,
            definitionRegistry,
            caseInstanceCache,
            caseCompletionTracker,
            channelRegistry,
            defaultChannelFactory,
            observationRegistry,
            signalRegistry,
            planItemStore,
            ruleRegistry);
    if (stigmergyCoordinator.isResolvable()) {
      factory.setStigmergyCoordinator(stigmergyCoordinator.get());
    }
    if (roleTracker.isResolvable()) {
      factory.setSwarmTrackers(
          activityTracker, roleTracker.get(), teamDetector.get(), swarmProgressTracker.get());
    }
    return factory;
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.stigmergy.SwarmProvisioner swarmProvisioner(
      io.casehub.api.spi.WorkerProvisioner workerProvisioner,
      io.casehub.engine.runtime.stigmergy.StigmergyCoordinator coordinator,
      io.casehub.engine.common.internal.signal.SignalRegistry signalRegistry,
      io.casehub.engine.common.internal.convergence.ActivityTracker activityTracker,
      io.casehub.engine.runtime.stigmergy.RoleTracker roleTracker,
      io.casehub.engine.runtime.stigmergy.TeamDetector teamDetector,
      io.casehub.engine.runtime.stigmergy.SwarmProgressTracker progressTracker,
      io.casehub.api.spi.DispatchBudget dispatchBudget,
      Instance<io.casehub.api.spi.stigmergy.SwarmProvisioningAdvisor> advisorInstance) {
    return new io.casehub.engine.runtime.stigmergy.SwarmProvisioner(
        workerProvisioner,
        coordinator,
        signalRegistry,
        activityTracker,
        roleTracker,
        teamDetector,
        progressTracker,
        dispatchBudget,
        advisorInstance.isResolvable() ? advisorInstance.get() : null);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.runtime.improvement.EvolutionTicker evolutionTicker(
      io.casehub.engine.runtime.improvement.ImprovementGoalFormationStrategy goalFormation,
      io.casehub.engine.runtime.improvement.ImprovementCircuitBreaker circuitBreaker,
      io.casehub.engine.runtime.improvement.HealthScoreTracker healthTracker,
      io.casehub.engine.runtime.improvement.RegressionDetector regressionDetector,
      io.casehub.api.spi.routing.GoalFormationService goalFormationService,
      io.casehub.engine.runtime.improvement.TickTraceBuffer traceBuffer,
      jakarta.enterprise.event.Event<io.casehub.engine.common.spi.event.TickEvaluatedEvent>
          tickEvaluatedEvent) {
    return new io.casehub.engine.runtime.improvement.EvolutionTicker(
        goalFormation,
        circuitBreaker,
        healthTracker,
        regressionDetector,
        goalFormationService,
        traceBuffer,
        tickEvaluatedEvent);
  }
}
