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

import io.casehub.platform.api.routing.NamedStrategy;

public interface DecompositionStrategy<T> extends NamedStrategy {
  DagPlan<TaskNode.LeafTask<T>> decompose(TaskNode<T> task, DecompositionContext<T> context);

  default DagPlan<TaskNode.LeafTask<T>> replan(
      TaskNode<T> task, DecompositionContext<T> context, ReplanContext<T> replanContext) {
    throw new UnsupportedOperationException("Re-planning not supported by " + id());
  }

  @Override
  default String id() {
    return "identity";
  }
}
