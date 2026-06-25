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
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import java.util.Map;

/**
 * Engine-internal SPI for pluggable worker function execution. Implementations pattern-match on
 * {@link WorkerFunction} subclasses and handle the dispatch and lifecycle (timeout, context
 * set/clear).
 *
 * <p>Output schema evaluation is a cross-cutting concern owned by the composite executor (not the
 * handler).
 *
 * <p>Handlers are complementary, not competing alternatives — implementations are
 * {@code @ApplicationScoped} (not {@code @DefaultBean}).
 *
 * <p>Refs casehubio/engine#567.
 */
public interface WorkerFunctionHandler {

  /**
   * Returns true if this handler can execute the given function type.
   *
   * @param function the worker function to test
   * @return true if this handler supports the function type
   */
  boolean supports(WorkerFunction function);

  /**
   * Executes the worker function and returns a result.
   *
   * <p>The handler is responsible for:
   *
   * <ul>
   *   <li>Running the function on the appropriate thread pool
   *   <li>Setting/clearing {@link io.casehub.api.model.WorkerExecutionContext#set(WorkerContext)}
   *   <li>Enforcing the timeout
   *   <li>Recovering timeout exceptions as {@link io.casehub.worker.api.WorkerResult#expired}
   * </ul>
   *
   * <p>The handler MUST NOT evaluate output schema — that is owned by the composite executor.
   *
   * @param function the worker function to execute
   * @param inputData the input data map
   * @param context the worker context (channels, case ID, etc.)
   * @param timeoutMs the timeout in milliseconds
   * @param metadata lineage metadata (worker name, input data hash)
   * @return a Uni emitting the worker result (never null)
   */
  Uni<WorkerResult> execute(
      WorkerFunction function,
      Map<String, Object> inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata);
}
