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
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerExecutor;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Engine's own worker executor — composite that dispatches to pluggable {@link
 * WorkerFunctionHandler} implementations. Applies output schema evaluation uniformly for all paths.
 *
 * <p>{@code @ApplicationScoped} (not {@code @DefaultBean}) — this is the engine's implementation,
 * not a consumer-replaceable fallback.
 *
 * <p>Refs casehubio/engine#463, casehubio/engine#567.
 */
@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

  private static final Logger LOG = Logger.getLogger(DefaultWorkerExecutor.class);

  private final Instance<WorkerFunctionHandler> handlers;
  private final JQEvaluator jqEvaluator;

  @Inject
  public DefaultWorkerExecutor(Instance<WorkerFunctionHandler> handlers, JQEvaluator jqEvaluator) {
    this.handlers = handlers;
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

    for (WorkerFunctionHandler handler : handlers) {
      if (handler.supports(function)) {
        return handler
            .execute(function, inputData, context, timeoutMs, metadata)
            .map(result -> applyOutputSchema(result, outputSchema));
      }
    }
    throw new UnsupportedOperationException("No handler for: " + function.getClass().getName());
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
      return new WorkerResult(evaluated, result.outcome());
    }
    return result;
  }
}
