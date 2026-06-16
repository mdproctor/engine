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
package io.casehub.engine.common.internal.executor;

import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerFunction;
import io.casehub.api.model.WorkerResult;
import io.smallrye.mutiny.Uni;
import java.util.Map;

/**
 * Abstracts <em>how</em> to run a worker function — independent of any scheduler. Called by
 * scheduler adapters (Quartz today, db-scheduler tomorrow); implemented by the engine runtime.
 *
 * <p>Follows the {@code WorkflowExecutor} precedent in {@code common/internal/worker/} — called by
 * scheduler modules, not implemented by them.
 *
 * <p>Refs casehubio/engine#463.
 */
public interface WorkerExecutor {

  /**
   * Executes a worker function on a virtual thread with timeout enforcement and output schema
   * evaluation.
   *
   * @param function the sealed worker function variant (Sync, AgentExec, or Flow)
   * @param inputData the input data map (already evaluated from input schema)
   * @param context the worker context (task description, case ID, channels, prior workers)
   * @param timeoutMs resolved effective timeout in milliseconds (non-nullable)
   * @param outputSchema nullable JQ expression for output evaluation
   * @param metadata lineage metadata (workerName, inputDataHash) — used by flow path, ignored by
   *     sync/agent
   * @return Uni completing with the evaluated WorkerResult
   */
  Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      String outputSchema,
      ExecutionMetadata metadata);
}
