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
package io.casehub.engine.internal.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.model.WorkerFunction;
import io.casehub.api.model.WorkerResult;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.worker.WorkflowExecutor;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
class DefaultWorkerExecutorFlowContextTest {

  @Inject WorkerExecutor workerExecutor;

  @InjectMock WorkflowExecutor workflowExecutor;

  @Test
  void executeFlow_setsWorkerExecutionContext() {
    AtomicReference<WorkerContext> captured = new AtomicReference<>();

    WorkflowModel model = Mockito.mock(WorkflowModel.class);
    Mockito.when(model.asMap()).thenReturn(Optional.of(Map.of("result", "done")));

    Mockito.when(
            workflowExecutor.execute(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenAnswer(
            inv -> {
              captured.set(WorkerExecutionContext.current());
              return CompletableFuture.completedFuture(model);
            });

    WorkerContext context =
        new WorkerContext(
            "test-flow-worker",
            UUID.randomUUID(),
            null,
            null,
            io.casehub.api.context.PropagationContext.createRoot(),
            null);

    Workflow workflow = Mockito.mock(Workflow.class);

    WorkerResult result =
        workerExecutor
            .execute(
                new WorkerFunction.Flow(workflow),
                Map.of(),
                context,
                60000,
                null,
                new ExecutionMetadata("test-flow-worker", "hash-1"))
            .await()
            .atMost(Duration.ofSeconds(10));

    assertThat(captured.get())
        .as("WorkerExecutionContext.current() must be non-null inside flow execution")
        .isNotNull();
    assertThat(captured.get().caseId()).isEqualTo(context.caseId());
    assertThat(result.output()).containsEntry("result", "done");
  }

  @Test
  void executeFlow_clearsContextAfterExecution() {
    WorkflowModel model = Mockito.mock(WorkflowModel.class);
    Mockito.when(model.asMap()).thenReturn(Optional.of(Map.of()));

    Mockito.when(
            workflowExecutor.execute(
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(CompletableFuture.completedFuture(model));

    WorkerContext context =
        new WorkerContext(
            "cleanup-test",
            UUID.randomUUID(),
            null,
            null,
            io.casehub.api.context.PropagationContext.createRoot(),
            null);

    workerExecutor
        .execute(
            new WorkerFunction.Flow(Mockito.mock(Workflow.class)),
            Map.of(),
            context,
            60000,
            null,
            new ExecutionMetadata("cleanup-test", "hash-1"))
        .await()
        .atMost(Duration.ofSeconds(10));

    assertThat(WorkerExecutionContext.current())
        .as("WorkerExecutionContext must be cleared after flow execution")
        .isNull();
  }
}
