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
package io.casehub.engine.planning.plan;

import io.casehub.api.model.ExecutorRef;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.List;
import java.util.Objects;

public sealed interface PlanItemDefinition
    permits PlanItemDefinition.Primitive, PlanItemDefinition.Compound {

  String id();

  String name();

  DispatchMode dispatchMode();

  record Primitive(
      String id,
      String name,
      ExecutorRef executor,
      DispatchMode dispatchMode,
      ExpressionEvaluator entryCondition)
      implements PlanItemDefinition {
    public Primitive {
      Objects.requireNonNull(id, "id required");
      Objects.requireNonNull(name, "name required");
      Objects.requireNonNull(executor, "executor required");
      Objects.requireNonNull(dispatchMode, "dispatchMode required");
    }
  }

  record Compound(
      String id,
      String name,
      List<PlanItemDefinition> children,
      String planningStrategy,
      CompletionSemantics completion,
      DispatchMode dispatchMode,
      ExpressionEvaluator entryCondition,
      ExpressionEvaluator exitCondition,
      boolean repeatable)
      implements PlanItemDefinition {
    public Compound {
      Objects.requireNonNull(id, "id required");
      Objects.requireNonNull(name, "name required");
      Objects.requireNonNull(dispatchMode, "dispatchMode required");
      Objects.requireNonNull(completion, "completion required");
      children = children != null ? List.copyOf(children) : List.of();
    }
  }
}
