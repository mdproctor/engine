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
import io.casehub.api.spi.ContextDiffStrategy;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.channel.DataChannelRegistry;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.scheduler.JobScheduler;
import io.casehub.engine.internal.engine.SignalSettlementTracker;
import io.casehub.engine.internal.engine.handler.AgentRoutingEscalationHandler;
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
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.casehub.platform.api.routing.StrategyResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Produces;
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
}
