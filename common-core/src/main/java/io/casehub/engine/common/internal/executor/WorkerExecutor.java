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
import io.casehub.worker.api.WorkerFunction;

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

  HandlerResult execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      String outputProjection,
      ExecutionMetadata metadata);
}
