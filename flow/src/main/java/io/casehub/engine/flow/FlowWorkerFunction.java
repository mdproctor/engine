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

@SuppressWarnings("unchecked")
public record FlowWorkerFunction(Workflow workflow)
    implements WorkerFunction<java.util.Map<String, Object>, java.util.Map<String, Object>> {
  public FlowWorkerFunction {
    java.util.Objects.requireNonNull(workflow, "workflow must not be null");
  }

  @Override
  public Class<java.util.Map<String, Object>> inputType() {
    return (Class) java.util.Map.class;
  }

  @Override
  public Class<java.util.Map<String, Object>> outputType() {
    return (Class) java.util.Map.class;
  }
}
