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
package io.casehub.engine.internal.quarkus;

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
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.acl.WorkerGrantOrchestrator;
import io.casehub.engine.internal.acl.WorkerIdentityResolver;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.engine.internal.engine.CaseEvaluationSerializer;
import io.casehub.engine.internal.engine.QuiescenceTracker;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import io.casehub.engine.internal.engine.handler.ActionGateApprovedHandler;
import io.casehub.engine.internal.engine.handler.ActionGateExpiredHandler;
import io.casehub.engine.internal.engine.handler.ActionGateRejectedHandler;
import io.casehub.engine.internal.engine.handler.AgentRoutingEscalationHandler;
import io.casehub.engine.internal.engine.handler.CaseStatusChangedHandler;
import io.casehub.engine.internal.engine.handler.ContextOutputApplier;
import io.casehub.engine.internal.engine.handler.ContextSignalEventHandler;
import io.casehub.engine.internal.engine.handler.GoalReachedEventHandler;
import io.casehub.engine.internal.engine.handler.JudgmentCompletedHandler;
import io.casehub.engine.internal.engine.handler.JudgmentEscalationHandler;
import io.casehub.engine.internal.engine.handler.JudgmentExpiredHandler;
import io.casehub.engine.internal.engine.handler.MilestoneActivatedEventHandler;
import io.casehub.engine.internal.engine.handler.MilestoneCompletedEventHandler;
import io.casehub.engine.internal.engine.handler.MilestoneSLAViolatedEventHandler;
import io.casehub.engine.internal.engine.handler.ScopedWorkerTerminationHandler;
import io.casehub.engine.internal.engine.handler.WorkerRetriesExhaustedEventHandler;
import io.casehub.engine.internal.engine.handler.WorkerScheduleEventHandler;
import io.casehub.engine.internal.memory.AgentMemoryRetriever;
import io.casehub.engine.internal.milestone.MilestoneLifecycleManager;
import io.casehub.engine.internal.recovery.CaseRecoveryStateRegistry;
import io.casehub.engine.internal.routing.SelectionContextStore;
import io.casehub.engine.internal.scheduler.SchedulerService;
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
      ScopedWorkerRegistry scopedWorkerRegistry, DataChannelRegistry dataChannelRegistry) {
    return new ScopedWorkerTerminationHandler(scopedWorkerRegistry, dataChannelRegistry);
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
      CompoundLockRegistry compoundLockRegistry) {
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
        compoundLockRegistry);
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
  io.casehub.engine.internal.routing.AgentGoalCompletionMarker agentGoalCompletionMarker(
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.internal.routing.AgentGoalCompletionMarker(caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.routing.GoalOutcomeRecorder goalOutcomeRecorder(
      Instance<io.casehub.eidos.api.GoalSignalStore> goalSignalStore,
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.internal.routing.GoalOutcomeRecorder(
        goalSignalStore.isResolvable()
            ? java.util.Optional.of(goalSignalStore.get())
            : java.util.Optional.empty(),
        caseDefinitionRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.routing.AgentCandidateFactory agentCandidateFactory(
      StrategyResolver strategyResolver) {
    return new io.casehub.engine.internal.routing.AgentCandidateFactory(strategyResolver);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.engine.handler.ExpectationValidator expectationValidator(
      io.casehub.engine.common.internal.monitoring.ExpectedEffectResolver effectResolver) {
    return new io.casehub.engine.internal.engine.handler.ExpectationValidator(effectResolver);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.worker.FailureCritiqueService failureCritiqueService(
      Instance<io.casehub.api.model.ai.ChatModelProvider> chatModelProvider) {
    return new io.casehub.engine.internal.worker.FailureCritiqueService(
        chatModelProvider.isResolvable()
            ? java.util.Optional.of(chatModelProvider.get())
            : java.util.Optional.empty());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.work.PendingWorkRegistry pendingWorkRegistry(
      @io.casehub.engine.common.qualifier.CrossTenant
          io.casehub.engine.common.spi.CrossTenantEventLogRepository eventLogRepository) {
    return new io.casehub.engine.internal.work.PendingWorkRegistry(eventLogRepository);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.work.CaseResumptionService caseResumptionService(
      CaseInstanceRepository caseInstanceRepository,
      io.casehub.engine.internal.work.PendingWorkRegistry pendingWorkRegistry) {
    return new io.casehub.engine.internal.work.CaseResumptionService(
        caseInstanceRepository, pendingWorkRegistry);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.routing.PersonalitySignalRecorder personalitySignalRecorder(
      Instance<io.casehub.eidos.api.DispositionSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Instance<io.casehub.eidos.api.DispositionHealth> dispositionHealth,
      Instance<io.casehub.eidos.api.DispositionEvolution> dispositionEvolution) {
    return new io.casehub.engine.internal.routing.PersonalitySignalRecorder(
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
  io.casehub.engine.internal.routing.BehavioralComplianceRecorder behavioralComplianceRecorder(
      Instance<io.casehub.eidos.api.BehavioralSignalStore> signalStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      Instance<io.casehub.engine.common.spi.PlanItemStore> planItemStore,
      io.casehub.eidos.api.VocabularyRegistry vocabularyRegistry) {
    return new io.casehub.engine.internal.routing.BehavioralComplianceRecorder(
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
  io.casehub.engine.internal.routing.GoalRevisionEvaluator goalRevisionEvaluator(
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
    return new io.casehub.engine.internal.routing.GoalRevisionEvaluator(
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
  io.casehub.engine.internal.routing.CbrRetrievalService cbrRetrievalService(
      io.casehub.engine.common.internal.jq.JQEvaluator jqEvaluator,
      io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore cbrStore,
      io.casehub.neocortex.memory.cbr.PlanAdapter planAdapter,
      @io.quarkus.arc.All
          Instance<io.casehub.api.model.cbr.CbrCaseTypeRegistration> registrations) {
    return new io.casehub.engine.internal.routing.CbrRetrievalService(
        jqEvaluator,
        cbrStore,
        planAdapter,
        StreamSupport.stream(registrations.spliterator(), false).toList());
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.routing.GoalFormationEvaluator goalFormationEvaluator(
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
    return new io.casehub.engine.internal.routing.GoalFormationEvaluator(
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
  io.casehub.engine.internal.memory.AgentExperienceRecorder agentExperienceRecorder(
      Instance<io.casehub.neocortex.memory.experience.ExperienceRecorder> experienceRecorder,
      Instance<io.casehub.neocortex.memory.reflection.ReflectionOrchestrator>
          reflectionOrchestrator,
      CaseDefinitionRegistry caseDefinitionRegistry,
      io.casehub.engine.internal.routing.GoalFormationEvaluator goalFormationEvaluator,
      Instance<CaseMemoryStore> caseMemoryStore,
      Instance<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
      @org.eclipse.microprofile.config.inject.ConfigProperty(
              name = "casehub.reasoning.enabled",
              defaultValue = "true")
          boolean reasoningEnabled) {
    return new io.casehub.engine.internal.memory.AgentExperienceRecorder(
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
  io.casehub.engine.internal.routing.CbrCacheEvictionHandler cbrCacheEvictionHandler(
      io.casehub.engine.internal.routing.CbrRetrievalService cbrRetrievalService) {
    return new io.casehub.engine.internal.routing.CbrCacheEvictionHandler(cbrRetrievalService);
  }

  @Produces
  @ApplicationScoped
  io.casehub.engine.internal.engine.handler.ScopedWorkerOutputHandler scopedWorkerOutputHandler(
      ContextOutputApplier contextOutputApplier,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher,
      io.casehub.engine.internal.memory.AgentExperienceRecorder agentExperienceRecorder,
      CaseDefinitionRegistry caseDefinitionRegistry) {
    return new io.casehub.engine.internal.engine.handler.ScopedWorkerOutputHandler(
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
  io.casehub.engine.internal.engine.handler.WorkflowExecutionCompletedHandler
      workflowExecutionCompletedHandler(
          EventDispatcher eventDispatcher,
          Event<CaseLifecycleEvent> lifecycleEvents,
          Event<io.casehub.engine.common.spi.event.WorkerDecisionEvent> workerDecisionEvents,
          EventLogRepository eventLogRepository,
          CaseDefinitionRegistry caseDefinitionRegistry,
          io.casehub.engine.internal.work.CaseResumptionService caseResumptionService,
          WorkerStatusListener workerStatusListener,
          LedgerTraceIdProvider traceIdProvider,
          io.casehub.api.spi.ActionRiskClassifier actionRiskClassifier,
          CaseInstanceRepository caseInstanceRepository,
          SignalSettlementTracker settlementTracker,
          QuiescenceTracker quiescenceTracker,
          io.casehub.engine.internal.routing.PersonalitySignalRecorder personalitySignalRecorder,
          io.casehub.engine.internal.routing.GoalOutcomeRecorder goalOutcomeRecorder,
          io.casehub.engine.internal.routing.BehavioralComplianceRecorder
              behavioralComplianceRecorder,
          io.casehub.engine.internal.routing.AgentGoalCompletionMarker agentGoalCompletionMarker,
          io.casehub.engine.internal.memory.AgentExperienceRecorder agentExperienceRecorder,
          io.casehub.engine.internal.routing.GoalRevisionEvaluator goalRevisionEvaluator,
          WorkerGrantOrchestrator workerGrantOrchestrator,
          ContextOutputApplier contextOutputApplier,
          StrategyResolver strategyResolver,
          RecoveryCoordinator recoveryCoordinator,
          io.casehub.api.spi.FailureClassifier failureClassifier,
          io.casehub.engine.internal.engine.handler.ExpectationValidator expectationValidator,
          io.casehub.engine.internal.worker.FailureCritiqueService failureCritiqueService,
          SelectionContextStore selectionContextStore,
          Instance<io.casehub.api.spi.routing.RoutingOutcomeRecorder> outcomeRecorder,
          Instance<io.casehub.engine.common.spi.ActionGateScheduler> actionGateScheduler,
          Instance<io.casehub.api.spi.StepOutcomeObserver> stepOutcomeObserver) {
    return new io.casehub.engine.internal.engine.handler.WorkflowExecutionCompletedHandler(
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
        stepOutcomeObserver.isResolvable()
            ? java.util.Optional.of(stepOutcomeObserver.get())
            : java.util.Optional.empty());
  }
}
