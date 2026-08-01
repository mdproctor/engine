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

  boolean supports(WorkerFunction<?, ?> function);

  HandlerResult execute(
      WorkerFunction<?, ?> function,
      Object inputData,
      WorkerContext context,
      int timeoutMs,
      ExecutionMetadata metadata);
}
