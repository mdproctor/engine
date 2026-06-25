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
package io.casehub.engine.flow;

import io.casehub.worker.api.WorkerFunction;
import io.serverlessworkflow.api.types.Workflow;
import java.util.Objects;

/**
 * A {@link WorkerFunction} that executes a Serverless Workflow definition.
 *
 * <p>Moved from api to flow in issue #567 — Serverless Workflow SDK is flow-module-only. Consumers
 * never reference {@code FlowWorkerFunction} directly; it is constructed via {@link
 * FlowWorkerFunctionProvider} when YAML contains a {@code do:} block.
 */
public record FlowWorkerFunction(Workflow workflow) implements WorkerFunction {
  public FlowWorkerFunction {
    Objects.requireNonNull(workflow, "workflow must not be null");
  }
}
