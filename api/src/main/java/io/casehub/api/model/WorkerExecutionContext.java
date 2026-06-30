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
package io.casehub.api.model;

import io.casehub.api.engine.WorkerRuntime;

/**
 * Thread-local holder for the {@link WorkerContext} active during worker function execution.
 *
 * <p>Set by the engine immediately before invoking the worker function; cleared in a {@code
 * finally} block after execution completes or fails. Workers running in the same thread as their
 * invocation can call {@link #current()} to access their case's channels and other startup context.
 *
 * <p>Not safe to use across async boundaries (e.g. CompletableFuture continuations on other
 * threads). For reactive workers, the engine's reactive execution path propagates context
 * separately.
 */
public final class WorkerExecutionContext {

  private static final ThreadLocal<WorkerContext> HOLDER = new ThreadLocal<>();
  private static final ThreadLocal<WorkerRuntime> RUNTIME_HOLDER = new ThreadLocal<>();

  private WorkerExecutionContext() {}

  /** Returns the {@link WorkerContext} for the currently executing worker, or {@code null}. */
  public static WorkerContext current() {
    return HOLDER.get();
  }

  /** Called by the engine before invoking a worker function. */
  public static void set(WorkerContext context) {
    HOLDER.set(context);
  }

  /** Returns the {@link WorkerRuntime} for the currently executing worker, or {@code null}. */
  public static WorkerRuntime currentRuntime() {
    return RUNTIME_HOLDER.get();
  }

  /** Called by the engine before invoking a worker function. */
  public static void setRuntime(WorkerRuntime runtime) {
    RUNTIME_HOLDER.set(runtime);
  }

  /** Called by the engine after the worker function returns (success or failure). */
  public static void clear() {
    HOLDER.remove();
    RUNTIME_HOLDER.remove();
  }
}
