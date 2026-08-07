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
import io.casehub.engine.plan.DecompositionMethod;
import io.casehub.engine.plan.TaskNode;
import java.time.Instant;
import java.util.List;

public record DecompositionSnapshot(TaskNodeSnapshot root, Instant timestamp) {

  public static DecompositionSnapshot from(TaskNode<?> root, Instant timestamp) {
    return new DecompositionSnapshot(toSnapshot(root), timestamp);
  }

  private static TaskNodeSnapshot toSnapshot(TaskNode<?> node) {
    return switch (node) {
      case TaskNode.LeafTask<?> leaf -> {
        String id = (leaf instanceof TaskDescriptor td) ? td.id() : null;
        String desc = (leaf instanceof TaskDescriptor td) ? td.description() : null;
        ExecutorRef exec = (leaf instanceof TaskDescriptor td) ? td.executor() : null;
        yield new LeafTaskSnapshot(id, desc, exec != null ? exec.name() : null);
      }
      case TaskNode.CompoundTask<?> ct -> {
        var methods = ct.methods().stream().map(DecompositionSnapshot::toMethodSnapshot).toList();
        yield new CompoundTaskSnapshot(ct.id(), ct.name(), methods);
      }
    };
  }

  private static DecompositionMethodSnapshot toMethodSnapshot(DecompositionMethod<?> method) {
    String strategyId = method.strategy() != null ? method.strategy().id() : null;
    return new DecompositionMethodSnapshot(method.guardLabel(), strategyId, List.of());
  }
}
