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

public interface DagEventListener<T, R> {
  default void onNodeDispatched(String nodeId, T task) {}

  default void onNodeCompleted(String nodeId, T task, R result) {}

  default void onNodeFailed(String nodeId, T task, String reason, Throwable cause) {}

  default void onNodeSkipped(String nodeId, T task, String reason) {}

  default void onNodeCancelled(String nodeId, T task) {}

  default void onExecutionComplete(DagResult<R> result) {}
}
