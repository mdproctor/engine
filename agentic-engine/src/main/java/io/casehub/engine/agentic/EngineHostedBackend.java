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
package io.casehub.engine.agentic;

import io.casehub.blocks.agentic.model.ExecutionBackend;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.ExecutionResult;
import io.smallrye.mutiny.Uni;

public class EngineHostedBackend<T> implements ExecutionBackend<T> {

  @Override
  public Uni<ExecutionResult> execute(ExecutionModel<T> model, T initialContext) {
    throw new UnsupportedOperationException(
        "EngineHostedBackend requires engine runtime — use PatternWorkerFunction "
            + "via CaseDefinition YAML, not the programmatic builder API directly. "
            + "For programmatic use, call ExecutionBackend.reactive() explicitly.");
  }
}
