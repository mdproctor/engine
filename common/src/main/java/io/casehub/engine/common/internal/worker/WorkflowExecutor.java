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

import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.impl.WorkflowModel;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executor for serverless workflow definitions.
 *
 * <p>This interface defines the contract for executing workflow instances based on Serverless
 * Workflow specifications.
 *
 * @see io.serverlessworkflow.api.types.Workflow
 * @see io.serverlessworkflow.impl.WorkflowModel
 */
public interface WorkflowExecutor {

  /**
   * Execute a workflow with the given input data.
   *
   * <p>{@code inputData} is {@code Map<String, Object>} at this layer because it is post-evaluation
   * data — the result of applying {@code inputMapping} expressions against {@link
   * io.casehub.api.context.CaseContext}. This is the correct type at the engine-internal layer.
   * Public entry points ({@link io.casehub.api.engine.CaseHub#startCase} and {@link
   * io.casehub.api.engine.CaseHubRuntime#startCase}) should accept {@code Object} to align with
   * {@code Flow.instance(Object)} — tracked in casehubio/engine#302.
   *
   * @param workflow the workflow definition
   * @param inputData post-evaluated case input; always a Map at this layer
   * @return CompletableFuture containing the workflow execution result
   */
  CompletableFuture<WorkflowModel> execute(Workflow workflow, Map<String, Object> inputData);
}
