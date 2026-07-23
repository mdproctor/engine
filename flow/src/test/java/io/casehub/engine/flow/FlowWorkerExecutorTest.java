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
package io.casehub.engine.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.worker.api.WorkerResult;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowWorkerExecutorTest {

  private WorkflowApplication app;
  private FlowExecutionRegistry registry;
  private FlowWorkerFunctionHandler handler;

  @BeforeEach
  void setUp() {
    app = mock(WorkflowApplication.class);
    registry = mock(FlowExecutionRegistry.class);
    handler =
        new FlowWorkerFunctionHandler(
            app, registry, java.util.concurrent.Executors.newSingleThreadExecutor());
  }

  @Test
  void supports_flow_worker_function() {
    assertThat(handler.supports(new FlowWorkerFunction(new Workflow()))).isTrue();
  }

  @Test
  void execute_registers_before_start_and_removes_on_success() {
    final String instanceId = "wf-abc";
    final UUID caseId = UUID.randomUUID();
    final WorkflowModel model = mockModelWithMap(Map.of("result", "done"));

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    when(wfInstance.start()).thenReturn(CompletableFuture.completedFuture(model));
    stubApp(wfInstance);

    WorkerResult result =
        handler.execute(
            new FlowWorkerFunction(mock(Workflow.class)),
            Map.of(),
            testContext(caseId),
            5000,
            new ExecutionMetadata("worker-A", "hash-1"));

    assertThat((java.util.Map<String, Object>) result.output()).containsEntry("result", "done");
    verify(registry).register(eq(instanceId), eq(caseId), eq("worker-A"), eq("hash-1"));
    verify(registry).remove(instanceId);
  }

  @Test
  void execute_removes_registry_entry_when_future_completes_exceptionally() {
    final String instanceId = "wf-err";
    final UUID caseId = UUID.randomUUID();

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("step failed"));
    when(wfInstance.start()).thenReturn(failed);
    stubApp(wfInstance);

    try {
      handler.execute(
          new FlowWorkerFunction(mock(Workflow.class)),
          Map.of(),
          testContext(caseId),
          5000,
          new ExecutionMetadata("worker-B", "hash-2"));
    } catch (Exception ignored) {
    }

    verify(registry).remove(instanceId);
  }

  private WorkflowInstance mockWorkflowInstance(final String instanceId) {
    final WorkflowInstance inst = mock(WorkflowInstance.class);
    when(inst.id()).thenReturn(instanceId);
    return inst;
  }

  private void stubApp(final WorkflowInstance wfInstance) {
    final WorkflowDefinition definition = mock(WorkflowDefinition.class);
    when(definition.instance(any())).thenReturn(wfInstance);
    when(app.workflowDefinition(any())).thenReturn(definition);
  }

  @SuppressWarnings("unchecked")
  private WorkflowModel mockModelWithMap(Map<String, Object> map) {
    final WorkflowModel model = mock(WorkflowModel.class);
    when(model.asMap()).thenReturn(Optional.of(map));
    return model;
  }

  private WorkerContext testContext(UUID caseId) {
    return new WorkerContext("test task", caseId, null, null, null, null);
  }
}
