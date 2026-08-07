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
import io.casehub.api.model.TaskSnapshot;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.plan.TaskNode;
import java.time.Instant;

record TestLeafTask(String id, String description, String executorName)
    implements TaskNode.LeafTask<TestLeafTask> {

  @Override
  public ExecutorRef executor() {
    return executorName != null ? ExecutorRef.of(executorName, null) : null;
  }

  @Override
  public TaskStatus status() {
    return TaskStatus.PENDING;
  }

  @Override
  public Instant createdAt() {
    return Instant.now();
  }

  @Override
  public TaskSnapshot snapshot() {
    return null;
  }
}
