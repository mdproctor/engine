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
package io.casehub.engine.agentic;

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.WorkerContext;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.ExecutionResult;
import io.casehub.blocks.agentic.model.OrchestratedDriver;
import io.casehub.engine.common.internal.executor.ExecutionMetadata;
import io.casehub.engine.common.internal.executor.HandlerResult;
import io.casehub.engine.common.internal.executor.WorkerFunctionHandler;
import io.casehub.engine.internal.executor.WorkerRuntimeFactory;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;

@ApplicationScoped
public class PatternWorkerFunctionHandler implements WorkerFunctionHandler {

  private final WorkerRuntimeFactory workerRuntimeFactory;

  @Inject
  public PatternWorkerFunctionHandler(WorkerRuntimeFactory workerRuntimeFactory) {
    this.workerRuntimeFactory = workerRuntimeFactory;
  }

  @Override
  public boolean supports(WorkerFunction<?, ?> function) {
    return function instanceof PatternWorkerFunction;
  }

  @Override
  @SuppressWarnings("unchecked")
  public HandlerResult execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata) {

    var patternFn = (PatternWorkerFunction) function;
    WorkerRuntime runtime =
        workerRuntimeFactory.create(context.caseId(), metadata.workerName(), context);

    var invoker = new EngineAgentInvoker<>(runtime);
    var driver = new OrchestratedDriver<>(invoker);

    ExecutionResult result;
    try {
      result =
          driver
              .execute((ExecutionModel<Object>) (ExecutionModel<?>) patternFn.model(), inputData)
              .await()
              .atMost(Duration.ofMillis(timeoutMs));
    } catch (Exception e) {
      driver.cancel();
      return new HandlerResult(
          WorkerResult.failed("Pattern execution failed: " + e.getMessage()),
          patternMetadata(patternFn));
    }

    WorkerResult<?> workerResult =
        switch (result) {
          case ExecutionResult.Completed c ->
              WorkerResult.of(c.result() instanceof Map m ? m : Map.of("result", c.result()));
          case ExecutionResult.Failed f -> WorkerResult.failed(f.reason());
          case ExecutionResult.Escalated e -> WorkerResult.failed("Escalated: " + e.reason());
          case ExecutionResult.Cancelled ignored -> WorkerResult.failed("Pattern cancelled");
        };

    return new HandlerResult(workerResult, patternMetadata(patternFn));
  }

  private Map<String, Object> patternMetadata(PatternWorkerFunction fn) {
    return Map.of(
        "patternType", fn.patternType().name(),
        "checkpointingEnabled", fn.checkpointingEnabled());
  }
}
