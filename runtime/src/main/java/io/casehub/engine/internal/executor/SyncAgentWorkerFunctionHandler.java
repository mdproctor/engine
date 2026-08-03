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
  private final io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry
      scopedWorkerRegistry;

  @Inject
  public SyncAgentWorkerFunctionHandler(
      @VirtualThreads ExecutorService virtualThreads,
      WorkerRuntimeFactory workerRuntimeFactory,
      io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry scopedWorkerRegistry) {
    this.virtualThreads = virtualThreads;
    this.workerRuntimeFactory = workerRuntimeFactory;
    this.scopedWorkerRegistry = scopedWorkerRegistry;
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
      throw new io.casehub.api.context.BridgeTypeMismatchException(
          function.inputType().getName(), "null");
    }

    if (!function.inputType().isInstance(inputData)) {
      throw new io.casehub.api.context.BridgeTypeMismatchException(
          function.inputType().getName(), inputData.getClass().getName());
    }

    final Object resolvedInput = inputData;

    java.util.Map<String, Object> accState = java.util.Map.of();
    java.util.concurrent.locks.ReentrantLock bindingLock = null;
    if (metadata.executionMode() == io.casehub.api.model.ExecutionMode.REINVOKED
        && metadata.bindingName() != null) {
      var scopeKey =
          new io.casehub.engine.common.internal.worker.scope.ScopedWorkerRegistry.ScopeKey(
              context.caseId(), metadata.bindingName());
      bindingLock = scopedWorkerRegistry.executionLock(scopeKey);
      bindingLock.lock();
      accState =
          scopedWorkerRegistry
              .get(context.caseId(), metadata.bindingName())
              .filter(
                  io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked.class
                      ::isInstance)
              .map(
                  s ->
                      ((io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession
                                  .Reinvoked)
                              s)
                          .accumulatedState()
                          .get())
              .orElse(java.util.Map.of());
    }

    final io.casehub.api.engine.WorkerRuntime runtime =
        workerRuntimeFactory.create(context.caseId(), metadata.workerName(), context, accState);

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
      WorkerResult<?> result = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (metadata.executionMode() == io.casehub.api.model.ExecutionMode.REINVOKED
          && metadata.bindingName() != null) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> output =
            result.output() instanceof java.util.Map
                ? (java.util.Map<String, Object>) result.output()
                : null;
        if (output != null) {
          scopedWorkerRegistry
              .get(context.caseId(), metadata.bindingName())
              .filter(
                  io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession.Reinvoked.class
                      ::isInstance)
              .ifPresent(
                  s ->
                      ((io.casehub.engine.common.internal.worker.scope.ScopedWorkerSession
                                  .Reinvoked)
                              s)
                          .accumulatedState()
                          .set(output));
        }
      }
      return result;
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
    } finally {
      if (bindingLock != null) {
        bindingLock.unlock();
      }
    }
  }
}
