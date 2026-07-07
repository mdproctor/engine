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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class GoalBasedCompletion<K extends GoalKind> implements CaseCompletion {

  private final LinkedHashMap<K, GoalExpression> goals;

  private GoalBasedCompletion(LinkedHashMap<K, GoalExpression> goals) {
    this.goals = goals;
  }

  public Map<K, GoalExpression> getGoals() {
    return Collections.unmodifiableMap(goals);
  }

  public static <K extends GoalKind> Builder<K> builder() {
    return new Builder<>();
  }

  public static class Builder<K extends GoalKind> {
    private final LinkedHashMap<K, GoalExpression> goals = new LinkedHashMap<>();

    public Builder<K> goal(K kind, GoalExpression expression) {
      Objects.requireNonNull(kind, "kind must not be null");
      Objects.requireNonNull(expression, "expression must not be null");
      if (goals.containsKey(kind)) {
        throw new IllegalStateException("Duplicate goal kind: " + kind.value());
      }
      goals.put(kind, expression);
      return this;
    }

    public GoalBasedCompletion<K> build() {
      if (goals.isEmpty()) {
        throw new IllegalStateException("GoalBasedCompletion requires at least one goal kind");
      }
      return new GoalBasedCompletion<>(new LinkedHashMap<>(goals));
    }
  }
}
