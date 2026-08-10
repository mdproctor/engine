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

import io.casehub.api.model.TaskDescriptor;
import java.util.List;
import java.util.Objects;

public sealed interface TaskNode<T> permits TaskNode.LeafTask, TaskNode.CompoundTask {

  non-sealed interface LeafTask<T> extends TaskNode<T>, TaskDescriptor {}

  record CompoundTask<T>(String id, String name, List<DecompositionMethod<T>> methods)
      implements TaskNode<T> {
    public CompoundTask {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      methods = List.copyOf(methods);
    }

    public CompoundTask(String name, List<DecompositionMethod<T>> methods) {
      this(java.util.UUID.randomUUID().toString(), name, methods);
    }
  }
}
