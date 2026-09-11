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
package io.casehub.engine.plan.execution;

import io.casehub.engine.plan.NodeState;

public record NodeStateSnapshot(String kind, String reason, DagResultSnapshot contingencyResult) {

  public NodeStateSnapshot(String kind, String reason) {
    this(kind, reason, null);
  }

  public static NodeStateSnapshot from(NodeState<?> state) {
    return from(state, null);
  }

  public static NodeStateSnapshot from(NodeState<?> state, DagResultSnapshot contingencyResult) {
    return switch (state) {
      case NodeState.Pending<?> p -> new NodeStateSnapshot("Pending", null, contingencyResult);
      case NodeState.Dispatched<?> d ->
          new NodeStateSnapshot("Dispatched", null, contingencyResult);
      case NodeState.Completed<?> c -> new NodeStateSnapshot("Completed", null, contingencyResult);
      case NodeState.Failed<?> f -> new NodeStateSnapshot("Failed", f.reason(), contingencyResult);
      case NodeState.Skipped<?> s ->
          new NodeStateSnapshot("Skipped", s.reason(), contingencyResult);
      case NodeState.Cancelled<?> x -> new NodeStateSnapshot("Cancelled", null, contingencyResult);
    };
  }
}
