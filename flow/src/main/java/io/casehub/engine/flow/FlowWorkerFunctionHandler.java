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

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowModel;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * {@link WorkerFunctionHandler} for Serverless Workflow workers.
 *
 * <p>Replaces {@code FlowWorkerExecutor} — merges execution logic into the handler model. Sets
 * {@link WorkerExecutionContext} around the workflow execution so casehub tasks inside the workflow
 * can access case context via {@code WorkerExecutionContext.current()}.
 *
 * <p>Plain {@code @ApplicationScoped} (no {@code @DefaultBean}) — when {@code casehub-engine-flow}
 * is on the classpath, this handler is discovered automatically.
 */
@ApplicationScoped
public class FlowWorkerFunctionHandler implements WorkerFunctionHandler {

  private final WorkflowApplication app;
  private final FlowExecutionRegistry registry;
  private final ExecutorService virtualThreads;

  @Inject
  public FlowWorkerFunctionHandler(
      final WorkflowApplication app,
      final FlowExecutionRegistry registry,
      @VirtualThreads final ExecutorService virtualThreads) {
    this.app = app;
    this.registry = registry;
    this.virtualThreads = virtualThreads;
  }

  @Override
  public boolean supports(final WorkerFunction function) {
    return function instanceof FlowWorkerFunction;
  }

    @SuppressWarnings("unchecked")
    @Override
    public Uni<WorkerResult> execute(
            final WorkerFunction function,
            final Object inputData,
            final WorkerContext context,
            final int timeoutMs,
            final ExecutionMetadata metadata) {

        final FlowWorkerFunction  flow     = (FlowWorkerFunction) function;
        final Map<String, Object> mapInput = (Map<String, Object>) inputData;

        return Uni.createFrom()
                  .completionStage(
                          () -> {
                              WorkerExecutionContext.set(context);
                              try {
                                  return executeWorkflow(
                                          flow.workflow(),
                                          mapInput,
                                          context.caseId(),
                                          metadata.workerName(),
                                          metadata.inputDataHash());
                              } finally {
                                  WorkerExecutionContext.clear();
                              }
                          })
                  .runSubscriptionOn(virtualThreads)
                  .map(
                          model ->
                                  WorkerResult.of(
                                          model
                                                  .asMap()
                                                  .orElseThrow(
                                                          () ->
                                                                  new RuntimeException(
                                                                          "Workflow produced non-serializable model for worker: "
                                                                          + metadata.workerName()))));
    }

  private CompletableFuture<WorkflowModel> executeWorkflow(
      final Workflow workflow,
      final Map<String, Object> inputData,
      final UUID caseId,
      final String workerName,
      final String inputDataHash) {

    final WorkflowInstance wfInstance = app.workflowDefinition(workflow).instance(inputData);
    // id() is assigned in WorkflowMutableInstance constructor — available before start()
    final String instanceId = wfInstance.id();

    registry.register(instanceId, caseId, workerName, inputDataHash);
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
