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
package io.casehub.engine.planning.decomposition;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.engine.plan.DagPlan;
import io.casehub.engine.plan.DecompositionContext;
import io.casehub.engine.plan.TaskNode;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ExplicitHtnDecompositionStrategy
    implements io.casehub.engine.plan.DecompositionStrategy<JsonNode> {

  @Override
  public String id() {
    return "explicit";
  }

  @Override
  public DagPlan<TaskNode.LeafTask<JsonNode>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) {
    if (!(task instanceof TaskNode.CompoundTask<JsonNode> compound)) {
      throw new IllegalArgumentException(
          "ExplicitHtnDecompositionStrategy requires a CompoundTask, got " + task.getClass());
    }
    var selectedMethod =
        compound.methods().stream()
            .filter(m -> m.guard().test(context.state()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No matching method for compound '"
                            + compound.name()
                            + "' — all "
                            + compound.methods().size()
                            + " method guards evaluated to false"));

    return selectedMethod.strategy().decompose(compound, context);
  }
}
