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
package io.casehub.engine.plan.snapshot;

import io.casehub.api.model.ExecutorRef;
import io.casehub.api.model.TaskDescriptor;
import io.casehub.engine.plan.DagNode;
import io.casehub.engine.plan.DagPlan;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record DagPlanSnapshot(Map<String, DagNodeSnapshot> nodes, Instant timestamp) {

  public static DagPlanSnapshot from(DagPlan<?> plan, Instant timestamp) {
    Map<String, DagNodeSnapshot> snapshotNodes = new LinkedHashMap<>();
    for (var entry : plan.nodes().entrySet()) {
      DagNode<?> node = entry.getValue();
      String taskId = null;
      String taskDesc = null;
      String execName = null;
      Object task = node.task();
      if (task instanceof TaskDescriptor td) {
        taskId = td.id();
        taskDesc = td.description();
        ExecutorRef exec = td.executor();
        execName = exec != null ? exec.name() : null;
      }
      snapshotNodes.put(
          entry.getKey(),
          new DagNodeSnapshot(
              node.id(), taskId, taskDesc, execName, node.dependsOn(), node.joinType()));
    }
    return new DagPlanSnapshot(Map.copyOf(snapshotNodes), timestamp);
  }
}
