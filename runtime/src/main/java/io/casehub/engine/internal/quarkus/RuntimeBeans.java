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
import io.casehub.engine.internal.milestone.MilestoneLifecycleManager;
import io.casehub.engine.internal.recovery.CaseRecoveryStateRegistry;
import io.casehub.engine.internal.routing.SelectionContextStore;
import io.casehub.engine.internal.scheduler.SchedulerService;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
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
}
