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
package io.casehub.engine.common.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.engine.common.internal.event.ContextSignalEvent;
import io.casehub.engine.common.internal.event.WorkerScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScheduledTriggerOrchestratorTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final String BINDING_NAME = "testBinding";
  private static final String CAPABILITY_NAME = "testCapability";
  private static final String WORKER_NAME = "testWorker";

  private StubRecoveryService recoveryService;
  private StubDefinitionRegistry definitionRegistry;
  private StubExpressionEngine expressionEngine;
  private RecordingEventBus recordingEventBus;
  private ScopedWorkerRegistry scopedWorkerRegistry;
  private ScheduledTriggerOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    recoveryService = new StubRecoveryService();
    definitionRegistry = new StubDefinitionRegistry();
    expressionEngine = new StubExpressionEngine();
    recordingEventBus = new RecordingEventBus();
    scopedWorkerRegistry = new ScopedWorkerRegistry();

    orchestrator =
        new ScheduledTriggerOrchestrator(
            definitionRegistry,
            recoveryService,
            scopedWorkerRegistry,
            expressionEngine,
            recordingEventBus.eventBus);
  }

  @Test
  void unconditionalTriggerPublishesWorkerScheduleEvent() {
    CaseInstance instance = runningCase();
    recoveryService.instance = instance;
    definitionRegistry.definition = definitionWith(WORKER_NAME, CAPABILITY_NAME, BINDING_NAME);

    orchestrator.executeUnconditionalTrigger(triggerData());

    assertThat(recordingEventBus.publishedMessages).hasSize(1);
    var msg = recordingEventBus.publishedMessages.get(0);
    assertThat(msg.address)
        .isEqualTo(io.casehub.engine.common.internal.event.EventBusAddresses.WORKER_SCHEDULE);
    assertThat(msg.body).isInstanceOf(WorkerScheduleEvent.class);
  }

  @Test
  void unconditionalTriggerSkipsNonRunningCase() {
    CaseInstance instance = caseWithStatus(CaseStatus.COMPLETED);
    recoveryService.instance = instance;

    orchestrator.executeUnconditionalTrigger(triggerData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void unconditionalTriggerSkipsWhenCaseNotFound() {
    recoveryService.throwOnLoad = true;

    orchestrator.executeUnconditionalTrigger(triggerData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void unconditionalTriggerThrowsWhenDefinitionNotFound() {
    recoveryService.instance = runningCase();
    definitionRegistry.definition = null;

    assertThatThrownBy(() -> orchestrator.executeUnconditionalTrigger(triggerData()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CaseDefinition not found");
  }

  @Test
  void unconditionalTriggerThrowsWhenWorkerNotFound() {
    recoveryService.instance = runningCase();
    definitionRegistry.definition = definitionWith("otherWorker", CAPABILITY_NAME, BINDING_NAME);

    assertThatThrownBy(() -> orchestrator.executeUnconditionalTrigger(triggerData()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Worker not found");
  }

  @Test
  void conditionalTriggerPublishesWhenConditionMet() {
    recoveryService.instance = runningCase();
    definitionRegistry.definition =
        definitionWithCondition(WORKER_NAME, CAPABILITY_NAME, BINDING_NAME);
    expressionEngine.result = true;

    orchestrator.executeConditionalTrigger(triggerData());

    assertThat(recordingEventBus.publishedMessages).hasSize(1);
  }

  @Test
  void conditionalTriggerSkipsWhenConditionNotMet() {
    recoveryService.instance = runningCase();
    definitionRegistry.definition =
        definitionWithCondition(WORKER_NAME, CAPABILITY_NAME, BINDING_NAME);
    expressionEngine.result = false;

    orchestrator.executeConditionalTrigger(triggerData());

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void signalTriggerPublishesContextSignalEvent() {
    recoveryService.instance = runningCase();

    var data = new ScheduledSignalData(CASE_ID, BINDING_NAME, "{\"key\":\"value\"}", false);
    orchestrator.executeSignalTrigger(data);

    assertThat(recordingEventBus.publishedMessages).hasSize(1);
    var msg = recordingEventBus.publishedMessages.get(0);
    assertThat(msg.address)
        .isEqualTo(io.casehub.engine.common.internal.event.EventBusAddresses.CONTEXT_SIGNAL);
    assertThat(msg.body).isInstanceOf(ContextSignalEvent.class);
  }

  @Test
  void signalTriggerWithConditionNotMetSkips() {
    recoveryService.instance = runningCase();
    definitionRegistry.definition =
        definitionWithCondition(WORKER_NAME, CAPABILITY_NAME, BINDING_NAME);
    expressionEngine.result = false;

    var data = new ScheduledSignalData(CASE_ID, BINDING_NAME, "{}", true);
    orchestrator.executeSignalTrigger(data);

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  @Test
  void signalTriggerSkipsNonRunningCase() {
    recoveryService.instance = caseWithStatus(CaseStatus.FAULTED);

    var data = new ScheduledSignalData(CASE_ID, BINDING_NAME, "{}", false);
    orchestrator.executeSignalTrigger(data);

    assertThat(recordingEventBus.publishedMessages).isEmpty();
  }

  // --- helpers ---

  private ScheduledTriggerData triggerData() {
    return new ScheduledTriggerData(CASE_ID, BINDING_NAME, CAPABILITY_NAME, WORKER_NAME);
  }

  private CaseInstance runningCase() {
    return caseWithStatus(CaseStatus.RUNNING);
  }

  private CaseInstance caseWithStatus(CaseStatus status) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(CASE_ID);
    instance.setState(status);
    CaseMetaModel meta = new CaseMetaModel();
    meta.setName("test");
    meta.setNamespace("ns");
    meta.setVersion("1.0");
    instance.setCaseMetaModel(meta);
    return instance;
  }

  private CaseDefinition definitionWith(
      String workerName, String capabilityName, String bindingName) {
    Capability cap = Capability.of(capabilityName, ".", ".");
    Worker worker =
        Worker.builder().name(workerName).capabilityName(capabilityName).noFunction().build();
    Binding binding =
        Binding.builder()
            .name(bindingName)
            .target(new CapabilityTarget(cap))
            .on(new ContextChangeTrigger(".always"))
            .build();
    return CaseDefinition.builder()
        .name("test")
        .namespace("ns")
        .version("1.0")
        .capabilities(cap)
        .workers(worker)
        .bindings(binding)
        .build();
  }

  private CaseDefinition definitionWithCondition(
      String workerName, String capabilityName, String bindingName) {
    Capability cap = Capability.of(capabilityName, ".", ".");
    Worker worker =
        Worker.builder().name(workerName).capabilityName(capabilityName).noFunction().build();
    Binding binding =
        Binding.builder()
            .name(bindingName)
            .target(new CapabilityTarget(cap))
            .on(new ContextChangeTrigger(".always"))
            .when(new StubCondition())
            .build();
    return CaseDefinition.builder()
        .name("test")
        .namespace("ns")
        .version("1.0")
        .capabilities(cap)
        .workers(worker)
        .bindings(binding)
        .build();
  }

  // --- test doubles ---

  static class StubRecoveryService implements WorkerExecutionRecoveryService {
    CaseInstance instance;
    boolean throwOnLoad;

    @Override
    public CaseInstance loadOrRestoreCaseInstance(UUID caseId) {
      if (throwOnLoad) throw new RuntimeException("Case not found");
      return instance;
    }

    @Override
    public void recoverPendingScheduledWorkers() {}
  }

  static class StubDefinitionRegistry implements CaseDefinitionRegistry {
    CaseDefinition definition;

    @Override
    public CaseDefinition getCaseDefinition(CaseMetaModel metaModel) {
      return definition;
    }

    @Override
    public CaseMetaModel registerCaseDefinition(CaseDefinition model) {
      return null;
    }

    @Override
    public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) {
      return null;
    }
  }

  static class StubCondition implements ExpressionEvaluator {
    @Override
    public String type() {
      return "stub";
    }
  }

  static class StubExpressionEngine implements ExpressionEngineRegistry {
    boolean result;

    @Override
    public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
      return result;
    }

    @Override
    public boolean evaluate(
        ExpressionEvaluator evaluator, com.fasterxml.jackson.databind.JsonNode asNode) {
      return result;
    }

    @Override
    public void validate(ExpressionEvaluator evaluator) {}

    @Override
    public ExpressionEvaluator create(String expression, String expressionLang) {
      return null;
    }

    @Override
    public void assertLanguageSupported(String expressionLang) {}

    @Override
    public java.util.List<com.fasterxml.jackson.databind.JsonNode> transform(
        ExpressionEvaluator evaluator, com.fasterxml.jackson.databind.JsonNode input) {
      return java.util.List.of();
    }

    @Override
    public Optional<String> extractString(ExpressionEvaluator evaluator, CaseContext context) {
      return Optional.empty();
    }
  }

  record PublishedMessage(String address, Object body) {}

  static class RecordingEventBus {
    final List<PublishedMessage> publishedMessages = new ArrayList<>();
    final EventBus eventBus;

    RecordingEventBus() {
      this.eventBus =
          new EventBus(null) {
            @Override
            public io.vertx.mutiny.core.eventbus.EventBus publish(String address, Object body) {
              publishedMessages.add(new PublishedMessage(address, body));
              return this;
            }
          };
    }
  }
}
