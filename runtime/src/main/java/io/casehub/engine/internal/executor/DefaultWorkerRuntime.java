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

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.SettlementTimeoutException;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.WorkerContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseTerminatedException;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.CaseCompletionTracker;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class DefaultWorkerRuntime implements WorkerRuntime {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final UUID caseId;
  private final String taskId;
  private final WorkerContext context;
  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;
  private final CaseCompletionTracker tracker;

  DefaultWorkerRuntime(
      UUID caseId,
      String taskId,
      WorkerContext context,
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache,
      CaseCompletionTracker tracker) {
    this.caseId = caseId;
    this.taskId = taskId;
    this.context = context;
    this.caseHubRuntime = caseHubRuntime;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
    this.tracker = tracker;
  }

  @Override
  public UUID caseId() {
    return caseId;
  }

  @Override
  public String taskId() {
    return taskId;
  }

  @Override
  public WorkerContext context() {
    return context;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, R> WorkerResult<R> execute(WorkerFunction<T, R> function, T input) {
    if (function instanceof WorkerFunction.Sync<T, R> sync) {
      try {
        return sync.fn().apply(input, this);
      } catch (Exception e) {
        return (WorkerResult<R>) WorkerResult.failed(e.getMessage());
      }
    }
    if (function instanceof AgentWorkerFunction agent) {
      return (WorkerResult<R>) agent.agent().execute((java.util.Map<String, Object>) input);
    }
    return (WorkerResult<R>)
        WorkerResult.failed(
            "Unsupported function type for Tier 1 execution: " + function.getClass().getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public WorkerResult<?> execute(String workerName, Map<String, Object> input) {
    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      return WorkerResult.failed("Case instance not found: " + caseId);
    }
    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) {
      return WorkerResult.failed("Case definition not found for case: " + caseId);
    }
    Worker worker =
        definition.getWorkers().stream()
            .filter(w -> workerName.equals(w.name()))
            .findFirst()
            .orElse(null);
    if (worker == null) {
      throw new IllegalArgumentException(
          "Worker '"
              + workerName
              + "' not found in case definition '"
              + definition.getName()
              + "'");
    }
    WorkerFunction<?, ?> function = worker.function();
    if (function.inputType() != Map.class && function.inputType() != Object.class) {
      Object converted = MAPPER.convertValue(input, function.inputType());
      return execute((WorkerFunction) function, converted);
    }
    return execute((WorkerFunction) function, input);
  }

  @Override
  public UUID spawnCase(String caseType, Map<String, Object> input) {
    CaseDefinition definition =
        definitionRegistry
            .findByName(caseType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No case definition found for caseType: " + caseType));

    CaseInstance parentInstance = caseInstanceCache.get(caseId);
    PropagationContext propagation =
        parentInstance != null ? parentInstance.getPropagationContext() : null;

    try {
      return caseHubRuntime.startCase(definition, input, caseId, propagation);
    } catch (Exception e) {
      throw new RuntimeException("Failed to spawn case '" + caseType + "'", e);
    }
  }

  @Override
  public CaseContext awaitCase(UUID childCaseId, Duration timeout) {
    CompletableFuture<CaseContext> future = tracker.register(childCaseId);

    CaseInstance child = caseInstanceCache.get(childCaseId);
    if (child != null && isTerminal(child.getState())) {
      CaseContext snapshot = child.getCaseContext().snapshot();
      if (child.getState() == CaseStatus.COMPLETED) {
        future.complete(snapshot);
      } else {
        future.completeExceptionally(new CaseTerminatedException(childCaseId, child.getState()));
      }
    }

    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new SettlementTimeoutException(childCaseId, timeout);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof CaseTerminatedException cte) {
        throw cte;
      }
      throw new RuntimeException("Child case " + childCaseId + " failed", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while awaiting case " + childCaseId, e);
    } finally {
      tracker.remove(childCaseId);
    }
  }

  private static boolean isTerminal(CaseStatus status) {
    return status == CaseStatus.COMPLETED
        || status == CaseStatus.FAULTED
        || status == CaseStatus.CANCELLED;
  }

  @Override
  public CaseContext spawnAndAwaitCase(
      String caseType, Map<String, Object> input, Duration timeout) {
    UUID childId = spawnCase(caseType, input);
    return awaitCase(childId, timeout);
  }
}
