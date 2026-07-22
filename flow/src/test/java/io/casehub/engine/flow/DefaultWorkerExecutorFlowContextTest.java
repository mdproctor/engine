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

import static io.serverlessworkflow.fluent.func.FuncWorkflowBuilder.workflow;
import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.function;
import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests flow worker execution context handling.
 *
 * <p>Flow module is a test dependency — these tests verify context lifecycle when flow workers
 * execute.
 *
 * <p>Refs casehubio/engine#567 (Task 9).
 */
@QuarkusTest
class DefaultWorkerExecutorFlowContextTest {

  @Inject WorkerExecutor workerExecutor;

  @Test
  void executeFlow_setsWorkerExecutionContext() {
    AtomicReference<WorkerContext> captured = new AtomicReference<>();

    WorkerContext context =
        new WorkerContext(
            "test-flow-worker",
            UUID.randomUUID(),
            null,
            null,
            io.casehub.api.context.PropagationContext.createRoot(),
            null);

    var workflow =
        workflow("context-test")
            .tasks(
                function(
                    s -> {
                      captured.set(WorkerExecutionContext.current());
                      return Map.of("result", "done");
                    },
                    Map.class))
            .build();

    WorkerResult result =
        workerExecutor
            .execute(
                new FlowWorkerFunction(workflow),
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
    assertThat((java.util.Map<String, Object>) result.output()).containsEntry("result", "done");
  }

  @Test
  void executeFlow_clearsContextAfterExecution() {
    WorkerContext context =
        new WorkerContext(
            "cleanup-test",
            UUID.randomUUID(),
            null,
            null,
            io.casehub.api.context.PropagationContext.createRoot(),
            null);

    var workflow = workflow("cleanup-test").tasks(function(s -> Map.of(), Map.class)).build();

    workerExecutor
        .execute(
            new FlowWorkerFunction(workflow),
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
