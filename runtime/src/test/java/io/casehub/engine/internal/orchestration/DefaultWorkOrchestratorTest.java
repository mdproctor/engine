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
package io.casehub.engine.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.EscalationReason;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.CapabilityHealth;
import io.casehub.eidos.api.CapabilityHealth.CapabilityStatus;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkOrchestratorTest {

  private AgentRoutingStrategy agentRoutingStrategy;
  private WorkerExecutionManager executionManager;
  private CapabilityHealth capabilityHealth;
  private EventBus eventBus;
  private PendingWorkRegistry registry;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private CaseInstanceRepository caseInstanceRepository;
  private EventLogRepository eventLogRepository;
  private JQEvaluator jqEvaluator;
  private DefaultWorkOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    agentRoutingStrategy = mock(AgentRoutingStrategy.class);
    executionManager = mock(WorkerExecutionManager.class);
    capabilityHealth = mock(CapabilityHealth.class);
    eventBus = mock(EventBus.class);
    registry = new PendingWorkRegistry();
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceRepository = mock(CaseInstanceRepository.class);
    eventLogRepository = mock(EventLogRepository.class);
    jqEvaluator = mock(JQEvaluator.class);

    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityHealth.CapabilityStatus.Ready());
    when(executionManager.getActiveWorkCount(any())).thenReturn(0);
    when(caseInstanceRepository.updateStateAndAppendEvent(any(), any(), any()))
        .thenReturn(Uni.createFrom().voidItem());
    when(eventLogRepository.appendAndReturnId(any(), any())).thenReturn(Uni.createFrom().item(1L));
    when(jqEvaluator.eval(any(), any()))
        .thenReturn(
            ValidationResult.ok(
                List.of(
                    (com.fasterxml.jackson.databind.JsonNode)
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())));

    orchestrator =
        new DefaultWorkOrchestrator(
            agentRoutingStrategy,
            executionManager,
            capabilityHealth,
            eventBus,
            registry,
            caseDefinitionRegistry,
            caseInstanceRepository,
            eventLogRepository,
            jqEvaluator);
  }

  // ---- happy path -----------------------------------------------------------

  @Test
  void submit_workerSelected_publishesScheduleEvent() {
    final CaseInstance instance = runningInstance("analyse");
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of("doc", "x")));

    verify(eventBus).publish(any(), any(WorkerScheduleEvent.class));
  }

  @Test
  void submit_workerSelected_returnsPendingFuture() {
    final CaseInstance instance = runningInstance("analyse");
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    final CompletableFuture<WorkResult> future =
        orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    assertThat(future.isDone()).isFalse();
  }

  @Test
  void submitAndWait_transitionsCaseToWaiting() {
    final CaseInstance instance = runningInstance("analyse");
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    orchestrator.submitAndWait(instance, WorkRequest.of("analyse", Map.of("doc", "x")));

    assertThat(instance.getState()).isEqualTo(CaseStatus.WAITING);
    assertThat(instance.getWaitingForWorkId()).isNotNull();
  }

  // ---- robustness -----------------------------------------------------------

  @Test
  void submit_strategyReturnsUnresolvable_failsFuture() {
    final CaseInstance instance = runningInstance("analyse");
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.unresolvable()));

    final var future =
        orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(eventBus, never()).publish(any(), any(WorkerScheduleEvent.class));
  }

  @Test
  void submit_strategyReturnsEscalate_failsFutureAndPublishesEscalation() {
    final CaseInstance instance = runningInstance("analyse");
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(
            Uni.createFrom()
                .item(AgentAssignment.escalate("analyse", EscalationReason.BORDERLINE_STALEMATE)));

    final var future =
        orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(eventBus, never()).publish(any(), any(WorkerScheduleEvent.class));
    verify(eventBus)
        .publish(
            org.mockito.ArgumentMatchers.eq(
                io.casehub.engine.common.internal.event.EventBusAddresses.AGENT_ROUTING_ESCALATION),
            any(io.casehub.engine.common.internal.event.AgentRoutingEscalationEvent.class));
  }

  @Test
  void submit_unknownCapability_failsFuture() {
    final CaseInstance instance = runningInstance("analyse");

    final var future =
        orchestrator
            .submit(instance, WorkRequest.of("unknown-capability", Map.of()))
            .toCompletableFuture();

    assertThat(future.isCompletedExceptionally()).isTrue();
  }

  // ---- capability health probe -----------------------------------------------

  private static final AgentDescriptor AGENT_DESCRIPTOR =
      new AgentDescriptor(
          "agent-1",
          "TestAgent",
          "1.0",
          "openai",
          "gpt-4",
          "4-turbo",
          null,
          null,
          null,
          null,
          null,
          "review",
          List.of(),
          null,
          null,
          null,
          "casehubio",
          null);

  @Test
  @SuppressWarnings("unchecked")
  void probe_unavailable_workerExcludedFromCandidates() {
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityStatus.Unavailable("model offline"));
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.unresolvable()));

    final CaseInstance instance = runningInstanceWithAgentWorker("analyse");
    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    final org.mockito.ArgumentCaptor<List<AgentCandidate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(agentRoutingStrategy).select(any(), captor.capture());
    assertThat(captor.getValue()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void probe_epistemicallyWeak_workerKeptWithWeakHealth() {
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityStatus.EpistemicallyWeak("rust", 0.25));
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("agent-worker")));

    final CaseInstance instance = runningInstanceWithAgentWorker("analyse");
    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of()));

    final org.mockito.ArgumentCaptor<List<AgentCandidate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(agentRoutingStrategy).select(any(), captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    assertThat(captor.getValue().get(0).workerId()).isEqualTo("agent-worker");
    assertThat(captor.getValue().get(0).health())
        .isEqualTo(io.casehub.api.spi.routing.AgentHealth.EPISTEMICALLY_WEAK);
  }

  @Test
  @SuppressWarnings("unchecked")
  void probe_allUnavailable_emptyCandidateListPassedToStrategy() {
    when(capabilityHealth.probe(any(), any(), any()))
        .thenReturn(new CapabilityStatus.Unavailable("all offline"));
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.unresolvable()));

    final CaseInstance instance = runningInstanceWithAgentWorker("analyse");
    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    final org.mockito.ArgumentCaptor<List<AgentCandidate>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(agentRoutingStrategy).select(any(), captor.capture());
    assertThat(captor.getValue()).isEmpty();
  }

  @Test
  void probe_noDescriptor_probeSkipped_workerUsesReadyHealth() {
    when(agentRoutingStrategy.select(any(), any()))
        .thenReturn(Uni.createFrom().item(AgentAssignment.assign("analyst-worker")));

    final CaseInstance instance = runningInstance("analyse");
    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of()));

    verify(capabilityHealth, never()).probe(any(), any(), any());
  }

  // ---- helper ---------------------------------------------------------------

  private CaseInstance runningInstanceWithAgentWorker(final String capabilityName) {
    final Capability capability =
        Capability.builder()
            .name(capabilityName)
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ result: .result }")
            .build();

    final Worker worker =
        Worker.builder()
            .name("agent-worker")
            .capabilities(capability)
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", "done"))))
            .build();

    return buildInstance(capabilityName, worker, AGENT_DESCRIPTOR);
  }

  private CaseInstance runningInstance(final String capabilityName) {
    final Capability capability =
        Capability.builder()
            .name(capabilityName)
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ result: .result }")
            .build();

    final Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilities(capability)
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("result", "done"))))
            .build();

    return buildInstance(capabilityName, worker);
  }

  private CaseInstance buildInstance(final String capabilityName, final Worker worker) {
    return buildInstance(capabilityName, worker, null);
  }

  private CaseInstance buildInstance(
      final String capabilityName, final Worker worker, final AgentDescriptor descriptor) {
    final Capability capability =
        Capability.builder()
            .name(capabilityName)
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ result: .result }")
            .build();

    final CaseDefinition.Builder defBuilder =
        CaseDefinition.builder()
            .namespace("test-orch")
            .name("Orchestration Test Case")
            .version("1.0.0")
            .capabilities(capability)
            .workers(worker);
    if (descriptor != null) {
      defBuilder.agentDescriptor(worker.name(), descriptor);
    }
    final CaseDefinition definition = defBuilder.build();

    final CaseMetaModel metaModel = mock(CaseMetaModel.class);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    final CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseMetaModel(metaModel);
    final CaseContext ctx = mock(CaseContext.class);
    final com.fasterxml.jackson.databind.node.ObjectNode emptyNode =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    final io.casehub.api.context.ReadablePanel workingPanel =
        mock(io.casehub.api.context.ReadablePanel.class);
    when(workingPanel.asJsonNode()).thenReturn(emptyNode);
    when(ctx.panel(io.casehub.api.context.ContextPanel.WORKING)).thenReturn(workingPanel);
    when(ctx.asJsonNode()).thenReturn(emptyNode);
    instance.setCaseContext(ctx);
    return instance;
  }
}
