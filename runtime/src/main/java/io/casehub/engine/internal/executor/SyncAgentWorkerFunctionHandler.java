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
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

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

  @Inject
  public SyncAgentWorkerFunctionHandler(@VirtualThreads ExecutorService virtualThreads) {
    this.virtualThreads = virtualThreads;
  }

  @Override
  public boolean supports(WorkerFunction function) {
    return function instanceof WorkerFunction.Sync || function instanceof AgentWorkerFunction;
  }

  @Override
  public Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata) {

    Function<Map<String, Object>, WorkerResult> fn =
        switch (function) {
          case WorkerFunction.Sync sync -> sync.fn()::apply;
          case AgentWorkerFunction agent -> agent.agent()::execute;
          default ->
              throw new UnsupportedOperationException(
                  "Unsupported: " + function.getClass().getName());
        };

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
        .fail()
        .onFailure(io.smallrye.mutiny.TimeoutException.class)
        .recoverWithItem(t -> WorkerResult.expired("Worker timed out after " + timeoutMs + "ms"));
  }
}
