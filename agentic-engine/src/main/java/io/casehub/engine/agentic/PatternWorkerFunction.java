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

import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.PatternType;
import io.casehub.engine.plan.PlanningConstraints;
import io.casehub.engine.plan.TaskNode;
import io.casehub.worker.api.WorkerFunction;
import java.util.Map;

public record PatternWorkerFunction(
    ExecutionModel<?> model,
    PatternType patternType,
    boolean checkpointingEnabled,
    PlanningConstraints planningConstraints,
    TaskNode<?> rootTask)
    implements WorkerFunction<Map, Map> {

  public PatternWorkerFunction(
      ExecutionModel<?> model, PatternType patternType, boolean checkpointingEnabled) {
    this(model, patternType, checkpointingEnabled, null, null);
  }

  public PatternWorkerFunction(
      ExecutionModel<?> model,
      PatternType patternType,
      boolean checkpointingEnabled,
      PlanningConstraints planningConstraints) {
    this(model, patternType, checkpointingEnabled, planningConstraints, null);
  }

  @Override
  public Class<Map> inputType() {
    return Map.class;
  }

  @Override
  public Class<Map> outputType() {
    return Map.class;
  }
}
