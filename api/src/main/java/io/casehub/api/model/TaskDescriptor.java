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

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Shared behavioral interface for any coordination model's unit of work. Implemented by engine's
 * {@code PlanItem} and (deferred) blocks' {@code PlannedTask}.
 */
public interface TaskDescriptor {

  String id();

  @Nullable
  String description();

  @Nullable
  ExecutorRef executor();

  TaskStatus status();

  Instant createdAt();

  default TaskSnapshot snapshot() {
    return new TaskSnapshot(
        id(),
        description(),
        executor() != null ? executor().name() : null,
        executor() != null ? executor().description() : null,
        status(),
        createdAt());
  }
}
