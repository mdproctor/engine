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

import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;

/**
 * Handler for {@link WorkerFunction.Sync} and {@link AgentWorkerFunction}. Executes the function on
 * a virtual thread, sets/clears {@link WorkerExecutionContext}, and enforces timeout with recovery
 * to {@link io.casehub.worker.api.WorkerResult#expired}.
 *
 * <p>Complementary to other handlers — this is {@code @ApplicationScoped} (not
 * {@code @DefaultBean}).
 *
 * <p>Refs casehubio/engine#567.
 */
@ApplicationScoped
public class SyncAgentWorkerFunctionHandler implements WorkerFunctionHandler {

  private final ExecutorService virtualThreads;
  private final WorkerRuntimeFactory workerRuntimeFactory;

  @Inject
  public SyncAgentWorkerFunctionHandler(
      @VirtualThreads ExecutorService virtualThreads, WorkerRuntimeFactory workerRuntimeFactory) {
    this.virtualThreads = virtualThreads;
    this.workerRuntimeFactory = workerRuntimeFactory;
  }

  @Override
  public boolean supports(WorkerFunction<?, ?> function) {
    return function instanceof WorkerFunction.Sync || function instanceof AgentWorkerFunction;
  }

  @SuppressWarnings("unchecked")
  @Override
  public WorkerResult<?> execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata) {

    if (inputData == null) {
      if (function.inputType() == java.util.Map.class) {
        inputData = java.util.Map.of();
      } else {
        throw new io.casehub.api.context.BridgeTypeMismatchException(
            function.inputType().getName(), "null");
      }
    }

    if (!function.inputType().isInstance(inputData) && !(inputData instanceof java.util.Map)) {
      throw new io.casehub.api.context.BridgeTypeMismatchException(
          function.inputType().getName(), inputData.getClass().getName());
    }

    final Object resolvedInput = inputData;
    final io.casehub.api.engine.WorkerRuntime runtime =
        workerRuntimeFactory.create(context.caseId(), metadata.workerName(), context);

    java.util.function.Function<Object, WorkerResult<?>> fn =
        switch (function) {
          case WorkerFunction.Sync<?, ?> sync -> {
            var biFn =
                (java.util.function.BiFunction<
                        Object, io.casehub.worker.api.WorkerScope, WorkerResult<?>>)
                    (java.util.function.BiFunction) sync.fn();
            yield input -> biFn.apply(input, runtime);
          }
          case AgentWorkerFunction agent ->
              input -> agent.agent().execute((java.util.Map<String, Object>) input);
          default ->
              throw new UnsupportedOperationException(
                  "Unsupported: " + function.getClass().getName());
        };

    try {
      java.util.concurrent.Future<WorkerResult<?>> future =
          virtualThreads.submit(() -> fn.apply(resolvedInput));
      return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      return WorkerResult.expired("Worker timed out after " + timeoutMs + "ms");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Worker execution interrupted", e);
    }
  }
}
