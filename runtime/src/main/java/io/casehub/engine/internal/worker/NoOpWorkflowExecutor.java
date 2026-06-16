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
import io.quarkus.arc.DefaultBean;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fallback {@link WorkflowExecutor} for deployments that do not include {@code casehub-engine-flow}
 * on the classpath. Throws {@link UnsupportedOperationException} on use — a {@code
 * Worker(Workflow)} cannot execute without the flow module present.
 */
@DefaultBean
@ApplicationScoped
public class NoOpWorkflowExecutor implements WorkflowExecutor {

  @Override
  public CompletableFuture<WorkflowModel> execute(
      final Workflow workflow,
      final Map<String, Object> inputData,
      final UUID caseId,
      final String workerName,
      final String inputDataHash) {
    return CompletableFuture.failedFuture(
        new UnsupportedOperationException(
            "casehub-engine-flow is not on the classpath. "
                + "Add it as a dependency to enable Worker(Workflow) execution."));
  }
}
