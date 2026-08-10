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
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class PatternWorkerFunctionHandler implements WorkerFunctionHandler {

  private final WorkerRuntimeFactory workerRuntimeFactory;
  private final PatternCheckpointStore checkpointStore;

  @Inject
  public PatternWorkerFunctionHandler(
      WorkerRuntimeFactory workerRuntimeFactory, PatternCheckpointStore checkpointStore) {
    this.workerRuntimeFactory = workerRuntimeFactory;
    this.checkpointStore = checkpointStore;
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

    int effectiveTimeoutMs = resolveTimeout(patternFn, timeoutMs);

    var invoker = new EngineAgentInvoker<>(runtime);

    io.casehub.engine.plan.execution.PatternExecutionCheckpoint checkpoint = null;
    if (patternFn.checkpointingEnabled() && metadata.tenancyId() != null) {
      checkpoint =
          checkpointStore
              .findLatest(context.caseId(), metadata.workerName(), metadata.tenancyId())
              .orElse(null);
    }

    final ExecutionModel<?> effectiveModel;
    if (patternFn.checkpointingEnabled() && metadata.tenancyId() != null) {
      var listener =
          new CheckpointingListener(
              context.caseId(), metadata.workerName(), metadata.tenancyId(), checkpointStore::save);
      effectiveModel = addListener(patternFn.model(), listener);
    } else {
      effectiveModel = patternFn.model();
    }

    var useResumableDriver = checkpoint != null && patternFn.rootTask() == null;
    var driver =
        useResumableDriver
            ? new ResumableDriver<>(invoker, checkpoint)
            : new OrchestratedDriver<>(invoker);

    ExecutionResult result;
    try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          exec.submit(
              () -> {
                if (patternFn.rootTask() != null) {
                  return executeHtn(patternFn, effectiveModel, invoker, inputData);
                }
                return executeStandard(patternFn, effectiveModel, driver, inputData);
              });
      result = future.get(effectiveTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      driver.cancel();
      return new HandlerResult(
          WorkerResult.expired("Pattern execution exceeded time budget"),
          patternMetadata(patternFn));
    } catch (Exception e) {
      driver.cancel();
      var cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
      return new HandlerResult(
          WorkerResult.failed("Pattern execution failed: " + cause.getMessage()),
          patternMetadata(patternFn));
    }

    return new HandlerResult(toWorkerResult(result), patternMetadata(patternFn));
  }

  @SuppressWarnings("unchecked")
  private ExecutionResult executeHtn(
      PatternWorkerFunction patternFn,
      ExecutionModel<?> model,
      EngineAgentInvoker<?> invoker,
      Object inputData) {
    var htnExecutor =
        new io.casehub.blocks.agentic.pattern.HtnExecutor<>(
            (io.casehub.blocks.agentic.model.AgentInvoker<Object>) invoker);
    var constrainedModel =
        applyResourceLimit(
            (ExecutionModel<Object>) (ExecutionModel<?>) model, patternFn.planningConstraints());
    return htnExecutor.execute(
        (io.casehub.engine.plan.TaskNode<Object>) patternFn.rootTask(),
        constrainedModel,
        inputData);
  }

  @SuppressWarnings("unchecked")
  private ExecutionResult executeStandard(
      PatternWorkerFunction patternFn,
      ExecutionModel<?> model,
      OrchestratedDriver<?> driver,
      Object inputData) {
    var constrainedModel =
        applyResourceLimit(
            (ExecutionModel<Object>) (ExecutionModel<?>) model, patternFn.planningConstraints());
    return ((OrchestratedDriver<Object>) driver)
        .execute(constrainedModel, inputData)
        .await()
        .indefinitely();
  }

  private WorkerResult<?> toWorkerResult(ExecutionResult result) {
    return switch (result) {
      case ExecutionResult.Completed c ->
          WorkerResult.of(c.result() instanceof Map m ? m : Map.of("result", c.result()));
      case ExecutionResult.Failed f -> WorkerResult.failed(f.reason());
      case ExecutionResult.Escalated e -> WorkerResult.failed("Escalated: " + e.reason());
      case ExecutionResult.Cancelled ignored -> WorkerResult.failed("Pattern cancelled");
    };
  }

  private int resolveTimeout(PatternWorkerFunction fn, int defaultTimeoutMs) {
    if (fn.planningConstraints() == null || fn.planningConstraints().timeBudget() == null) {
      return defaultTimeoutMs;
    }
    long budgetMs = fn.planningConstraints().timeBudget().toMillis();
    return (int) Math.min(budgetMs, defaultTimeoutMs);
  }

  @SuppressWarnings("unchecked")
  private <T> ExecutionModel<T> applyResourceLimit(
      ExecutionModel<T> model, PlanningConstraints constraints) {
    if (constraints == null || constraints.resourceLimit() == null) {
      return model;
    }
    int limit = constraints.resourceLimit();
    var original = model.routing();
    io.casehub.blocks.agentic.routing.RoutingStrategy<T> capped =
        ctx ->
            original
                .route(ctx)
                .map(
                    decision -> {
                      if (decision
                              instanceof
                              io.casehub.blocks.agentic.routing.RoutingDecision.Selected selected
                          && selected.agents().size() > limit) {
                        return new io.casehub.blocks.agentic.routing.RoutingDecision.Selected(
                            selected.agents().subList(0, limit));
                      }
                      return decision;
                    });
    return new ExecutionModel<>(
        capped,
        model.decomposition(),
        model.activation(),
        model.aggregation(),
        model.termination(),
        model.candidateSupplier(),
        model.failurePolicy(),
        model.listeners(),
        model.task(),
        model.patternType());
  }

  private Map<String, Object> patternMetadata(PatternWorkerFunction fn) {
    return Map.of(
        "patternType", fn.patternType().name(),
        "checkpointingEnabled", fn.checkpointingEnabled());
  }

  @SuppressWarnings("unchecked")
  private static <T> ExecutionModel<T> addListener(
      ExecutionModel<T> model, io.casehub.blocks.agentic.model.ExecutionEventListener listener) {
    var listeners =
        new java.util.ArrayList<io.casehub.blocks.agentic.model.ExecutionEventListener>(
            model.listeners());
    listeners.add(listener);
    return new ExecutionModel<>(
        model.routing(),
        model.decomposition(),
        model.activation(),
        model.aggregation(),
        model.termination(),
        model.candidateSupplier(),
        model.failurePolicy(),
        listeners,
        model.task(),
        model.patternType());
  }
}
