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
package io.casehub.engine.plan;

import java.util.Objects;
import java.util.Set;

public record DagNode<T>(
    String id,
    T task,
    Set<String> dependsOn,
    JoinType joinType,
    DagPlan<T> contingency,
    io.casehub.api.model.JudgmentTarget judgment) {
  public DagNode {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(task, "task required");
    dependsOn = dependsOn != null ? Set.copyOf(dependsOn) : Set.of();
    if (joinType == null) {
      joinType = JoinType.ALL_OF;
    }
    if (contingency != null && contingency.exitNodeIds().size() > 1) {
      throw new IllegalArgumentException(
          "Contingency plan must have a single exit node, got " + contingency.exitNodeIds().size());
    }
  }

  public DagNode(
      String id, T task, Set<String> dependsOn, JoinType joinType, DagPlan<T> contingency) {
    this(id, task, dependsOn, joinType, contingency, null);
  }

  public DagNode(String id, T task, Set<String> dependsOn, JoinType joinType) {
    this(id, task, dependsOn, joinType, null, null);
  }
}
