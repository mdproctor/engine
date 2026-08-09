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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.spi.WorkerFunctionProvider;
import io.casehub.blocks.agentic.model.PatternType;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PatternWorkerFunctionProvider implements WorkerFunctionProvider {

  @Override
  public boolean handles(JsonNode rawWorkerNode) {
    return rawWorkerNode.has("pattern");
  }

  @Override
  public WorkerFunction<?, ?> create(JsonNode rawWorkerNode) {
    JsonNode patternNode = rawWorkerNode.get("pattern");
    String typeName = patternNode.path("type").asText("sequence");
    PatternType patternType = PatternType.valueOf(typeName.toUpperCase());
    boolean checkpointing = patternNode.path("checkpointing").asBoolean(false);

    io.casehub.engine.plan.PlanningConstraints constraints = null;
    if (patternNode.has("constraints")) {
      JsonNode cNode = patternNode.get("constraints");
      java.time.Duration timeBudget =
          cNode.has("timeBudget")
              ? java.time.Duration.parse(cNode.get("timeBudget").asText())
              : null;
      Integer resourceLimit =
          cNode.has("resourceLimit") ? cNode.get("resourceLimit").asInt() : null;
      constraints = io.casehub.engine.plan.PlanningConstraints.of(timeBudget, resourceLimit);
    }

    return new PatternWorkerFunction(null, patternType, checkpointing, constraints);
  }
}
