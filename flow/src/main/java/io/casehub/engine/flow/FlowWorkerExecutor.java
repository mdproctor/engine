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

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.worker.WorkflowExecutor;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Proper {@link WorkflowExecutor} — fixes the two bugs in {@code ServerlessWorkflowExecutor}:
 * singleton {@link WorkflowApplication} (no per-call creation) and non-blocking execution (no
 * try-with-resources that closes the app before the future resolves).
 *
 * <p>Plain {@code @ApplicationScoped} (no {@code @DefaultBean}) so CDI prefers this over {@code
 * NoOpWorkflowExecutor @DefaultBean} when {@code casehub-engine-flow} is on the classpath.
 */
@ApplicationScoped
public class FlowWorkerExecutor implements WorkflowExecutor {

  private final WorkflowApplication app;
  private final FlowExecutionRegistry registry;

  @Inject
  public FlowWorkerExecutor(final WorkflowApplication app, final FlowExecutionRegistry registry) {
    this.app = app;
    this.registry = registry;
  }

  @Override
  public CompletableFuture<WorkflowModel> execute(
      final Workflow workflow,
      final Map<String, Object> inputData,
      final CaseInstance caseInstance,
      final String workerName,
      final String inputDataHash) {

    final WorkflowInstance wfInstance = app.workflowDefinition(workflow).instance(inputData);
    // id() is assigned in WorkflowMutableInstance constructor — available before start()
    final String instanceId = wfInstance.id();

    registry.register(instanceId, caseInstance, workerName, inputDataHash);
    try {
      final CompletableFuture<WorkflowModel> future = wfInstance.start();
      future.whenComplete((model, ex) -> registry.remove(instanceId));
      return future;
    } catch (final RuntimeException e) {
      registry.remove(instanceId);
      throw e;
    }
  }
}
