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

import io.casehub.api.context.PropagationContext;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.worker.api.WorkerFunction;
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

class FlowWorkerFunctionHandlerTest {

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
    assertThat(handler.supports(new FlowWorkerFunction(mock(Workflow.class)))).isTrue();
  }

  @Test
  void does_not_support_other_function_types() {
    WorkerFunction other = mock(WorkerFunction.class);
    assertThat(handler.supports(other)).isFalse();
  }

  @Test
  void execute_registers_before_start_and_removes_on_success() {
    final String instanceId = "wf-abc";
    final UUID caseId = UUID.randomUUID();
    final WorkflowModel model = mock(WorkflowModel.class);
    when(model.asMap()).thenReturn(Optional.of(Map.of("result", "done")));

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> future = CompletableFuture.completedFuture(model);
    when(wfInstance.start()).thenReturn(future);

    stubApp(wfInstance);

    final WorkerContext context =
        new WorkerContext("worker-A", caseId, null, null, PropagationContext.createRoot(), null);
    final ExecutionMetadata metadata = new ExecutionMetadata("worker-A", "hash-1");

    final WorkerResult result =
        handler.execute(
            new FlowWorkerFunction(mock(Workflow.class)), Map.of(), context, 60000, metadata);

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

    final WorkerContext context =
        new WorkerContext("worker-B", caseId, null, null, PropagationContext.createRoot(), null);
    final ExecutionMetadata metadata = new ExecutionMetadata("worker-B", "hash-2");

    try {
      handler.execute(
          new FlowWorkerFunction(mock(Workflow.class)), Map.of(), context, 60000, metadata);
    } catch (Exception ignored) {
    }

    verify(registry).remove(instanceId);
  }

  @Test
  void execute_removes_registry_entry_and_propagates_when_start_throws_synchronously() {
    final String instanceId = "wf-sync-err";
    final UUID caseId = UUID.randomUUID();

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    when(wfInstance.start()).thenThrow(new RuntimeException("sync exception from start()"));

    stubApp(wfInstance);

    final WorkerContext context =
        new WorkerContext("worker-C", caseId, null, null, PropagationContext.createRoot(), null);
    final ExecutionMetadata metadata = new ExecutionMetadata("worker-C", "h");

    try {
      handler.execute(
          new FlowWorkerFunction(mock(Workflow.class)), Map.of(), context, 60000, metadata);
      throw new AssertionError("Should have thrown");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageContaining("sync exception from start()");
      verify(registry).register(eq(instanceId), any(), any(), any());
      verify(registry).remove(instanceId);
    }
  }

  @Test
  void execute_returns_result_after_future_completes() {
    final String instanceId = "wf-future";
    final WorkflowModel model = mock(WorkflowModel.class);
    when(model.asMap()).thenReturn(Optional.of(Map.of("data", "value")));

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> future = CompletableFuture.completedFuture(model);
    when(wfInstance.start()).thenReturn(future);

    stubApp(wfInstance);

    final UUID caseId = UUID.randomUUID();
    final WorkerContext context =
        new WorkerContext("w", caseId, null, null, PropagationContext.createRoot(), null);
    final ExecutionMetadata metadata = new ExecutionMetadata("w", "h");

    final WorkerResult result =
        handler.execute(
            new FlowWorkerFunction(mock(Workflow.class)), Map.of(), context, 60000, metadata);

    assertThat((java.util.Map<String, Object>) result.output()).containsEntry("data", "value");
    verify(registry).remove(instanceId);
  }

  // ---- helpers --------------------------------------------------------

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
}
