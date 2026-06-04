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
package io.casehub.engine.common.internal.worker;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowModel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for serverless workflow definitions.
 *
 * <p>{@code workerName} and {@code inputDataHash} are passed for lineage — {@code
 * FlowWorkerExecutor} stores them in {@code FlowExecutionRegistry} so {@code CasehubDispatch} can
 * emit {@code WORKFLOW_STEP_DISPATCHED} events with the correct parent execution metadata.
 */
public interface WorkflowExecutor {

  CompletableFuture<WorkflowModel> execute(
      Workflow workflow,
      Map<String, Object> inputData,
      CaseInstance caseInstance,
      String workerName,
      String inputDataHash);
}
