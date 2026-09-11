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
package io.casehub.engine.common.quarkus;

import io.casehub.api.context.ContextBridge;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.spi.DataRefResolver;
import io.casehub.api.spi.WorkerContextProvider;
import io.casehub.api.spi.WorkerStatusListener;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.channel.CustomJqProjection;
import io.casehub.engine.common.internal.channel.DataChannelRegistry;
import io.casehub.engine.common.internal.channel.DualWriteProjection;
import io.casehub.engine.common.internal.channel.ExchangeOnlyProjection;
import io.casehub.engine.common.internal.channel.ExchangeSerializer;
import io.casehub.engine.common.internal.channel.FullProjection;
import io.casehub.engine.common.internal.channel.InMemoryDataChannelFactory;
import io.casehub.engine.common.internal.config.ConfigManager;
import io.casehub.engine.common.internal.config.NoOpConfigManager;
import io.casehub.engine.common.internal.config.NoOpSecretManager;
import io.casehub.engine.common.internal.config.SecretManager;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.context.DataRefRegistry;
import io.casehub.engine.common.internal.executor.MilestoneSLAOrchestrator;
import io.casehub.engine.common.internal.executor.RetryOrchestrator;
import io.casehub.engine.common.internal.executor.ScheduledTriggerOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerExecutionConfig;
import io.casehub.engine.common.internal.executor.WorkerExecutionOrchestrator;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.internal.monitoring.ExpectedEffectResolver;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.qualifier.CrossTenant;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.JudgmentScheduler;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.common.spi.recovery.CompoundLockRegistry;
import io.casehub.engine.common.spi.recovery.RecoveryCoordinator;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.engine.plan.execution.InMemoryExecutionSnapshotStore;
import io.casehub.engine.plan.execution.InMemoryPlanVersionStore;
import io.casehub.engine.plan.execution.NoOpCasePlanModelSnapshotProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class CommonBeans {

  // --- No-arg @ApplicationScoped POJOs ---

  @Produces
  @ApplicationScoped
  CompoundLockRegistry compoundLockRegistry() {
    return new CompoundLockRegistry();
  }

  @Produces
  @ApplicationScoped
  DataChannelRegistry dataChannelRegistry() {
    return new DataChannelRegistry();
  }

  @Produces
  @ApplicationScoped
  ScopedWorkerRegistry scopedWorkerRegistry() {
    return new ScopedWorkerRegistry();
  }

  @Produces
  @ApplicationScoped
  FullProjection fullProjection() {
    return new FullProjection();
  }

  @Produces
  @ApplicationScoped
  ExchangeOnlyProjection exchangeOnlyProjection() {
    return new ExchangeOnlyProjection();
  }

  @Produces
  @ApplicationScoped
  ExpectedEffectResolver expectedEffectResolver() {
    return new ExpectedEffectResolver();
  }

  // --- @DefaultBean no-op / in-memory implementations ---

  @Produces
  @DefaultBean
  NoOpSecretManager noOpSecretManager() {
    return new NoOpSecretManager();
  }

  @Produces
  @DefaultBean
  NoOpConfigManager noOpConfigManager() {
    return new NoOpConfigManager();
  }

  @Produces
  @DefaultBean
  DualWriteProjection dualWriteProjection() {
    return new DualWriteProjection();
  }

  @Produces
  @DefaultBean
  NoOpCasePlanModelSnapshotProvider noOpCasePlanModelSnapshotProvider() {
    return new NoOpCasePlanModelSnapshotProvider();
  }

  @Produces
  @DefaultBean
  InMemoryPlanVersionStore inMemoryPlanVersionStore() {
    return new InMemoryPlanVersionStore();
  }

  @Produces
  @DefaultBean
  InMemoryExecutionSnapshotStore inMemoryExecutionSnapshotStore() {
    return new InMemoryExecutionSnapshotStore();
  }

  @Produces
  @DefaultBean
  InMemoryDataChannelFactory inMemoryDataChannelFactory(
      @ConfigProperty(name = "casehub.engine.channel.send-timeout-ms", defaultValue = "0")
          long sendTimeoutMs) {
    return new InMemoryDataChannelFactory(sendTimeoutMs);
  }

  // --- Config-driven POJOs ---

  @Produces
  @ApplicationScoped
  WorkerExecutionConfig workerExecutionConfig(
      @ConfigProperty(name = "casehub.engine.worker.default-timeout-ms", defaultValue = "60000")
          int defaultTimeoutMs) {
    return new WorkerExecutionConfig(defaultTimeoutMs);
  }

  // --- Simple constructor-injected POJOs ---

  @Produces
  @ApplicationScoped
  JQEvaluator jqEvaluator(SecretManager secretManager, ConfigManager configManager) {
    return new JQEvaluator(secretManager, configManager);
  }

  @Produces
  @ApplicationScoped
  CustomJqProjection customJqProjection(JQEvaluator jqEvaluator) {
    return new CustomJqProjection(jqEvaluator);
  }

  @Produces
  @ApplicationScoped
  ExchangeSerializer exchangeSerializer(
      BridgeResolver bridgeResolver, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    return new ExchangeSerializer(bridgeResolver, objectMapper);
  }

  // --- Instance<T> → List<T> / Optional<T> bridging ---

  @Produces
  @ApplicationScoped
  DataRefRegistry dataRefRegistry(Instance<DataRefResolver> resolvers) {
    return new DataRefRegistry(resolvers.stream().toList());
  }

  @Produces
  @ApplicationScoped
  BridgeResolver bridgeResolver(
      Instance<ContextBridge<?>> bridges, DataRefRegistry dataRefRegistry) {
    return new BridgeResolver(bridges.stream().toList(), dataRefRegistry);
  }

  @Produces
  @ApplicationScoped
  JudgmentNodeExecutor judgmentNodeExecutor(Instance<JudgmentScheduler> scheduler) {
    return new JudgmentNodeExecutor(
        scheduler.isResolvable() ? Optional.of(scheduler.get()) : Optional.empty());
  }

  // --- EventBus → EventDispatcher bridging ---

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
      ExpressionEngineRegistry expressionEngineRegistry,
      EventDispatcher eventDispatcher) {
    return new ScheduledTriggerOrchestrator(
        caseDefinitionRegistry,
        recoveryService,
        scopedWorkerRegistry,
        expressionEngineRegistry,
        eventDispatcher);
  }

  // --- Complex: EventBus + Event<T> + @CrossTenant bridging ---

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
      io.casehub.ledger.api.spi.LedgerTraceIdProvider traceIdProvider) {
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
}
