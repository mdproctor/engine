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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowDefinition;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowWorkerExecutorTest {

  private WorkflowApplication app;
  private FlowExecutionRegistry registry;
  private FlowWorkerExecutor executor;

  @BeforeEach
  void setUp() {
    app = mock(WorkflowApplication.class);
    registry = mock(FlowExecutionRegistry.class);
    executor = new FlowWorkerExecutor(app, registry);
  }

  @Test
  void execute_registers_before_start_and_removes_on_success() throws Exception {
    final String instanceId = "wf-abc";
    final CaseInstance caseInstance = mock(CaseInstance.class);
    final WorkflowModel model = mock(WorkflowModel.class);

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> future = CompletableFuture.completedFuture(model);
    when(wfInstance.start()).thenReturn(future);

    stubApp(wfInstance);

    final CompletableFuture<WorkflowModel> result =
        executor.execute(mock(Workflow.class), Map.of(), caseInstance, "worker-A", "hash-1");

    assertThat(result.get()).isSameAs(model);
    verify(registry).register(eq(instanceId), eq(caseInstance), eq("worker-A"), eq("hash-1"));
    verify(registry).remove(instanceId);
  }

  @Test
  void execute_removes_registry_entry_when_future_completes_exceptionally() throws Exception {
    final String instanceId = "wf-err";
    final CaseInstance caseInstance = mock(CaseInstance.class);

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("step failed"));
    when(wfInstance.start()).thenReturn(failed);

    stubApp(wfInstance);

    final CompletableFuture<WorkflowModel> result =
        executor.execute(mock(Workflow.class), Map.of(), caseInstance, "worker-B", "hash-2");

    assertThat(result).isCompletedExceptionally();
    verify(registry).remove(instanceId);
  }

  @Test
  void execute_removes_registry_entry_and_rethrows_when_start_throws_synchronously() {
    final String instanceId = "wf-sync-err";
    final CaseInstance caseInstance = mock(CaseInstance.class);

    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    when(wfInstance.start()).thenThrow(new RuntimeException("sync exception from start()"));

    stubApp(wfInstance);

    assertThatThrownBy(
            () -> executor.execute(mock(Workflow.class), Map.of(), caseInstance, "worker-C", "h"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("sync exception from start()");

    // Registry must be cleaned up — no leak
    verify(registry).register(eq(instanceId), any(), any(), any());
    verify(registry).remove(instanceId);
  }

  @Test
  void execute_returns_future_from_start() throws Exception {
    final String instanceId = "wf-future";
    final WorkflowModel model = mock(WorkflowModel.class);
    final WorkflowInstance wfInstance = mockWorkflowInstance(instanceId);
    final CompletableFuture<WorkflowModel> future = new CompletableFuture<>();
    when(wfInstance.start()).thenReturn(future);

    stubApp(wfInstance);

    final CompletableFuture<WorkflowModel> result =
        executor.execute(mock(Workflow.class), Map.of(), mock(CaseInstance.class), "w", "h");

    assertThat(result.isDone()).isFalse();
    future.complete(model);
    assertThat(result.get()).isSameAs(model);
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
