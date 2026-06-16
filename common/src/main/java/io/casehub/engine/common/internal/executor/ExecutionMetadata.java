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

/**
 * Lineage metadata for worker execution. Carries the worker name and input data hash needed by the
 * flow path ({@code FlowExecutionRegistry}) but meaningless for sync/agent execution.
 *
 * <p>Kept separate from {@code WorkerContext} (which is an API type visible to worker functions) to
 * avoid polluting the API with engine internals.
 *
 * <p>Refs casehubio/engine#463.
 */
public record ExecutionMetadata(String workerName, String inputDataHash) {}
