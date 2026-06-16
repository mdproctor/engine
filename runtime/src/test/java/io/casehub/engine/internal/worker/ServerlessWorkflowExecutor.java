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
package io.casehub.engine.internal.worker;

import io.casehub.engine.common.internal.worker.WorkflowExecutor;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Test-only {@link WorkflowExecutor} for the runtime module's own {@code @QuarkusTest} suite.
 * Activates over {@code NoOpWorkflowExecutor @DefaultBean} because it is plain
 * {@code @ApplicationScoped} (non-default). Plain {@code FlowWorkerExecutor} in {@code
 * casehub-engine-flow} wins over this when the flow module is on the test classpath.
 *
 * <p>This is the legacy implementation with two known bugs — new {@code WorkflowApplication} per
 * call, and {@code try-with-resources} that closes the app before the returned {@code
 * CompletableFuture} resolves. Both bugs are fixed by {@code FlowWorkerExecutor}. This class exists
 * only to keep the existing runtime integration tests passing until they are migrated to use {@code
 * casehub-engine-flow} — tracked in casehubio/engine#206.
 *
 * @deprecated Use {@code FlowWorkerExecutor} from {@code casehub-engine-flow} for production use.
 */
@Deprecated
@ApplicationScoped
public class ServerlessWorkflowExecutor implements WorkflowExecutor {

  @Override
  public CompletableFuture<WorkflowModel> execute(
      final Workflow workflow,
      final Map<String, Object> inputData,
      final java.util.UUID caseId,
      final String workerName,
      final String inputDataHash) {
    try (WorkflowApplication app = WorkflowApplication.builder().build()) {
      return app.workflowDefinition(workflow).instance(inputData).start();
    } catch (Exception e) {
      return CompletableFuture.failedFuture(new RuntimeException("Workflow execution failed", e));
    }
  }
}
