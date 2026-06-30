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
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

class DefaultWorkerRuntime implements WorkerRuntime {

  private final UUID caseId;
  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;

  DefaultWorkerRuntime(
      UUID caseId,
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache) {
    this.caseId = caseId;
    this.caseHubRuntime = caseHubRuntime;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
  }

  @Override
  public UUID caseId() {
    return caseId;
  }

  @Override
  public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
    if (function instanceof WorkerFunction.Sync sync) {
      return executeSync(sync.fn()::apply, input);
    }
    if (function instanceof AgentWorkerFunction agent) {
      return executeSync(agent.agent()::execute, input);
    }
    return WorkerResult.failed(
        "Unsupported function type for Tier 1 execution: "
            + function.getClass().getName()
            + ". FlowWorkerFunction belongs at Tier 3.");
  }

  @Override
  public WorkerResult execute(String workerName, Map<String, Object> input) {
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
    return execute(worker.function(), input);
  }

  @Override
  public UUID spawnCase(String caseType, Map<String, Object> input) {
    throw new UnsupportedOperationException("spawnCase not yet implemented");
  }

  @Override
  public CaseContext awaitCase(UUID childCaseId, Duration timeout) {
    throw new UnsupportedOperationException("awaitCase not yet implemented");
  }

  @Override
  public CaseContext spawnAndAwaitCase(
      String caseType, Map<String, Object> input, Duration timeout) {
    UUID childId = spawnCase(caseType, input);
    return awaitCase(childId, timeout);
  }

  private WorkerResult executeSync(
      java.util.function.Function<Map<String, Object>, WorkerResult> fn,
      Map<String, Object> input) {
    WorkerContext parentCtx = WorkerExecutionContext.current();
    WorkerRuntime parentRuntime = WorkerExecutionContext.currentRuntime();
    try {
      WorkerContext childCtx = new WorkerContext(null, caseId, null, null, null, null);
      WorkerExecutionContext.set(childCtx);
      WorkerExecutionContext.setRuntime(this);
      return fn.apply(input);
    } catch (Exception e) {
      return WorkerResult.failed(e.getMessage());
    } finally {
      WorkerExecutionContext.set(parentCtx);
      WorkerExecutionContext.setRuntime(parentRuntime);
    }
  }
}
