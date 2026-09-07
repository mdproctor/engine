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
package io.casehub.engine.internal.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;

import io.casehub.api.spi.StepOutcomeEvent;
import io.casehub.api.spi.StepOutcomeObserver;
import io.casehub.api.spi.routing.RoutingOutcome;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that StepOutcomeObserver.onStepOutcome() is called after each worker execution. Refs
 * casehubio/engine#1050.
 */
@QuarkusTest
class StepOutcomeObserverTest {

  @Inject WorkflowExecutionCompletedHandler handler;
  @Inject StepCapturingObserver captureObserver;
  @Inject CaseDefinitionRegistry registry;

  @BeforeEach
  void reset() {
    StepCapturingObserver.capturedEvents.clear();
    StepCapturingObserver.shouldThrow = false;
  }

  @Test
  void stepOutcomeObserver_called_on_success() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("step-test")
            .version("1.0.0")
            .capability(new Capability("analysis", ".", ".", ""))
            .binding(
                Binding.builder()
                    .name("analyse")
                    .capability(new Capability("analysis", ".", ".", ""))
                    .on("true")
                    .build())
            .build();
    registry.registerCaseDefinition(def);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("step-test");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("volatility", 0.85, "hour", 2)));
    instance.tenancyId = "test-tenant";

    Worker worker =
        Worker.builder().name("momentum-agent").capabilityName("analysis").noFunction().build();

    handler.onWorkflowExecutionCompletedHandler(
        new WorkflowExecutionCompleted(
            instance, worker, "idem-1", Map.of("action", "reduce"), "analyse",
            WorkerOutcome.success()));

    assertThat(StepCapturingObserver.capturedEvents)
        .as("StepOutcomeObserver must fire on worker success — engine#1050")
        .hasSize(1);

    StepOutcomeEvent event = StepCapturingObserver.capturedEvents.get(0);
    assertThat(event.caseId()).isEqualTo(instance.getUuid());
    assertThat(event.tenancyId()).isEqualTo("test-tenant");
    assertThat(event.caseType()).isEqualTo("step-test");
    assertThat(event.bindingName()).isEqualTo("analyse");
    assertThat(event.capabilityName()).isEqualTo("analysis");
    assertThat(event.workerName()).isEqualTo("momentum-agent");
    assertThat(event.outcome()).isEqualTo(RoutingOutcome.SUCCESS);
    assertThat(event.contextSnapshot()).containsEntry("volatility", 0.85);
    assertThat(event.contextSnapshot()).containsEntry("hour", 2);
  }

  @Test
  void stepOutcomeObserver_called_on_failure() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("step-fail-test")
            .version("1.0.0")
            .capability(new Capability("assessment", ".", ".", ""))
            .binding(
                Binding.builder()
                    .name("assess")
                    .capability(new Capability("assessment", ".", ".", ""))
                    .on("true")
                    .build())
            .build();
    registry.registerCaseDefinition(def);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("step-fail-test");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("error_state", true)));
    instance.tenancyId = "test-tenant";

    Worker worker =
        Worker.builder().name("risk-agent").capabilityName("assessment").noFunction().build();

    handler.onWorkflowExecutionCompletedHandler(
        new WorkflowExecutionCompleted(
            instance, worker, "idem-2", null, "assess",
            new WorkerOutcome.Declined<>("insufficient data")));

    assertThat(StepCapturingObserver.capturedEvents)
        .as("StepOutcomeObserver must fire on worker failure — engine#1050")
        .isNotEmpty();

    StepOutcomeEvent event = StepCapturingObserver.capturedEvents.get(0);
    assertThat(event.outcome()).isEqualTo(RoutingOutcome.FAILURE);
    assertThat(event.contextSnapshot()).containsEntry("error_state", true);
  }

  @Test
  void stepOutcomeObserver_exception_does_not_block_case_progression() {
    StepCapturingObserver.shouldThrow = true;

    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("step-err-test")
            .version("1.0.0")
            .capability(new Capability("checking", ".", ".", ""))
            .binding(
                Binding.builder()
                    .name("check")
                    .capability(new Capability("checking", ".", ".", ""))
                    .on("true")
                    .build())
            .build();
    registry.registerCaseDefinition(def);

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName("step-err-test");
    metaModel.setNamespace("test");
    metaModel.setVersion("1.0.0");

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.setCaseMetaModel(metaModel);
    instance.setCaseContext(new CaseContextImpl(Map.of("val", 1)));
    instance.tenancyId = "test-tenant";

    Worker worker =
        Worker.builder().name("checker").capabilityName("checking").noFunction().build();

    // Should not throw — exception is caught and logged
    handler.onWorkflowExecutionCompletedHandler(
        new WorkflowExecutionCompleted(
            instance, worker, "idem-3", Map.of("ok", true), "check",
            WorkerOutcome.success()));
  }

  @ApplicationScoped
  static class StepCapturingObserver implements StepOutcomeObserver {
    static final CopyOnWriteArrayList<StepOutcomeEvent> capturedEvents =
        new CopyOnWriteArrayList<>();
    static volatile boolean shouldThrow = false;

    @Override
    public void onStepOutcome(StepOutcomeEvent event) {
      if (shouldThrow) {
        throw new RuntimeException("Deliberate test exception");
      }
      capturedEvents.add(event);
    }
  }
}
