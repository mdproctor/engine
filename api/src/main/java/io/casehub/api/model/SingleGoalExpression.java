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

import java.util.Objects;
import java.util.Set;

public record SingleGoalExpression(String goalName) implements GoalExpression {

  public SingleGoalExpression {
    Objects.requireNonNull(goalName, "goalName must not be null");
  }

  @Override
  public boolean isSatisfiedBy(Set<String> reachedGoalNames) {
    return reachedGoalNames.contains(goalName);
  }

  @Override
  public Set<String> goalNames() {
    return Set.of(goalName);
  }

  @Override
  public String satisfiedGoalName(Set<String> reachedGoalNames) {
    return reachedGoalNames.contains(goalName) ? goalName : null;
  }
}
