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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.Worker;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
import io.casehub.engine.internal.event.WorkerScheduleEvent;
import io.casehub.engine.internal.model.CaseInstance;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.casehub.engine.spi.CaseDefinitionRegistry;
import io.casehub.engine.spi.CaseInstanceRepository;
import io.casehub.engine.spi.EventLogRepository;
import io.casehub.engine.spi.cache.CaseInstanceCache;
import io.casehub.platform.expression.JQEvaluator;
import io.casehub.platform.expression.ValidationResult;
import io.casehub.work.api.AssignmentDecision;
import io.casehub.work.api.WorkloadProvider;
import io.casehub.work.core.strategy.LeastLoadedStrategy;
import io.casehub.work.core.strategy.WorkBroker;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkOrchestratorTest {

  private WorkBroker workBroker;
  private LeastLoadedStrategy strategy;
  private WorkloadProvider workloadProvider;
  private EventBus eventBus;
  private PendingWorkRegistry registry;
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private CaseInstanceRepository caseInstanceRepository;
  private EventLogRepository eventLogRepository;
  private CaseInstanceCache cache;
  private JQEvaluator jqEvaluator;
  private WorkOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    workBroker = mock(WorkBroker.class);
    strategy = mock(LeastLoadedStrategy.class);
    workloadProvider = mock(WorkloadProvider.class);
    eventBus = mock(EventBus.class);
    registry = new PendingWorkRegistry();
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    caseInstanceRepository = mock(CaseInstanceRepository.class);
    eventLogRepository = mock(EventLogRepository.class);
    cache = new CaseInstanceCacheImpl();
    jqEvaluator = mock(JQEvaluator.class);

    when(workloadProvider.getActiveWorkCount(any())).thenReturn(0);
    when(caseInstanceRepository.updateStateAndAppendEvent(any(), any()))
        .thenReturn(Uni.createFrom().voidItem());
    when(eventLogRepository.appendAndReturnId(any())).thenReturn(Uni.createFrom().item(1L));
    when(jqEvaluator.eval(any(), any()))
        .thenReturn(
            ValidationResult.ok(
                List.of(
                    (com.fasterxml.jackson.databind.JsonNode)
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())));

    orchestrator =
        new WorkOrchestrator(
            workBroker,
            strategy,
            workloadProvider,
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
    CaseInstance instance = runningInstance("analyse");
    cache.put(instance);
    when(workBroker.apply(any(), any(), any(), any()))
        .thenReturn(AssignmentDecision.assignTo("analyst-worker"));

    orchestrator.submit(instance, WorkRequest.of("analyse", Map.of("doc", "x")));

    verify(eventBus).publish(any(), any(WorkerScheduleEvent.class));
  }

  @Test
  void submit_workerSelected_returnsPendingFuture() {
    CaseInstance instance = runningInstance("analyse");
    cache.put(instance);
    when(workBroker.apply(any(), any(), any(), any()))
        .thenReturn(AssignmentDecision.assignTo("analyst-worker"));

    CompletableFuture<WorkResult> future =
        orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    assertThat(future.isDone()).isFalse();
  }

  @Test
  void submitAndWait_transitionsCaseToWaiting() {
    CaseInstance instance = runningInstance("analyse");
    cache.put(instance);
    when(workBroker.apply(any(), any(), any(), any()))
        .thenReturn(AssignmentDecision.assignTo("analyst-worker"));

    orchestrator.submitAndWait(instance, WorkRequest.of("analyse", Map.of("doc", "x")));

    assertThat(instance.getState()).isEqualTo(CaseStatus.WAITING);
    assertThat(instance.getWaitingForWorkId()).isNotNull();
  }

  // ---- robustness -----------------------------------------------------------

  @Test
  void submit_noCapableWorker_failsFuture() {
    CaseInstance instance = runningInstance("analyse");
    cache.put(instance);
    when(workBroker.apply(any(), any(), any(), any())).thenReturn(AssignmentDecision.noChange());

    var future =
        orchestrator.submit(instance, WorkRequest.of("analyse", Map.of())).toCompletableFuture();

    assertThat(future.isCompletedExceptionally()).isTrue();
    verify(eventBus, never()).publish(any(), any());
  }

  @Test
  void submit_unknownCapability_throwsIllegalArgument() {
    CaseInstance instance = runningInstance("analyse");
    cache.put(instance);

    var future =
        orchestrator
            .submit(instance, WorkRequest.of("unknown-capability", Map.of()))
            .toCompletableFuture();

    assertThat(future.isCompletedExceptionally()).isTrue();
  }

  // ---- helper ---------------------------------------------------------------

  private CaseInstance runningInstance(String capabilityName) {
    Capability capability =
        Capability.builder()
            .name(capabilityName)
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ result: .result }")
            .build();

    Worker worker =
        Worker.builder()
            .name("analyst-worker")
            .capabilities(capability)
            .function(input -> Map.of("result", "done"))
            .build();

    CaseDefinition definition =
        CaseDefinition.builder()
            .namespace("test-orch")
            .name("Orchestration Test Case")
            .version("1.0.0")
            .capabilities(capability)
            .workers(worker)
            .build();

    CaseMetaModel metaModel = mock(CaseMetaModel.class);
    when(caseDefinitionRegistry.getCaseDefinition(metaModel)).thenReturn(definition);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setState(CaseStatus.RUNNING);
    instance.setCaseMetaModel(metaModel);
    CaseContext ctx = mock(CaseContext.class);
    when(ctx.asJsonNode())
        .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
    instance.setCaseContext(ctx);
    return instance;
  }
}
