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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record AllOfGoalExpression(List<GoalExpression> children) implements GoalExpression {

  public AllOfGoalExpression {
    if (children.isEmpty()) {
      throw new IllegalArgumentException("AllOfGoalExpression requires at least one child");
    }
    children = List.copyOf(children);
  }

  @Override
  public boolean isSatisfiedBy(Set<String> reachedGoalNames) {
    return children.stream().allMatch(c -> c.isSatisfiedBy(reachedGoalNames));
  }

  @Override
  public Set<String> goalNames() {
    return children.stream()
        .flatMap(c -> c.goalNames().stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public String satisfiedGoalName(Set<String> reachedGoalNames) {
    String firstName = null;
    for (GoalExpression child : children) {
      String name = child.satisfiedGoalName(reachedGoalNames);
      if (name == null) return null;
      if (firstName == null) firstName = name;
    }
    return firstName;
  }
}
