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
 * Serverless Workflow implementation of {@link WorkflowExecutor}.
 *
 * <p>Uses the Serverless Workflow SDK to execute workflow definitions.
 */
@ApplicationScoped
public class ServerlessWorkflowExecutor implements WorkflowExecutor {

  @Override
  public CompletableFuture<WorkflowModel> execute(
      Workflow workflow, Map<String, Object> inputData) {
    try (WorkflowApplication app = WorkflowApplication.builder().build()) {
      return app.workflowDefinition(workflow).instance(inputData).start();
    } catch (Exception e) {
      return CompletableFuture.failedFuture(new RuntimeException("Workflow execution failed", e));
    }
  }
}
