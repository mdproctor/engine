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
package io.casehub.engine.support.spring;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.casehub.actorstate.ActorStateAggregator;
import io.casehub.actorstate.ActorStateResource;
import io.casehub.actorstate.EngineActorStateContributor;
import io.casehub.actorstate.LedgerActorStateContributor;
import io.casehub.actorstate.QhorusActorStateContributor;
import io.casehub.actorstate.WorkActorStateContributor;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.a2a.A2ACapabilityHealth;
import io.casehub.engine.a2a.A2AClientRegistry;
import io.casehub.engine.a2a.A2AEndpointRegistry;
import io.casehub.engine.a2a.A2AWorkerFunctionHandler;
import io.casehub.engine.a2a.A2AWorkerFunctionProvider;
import io.casehub.engine.ai.provider.LangChain4jAgentEmbeddingProvider;
import io.casehub.engine.ai.routing.EmbeddingCache;
import io.casehub.engine.ai.routing.SemanticSignalProvider;
import io.casehub.engine.ai.spi.AgentEmbeddingProvider;
import io.casehub.engine.common.internal.context.BridgeResolver;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.judgment.JudgmentNodeExecutor;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.HumanTaskScheduler;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.WorkOrchestrator;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.flow.CallableDispatchRegistry;
import io.casehub.engine.flow.CasehubDispatch;
import io.casehub.engine.flow.CasehubJudgment;
import io.casehub.engine.flow.FlowExecutionRegistry;
import io.casehub.engine.flow.FlowWorkerFunctionHandler;
import io.casehub.engine.flow.FlowWorkerFunctionProvider;
import io.casehub.engine.inbound.InboundSignalBridge;
import io.casehub.engine.inbound.InboundWorkItemBridge;
import io.casehub.engine.inbound.InboundWorkItemPolicy;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.engine.mcp.McpCapabilityHealth;
import io.casehub.engine.mcp.McpClientRegistry;
import io.casehub.engine.mcp.McpEndpointRegistry;
import io.casehub.engine.mcp.McpWorkerFunctionHandler;
import io.casehub.engine.mcp.McpWorkerFunctionProvider;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.engine.queue.entry.CaseQueueEntryManager;
import io.casehub.engine.queue.label.CaseLabelEvaluator;
import io.casehub.engine.queue.reconcile.CaseLabelReconciler;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import io.casehub.engine.queue.store.InMemoryCaseQueueEntryStore;
import io.casehub.engine.queue.view.CaseQueueViewManager;
import io.casehub.engine.react.ReActCycleEventHandler;
import io.casehub.engine.react.ReActWorkerFunctionHandler;
import io.casehub.engine.react.ReActWorkerFunctionProvider;
import io.casehub.engine.work.cloudevent.CloudEventActionGateScheduler;
import io.casehub.engine.work.cloudevent.CloudEventHumanTaskScheduler;
import io.casehub.engine.work.cloudevent.CloudEventJudgmentScheduler;
import io.casehub.engine.work.cloudevent.WorkIntegrationConflictDetector;
import io.casehub.engine.work.cloudevent.WorkItemLifecycleCloudEventConsumer;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.persistence.memory.DefaultTestPrincipal;
import io.casehub.persistence.memory.InMemoryCaseInstanceRepository;
import io.casehub.persistence.memory.InMemoryCaseMetaModelRepository;
import io.casehub.persistence.memory.InMemoryEventLogRepository;
import io.casehub.persistence.memory.InMemoryPlanItemStore;
import io.casehub.persistence.memory.InMemorySubCaseGroupRepository;
import io.casehub.platform.api.actor.ActorStateContributor;
import io.casehub.platform.api.routing.StrategyResolver;
import io.casehub.platform.api.view.CrossTenantSubjectViewStore;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.platform.view.SubjectViewOrchestrator;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.work.api.spi.TenantContextExecutor;
import io.casehub.work.api.spi.WorkItemOperations;
import io.casehub.work.api.spi.WorkItemStore;
import io.serverlessworkflow.impl.WorkflowApplication;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@ConditionalOnClass(CallableDispatchRegistry.class)
public class EngineSupportAutoConfiguration {

  // --- flow ---

  @Bean
  public CallableDispatchRegistry callableDispatchRegistry() {
    return new CallableDispatchRegistry();
  }

  @Bean
  public FlowExecutionRegistry flowExecutionRegistry() {
    return new FlowExecutionRegistry();
  }

  @Bean
  public FlowWorkerFunctionProvider flowWorkerFunctionProvider() {
    return new FlowWorkerFunctionProvider();
  }

  @Bean
  public FlowWorkerFunctionHandler flowWorkerFunctionHandler(
      WorkflowApplication app, FlowExecutionRegistry registry) {
    return new FlowWorkerFunctionHandler(
        app, registry, Executors.newVirtualThreadPerTaskExecutor());
  }

  @Bean
  public CasehubDispatch casehubDispatch(
      FlowExecutionRegistry registry,
      WorkOrchestrator orchestrator,
      EventLogRepository eventLogRepository,
      CaseInstanceCache caseInstanceCache,
      CallableDispatchRegistry dispatchRegistry) {
    CasehubDispatch dispatch =
        new CasehubDispatch(
            registry, orchestrator, eventLogRepository, caseInstanceCache, dispatchRegistry);
    dispatch.register();
    return dispatch;
  }

  @Bean
  public CasehubJudgment casehubJudgment(
      FlowExecutionRegistry executionRegistry,
      CallableDispatchRegistry dispatchRegistry,
      JudgmentNodeExecutor judgmentNodeExecutor,
      CaseInstanceCache caseInstanceCache) {
    CasehubJudgment judgment =
        new CasehubJudgment(
            executionRegistry,
            dispatchRegistry,
            judgmentNodeExecutor,
            caseInstanceCache,
            Executors.newVirtualThreadPerTaskExecutor());
    judgment.register();
    return judgment;
  }

  // --- actor-state ---

  @Bean
  public ActorStateAggregator actorStateAggregator(List<ActorStateContributor> contributors) {
    return new ActorStateAggregator(contributors, Executors.newVirtualThreadPerTaskExecutor());
  }

  @Bean
  public ActorStateResource actorStateResource(ActorStateAggregator aggregator) {
    return new ActorStateResource(aggregator);
  }

  @Bean
  @ConditionalOnBean(WorkerExecutionManager.class)
  public EngineActorStateContributor engineActorStateContributor(
      WorkerExecutionManager executionManager) {
    return new EngineActorStateContributor(executionManager);
  }

  @Bean
  @ConditionalOnBean(TrustGateService.class)
  public LedgerActorStateContributor ledgerActorStateContributor(
      TrustGateService trustGateService) {
    return new LedgerActorStateContributor(trustGateService);
  }

  @Bean
  @ConditionalOnBean(WorkItemStore.class)
  public WorkActorStateContributor workActorStateContributor(WorkItemStore workItemStore) {
    return new WorkActorStateContributor(workItemStore);
  }

  @Bean
  @ConditionalOnBean({CommitmentStore.class, ChannelStore.class})
  public QhorusActorStateContributor qhorusActorStateContributor(
      CommitmentStore commitmentStore, ChannelStore channelStore) {
    return new QhorusActorStateContributor(commitmentStore, channelStore);
  }

  // --- persistence-memory ---

  @Bean
  @ConditionalOnMissingBean
  public DefaultTestPrincipal defaultTestPrincipal() {
    return new DefaultTestPrincipal();
  }

  @Bean
  @Primary
  public InMemoryCaseInstanceRepository inMemoryCaseInstanceRepository(
      EventLogRepository eventLogRepository) {
    return new InMemoryCaseInstanceRepository(eventLogRepository);
  }

  @Bean
  @Primary
  public InMemoryCaseMetaModelRepository inMemoryCaseMetaModelRepository() {
    return new InMemoryCaseMetaModelRepository();
  }

  @Bean
  @Primary
  public InMemoryEventLogRepository inMemoryEventLogRepository() {
    return new InMemoryEventLogRepository();
  }

  @Bean
  @Primary
  public InMemoryPlanItemStore inMemoryPlanItemStore() {
    return new InMemoryPlanItemStore();
  }

  @Bean
  @Primary
  public InMemorySubCaseGroupRepository inMemorySubCaseGroupRepository() {
    return new InMemorySubCaseGroupRepository();
  }

  // --- queue ---

  @Bean
  public CaseQueueViewManager caseQueueViewManager(
      SubjectViewOrchestrator views, SubjectViewStore viewStore) {
    return new CaseQueueViewManager(views, viewStore);
  }

  @Bean
  public InMemoryCaseQueueEntryStore inMemoryCaseQueueEntryStore() {
    return new InMemoryCaseQueueEntryStore();
  }

  @Bean
  public CaseQueueService caseQueueService(
      CaseQueueEntryStore store, ApplicationEventPublisher publisher) {
    return new CaseQueueService(
        store,
        e -> publisher.publishEvent(e),
        e -> publisher.publishEvent(e),
        e -> publisher.publishEvent(e));
  }

  @Bean
  public CaseQueueEntryManager caseQueueEntryManager(
      CaseQueueEntryStore store, ApplicationEventPublisher publisher) {
    return new CaseQueueEntryManager(store, e -> publisher.publishEvent(e));
  }

  @Bean
  public CaseLabelEvaluator caseLabelEvaluator(
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      SubjectViewOrchestrator views,
      ApplicationEventPublisher publisher) {
    return new CaseLabelEvaluator(
        definitionRegistry, caseInstanceRepository, views, e -> publisher.publishEvent(e));
  }

  @Bean
  public CaseLabelReconciler caseLabelReconciler(
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      SubjectViewOrchestrator views,
      CrossTenantSubjectViewStore crossTenantViewStore,
      ApplicationEventPublisher publisher) {
    return new CaseLabelReconciler(
        definitionRegistry,
        caseInstanceRepository,
        views,
        crossTenantViewStore,
        e -> publisher.publishEvent(e));
  }

  // --- a2a ---

  @Bean
  public A2AEndpointRegistry a2aEndpointRegistry() {
    return new A2AEndpointRegistry();
  }

  @Bean
  public A2AClientRegistry a2aClientRegistry() {
    return new A2AClientRegistry();
  }

  @Bean
  public A2AWorkerFunctionProvider a2aWorkerFunctionProvider(A2AEndpointRegistry endpointRegistry) {
    return new A2AWorkerFunctionProvider(endpointRegistry);
  }

  @Bean
  public A2ACapabilityHealth a2aCapabilityHealth(
      A2AEndpointRegistry endpointRegistry, A2AClientRegistry clientRegistry) {
    return new A2ACapabilityHealth(endpointRegistry, clientRegistry);
  }

  @Bean
  public A2AWorkerFunctionHandler a2aWorkerFunctionHandler(
      A2AClientRegistry clientRegistry,
      @Value("${casehub.a2a.max-artifacts:100}") int maxArtifacts,
      @Value("${casehub.a2a.max-artifact-bytes:10485760}") long maxArtifactBytes) {
    return new A2AWorkerFunctionHandler(
        clientRegistry,
        Executors.newVirtualThreadPerTaskExecutor(),
        maxArtifacts,
        maxArtifactBytes);
  }

  // --- mcp ---

  @Bean
  public McpEndpointRegistry mcpEndpointRegistry() {
    return new McpEndpointRegistry();
  }

  @Bean
  public McpClientRegistry mcpClientRegistry() {
    return new McpClientRegistry();
  }

  @Bean
  public McpWorkerFunctionProvider mcpWorkerFunctionProvider(McpEndpointRegistry endpointRegistry) {
    return new McpWorkerFunctionProvider(endpointRegistry);
  }

  @Bean
  public McpCapabilityHealth mcpCapabilityHealth(
      McpEndpointRegistry endpointRegistry, McpClientRegistry clientRegistry) {
    return new McpCapabilityHealth(endpointRegistry, clientRegistry);
  }

  @Bean
  public McpWorkerFunctionHandler mcpWorkerFunctionHandler(McpClientRegistry clientRegistry) {
    return new McpWorkerFunctionHandler(
        clientRegistry, Executors.newVirtualThreadPerTaskExecutor());
  }

  // --- engine-ai ---

  @Bean
  public LangChain4jAgentEmbeddingProvider langChain4jAgentEmbeddingProvider(
      EmbeddingModel embeddingModel) {
    return new LangChain4jAgentEmbeddingProvider(embeddingModel);
  }

  @Bean
  public EmbeddingCache embeddingCache(
      @Value("${casehub.engine.ai.embedding-cache.max-size:500}") int maxSize) {
    return new EmbeddingCache(maxSize);
  }

  @Bean
  public SemanticSignalProvider semanticSignalProvider(
      AgentEmbeddingProvider embeddingProvider,
      EmbeddingCache embeddingCache,
      JQEvaluator jqEvaluator,
      @Value("${casehub.routing.semantic.context-jq:tostring}") String contextSummaryJq) {
    return new SemanticSignalProvider(
        embeddingProvider, embeddingCache, jqEvaluator, contextSummaryJq);
  }

  // --- inbound ---

  @Bean
  public InboundSignalBridge inboundSignalBridge(
      Optional<CaseDefinitionRegistry> registry,
      Optional<CaseHubRuntime> runtime,
      BridgeResolver bridgeResolver,
      StrategyResolver strategyResolver,
      JQEvaluator jqEvaluator) {
    return new InboundSignalBridge(
        registry, runtime, bridgeResolver, strategyResolver, jqEvaluator);
  }

  @Bean
  public InboundWorkItemBridge inboundWorkItemBridge(
      Optional<InboundWorkItemPolicy> policy,
      WorkItemOperations workItemOperations,
      TenantContextExecutor tenantContextExecutor) {
    return new InboundWorkItemBridge(policy, workItemOperations, tenantContextExecutor);
  }

  // --- react ---

  @Bean
  public ReActCycleEventHandler reActCycleEventHandler(EventLogRepository eventLogRepository) {
    return new ReActCycleEventHandler(eventLogRepository);
  }

  @Bean
  public ReActWorkerFunctionProvider reActWorkerFunctionProvider() {
    return new ReActWorkerFunctionProvider();
  }

  @Bean
  public ReActWorkerFunctionHandler reActWorkerFunctionHandler(
      WorkerRuntimeFactory runtimeFactory, EventDispatcher eventDispatcher) {
    return new ReActWorkerFunctionHandler(
        runtimeFactory, eventDispatcher, Executors.newVirtualThreadPerTaskExecutor());
  }

  // --- work-cloudevent ---

  @Bean
  public CloudEventActionGateScheduler cloudEventActionGateScheduler(
      ApplicationEventPublisher publisher) {
    return new CloudEventActionGateScheduler(e -> publisher.publishEvent(e));
  }

  @Bean
  public WorkIntegrationConflictDetector workIntegrationConflictDetector(
      List<HumanTaskScheduler> schedulers) {
    return new WorkIntegrationConflictDetector(schedulers);
  }

  @Bean
  public WorkItemLifecycleCloudEventConsumer workItemLifecycleCloudEventConsumer(
      PlanItemCompletionApplier planItemApplier,
      GateCompletionApplier gateApplier,
      BlackboardRegistry blackboardRegistry) {
    return new WorkItemLifecycleCloudEventConsumer(
        planItemApplier, gateApplier, blackboardRegistry);
  }

  @Bean
  @SuppressWarnings("removal")
  public CloudEventHumanTaskScheduler cloudEventHumanTaskScheduler(
      BlackboardRegistry registry,
      PlanItemStore planItemStore,
      ApplicationEventPublisher publisher) {
    return new CloudEventHumanTaskScheduler(
        registry, planItemStore, e -> publisher.publishEvent(e));
  }

  @Bean
  public CloudEventJudgmentScheduler cloudEventJudgmentScheduler(
      BlackboardRegistry registry,
      PlanItemStore planItemStore,
      ApplicationEventPublisher publisher) {
    return new CloudEventJudgmentScheduler(registry, planItemStore, e -> publisher.publishEvent(e));
  }
}
