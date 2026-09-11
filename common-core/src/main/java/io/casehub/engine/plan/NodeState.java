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

import io.casehub.api.model.TaskStatus;

public sealed interface NodeState<R> {
  record Pending<R>() implements NodeState<R> {}

  record Dispatched<R>() implements NodeState<R> {}

  record Completed<R>(R result) implements NodeState<R> {}

  record Failed<R>(String reason, Throwable cause) implements NodeState<R> {}

  record Skipped<R>(String reason) implements NodeState<R> {}

  record Cancelled<R>() implements NodeState<R> {}

  default boolean isTerminal() {
    return this instanceof Completed
        || this instanceof Failed
        || this instanceof Skipped
        || this instanceof Cancelled;
  }

  default TaskStatus toTaskStatus() {
    return switch (this) {
      case Pending<?> p -> TaskStatus.PENDING;
      case Dispatched<?> d -> TaskStatus.RUNNING;
      case Completed<?> c -> TaskStatus.COMPLETED;
      case Failed<?> f -> TaskStatus.FAULTED;
      case Skipped<?> s -> TaskStatus.OBSOLETE;
      case Cancelled<?> x -> TaskStatus.CANCELLED;
    };
  }
}
