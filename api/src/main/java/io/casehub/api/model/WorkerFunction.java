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

import io.casehub.api.model.ai.Agent;
import io.serverlessworkflow.api.types.Workflow;
import java.util.Map;
import java.util.function.Function;

/**
 * Sealed type hierarchy for worker function types. Replaces {@code WorkerFunctionHolder<T>} with
 * compile-time exhaustive dispatch — every {@code switch} on {@code WorkerFunction} must handle all
 * variants.
 *
 * <p>Three variants capture the execution model distinction:
 *
 * <ul>
 *   <li>{@link Sync} — synchronous function, timeout-bounded, on a virtual thread
 *   <li>{@link AgentExec} — LLM agent, same execution model as Sync but preserves Agent metadata
 *       for routing and observability
 *   <li>{@link Flow} — asynchronous workflow, delegated to {@code WorkflowExecutor}
 * </ul>
 *
 * <p>Refs casehubio/engine#463.
 */
public sealed interface WorkerFunction
    permits WorkerFunction.Sync, WorkerFunction.AgentExec, WorkerFunction.Flow {

  record Sync(Function<Map<String, Object>, WorkerResult> fn) implements WorkerFunction {}

  record AgentExec(Agent agent) implements WorkerFunction {}

  record Flow(Workflow workflow) implements WorkerFunction {}
}
