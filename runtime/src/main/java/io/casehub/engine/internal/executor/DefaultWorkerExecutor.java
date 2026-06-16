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

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.api.model.WorkerFunction;
import io.casehub.api.model.WorkerResult;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.worker.WorkflowExecutor;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Engine's own worker executor — runs sync/agent functions on Quarkus-managed virtual threads and
 * delegates flow workers to {@link WorkflowExecutor}. Applies output schema evaluation uniformly
 * for all paths.
 *
 * <p>{@code @ApplicationScoped} (not {@code @DefaultBean}) — this is the engine's implementation,
 * not a consumer-replaceable fallback.
 *
 * <p>Refs casehubio/engine#463.
 */
@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

  private static final Logger LOG = Logger.getLogger(DefaultWorkerExecutor.class);

  private final ExecutorService virtualThreads;
  private final WorkflowExecutor workflowExecutor;
  private final JQEvaluator jqEvaluator;

  @Inject
  public DefaultWorkerExecutor(
      @VirtualThreads ExecutorService virtualThreads,
      WorkflowExecutor workflowExecutor,
      JQEvaluator jqEvaluator) {
    this.virtualThreads = virtualThreads;
    this.workflowExecutor = workflowExecutor;
    this.jqEvaluator = jqEvaluator;
  }

  @Override
  public Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      String outputSchema,
      ExecutionMetadata metadata) {

    Uni<WorkerResult> execution =
        switch (function) {
          case WorkerFunction.Sync sync -> executeSync(sync.fn(), inputData, context, timeoutMs);
          case WorkerFunction.AgentExec agent ->
              executeSync(agent.agent()::execute, inputData, context, timeoutMs);
          case WorkerFunction.Flow flow ->
              executeFlow(flow.workflow(), inputData, context, metadata);
        };
    return execution.map(result -> applyOutputSchema(result, outputSchema));
  }

  private Uni<WorkerResult> executeSync(
      Function<Map<String, Object>, WorkerResult> fn,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs) {

    return Uni.createFrom()
        .item(
            () -> {
              WorkerExecutionContext.set(context);
              try {
                return fn.apply(inputData);
              } finally {
                WorkerExecutionContext.clear();
              }
            })
        .runSubscriptionOn(virtualThreads)
        .ifNoItem()
        .after(Duration.ofMillis(timeoutMs))
        .fail();
  }

  private Uni<WorkerResult> executeFlow(
      io.serverlessworkflow.api.types.Workflow workflow,
      Map<String, Object> inputData,
      WorkerContext context,
      ExecutionMetadata metadata) {

    // Flow execution delegates timeout to the workflow runtime — individual steps manage
    // their own timeouts via the workflow definition. No overall execution timeout applied.
    return Uni.createFrom()
        .completionStage(
            () ->
                workflowExecutor.execute(
                    workflow,
                    inputData,
                    context.caseId(),
                    metadata.workerName(),
                    metadata.inputDataHash()))
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

  @SuppressWarnings("unchecked")
  private WorkerResult applyOutputSchema(WorkerResult result, String outputSchema) {
    if (outputSchema == null || outputSchema.isBlank() || result.output() == null) {
      return result;
    }
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode outputNode = mapper.valueToTree(result.output());
    ValidationResult vr = jqEvaluator.eval(outputSchema, outputNode);
    if (!vr.ok()) {
      LOG.warnf("Output schema evaluation error: %s", vr.error());
      return result;
    }
    if (vr.output() != null && !vr.output().isEmpty()) {
      Map<String, Object> evaluated =
          mapper.convertValue(
              vr.output().get(0),
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      return WorkerResult.of(evaluated, result.plannedAction());
    }
    return result;
  }
}
