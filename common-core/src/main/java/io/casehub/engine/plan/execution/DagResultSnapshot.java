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

import io.casehub.engine.plan.DagResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record DagResultSnapshot(
    Map<String, NodeStateSnapshot> nodeStates,
    Map<String, Object> completedResults,
    boolean allSucceeded,
    Duration elapsed,
    Instant timestamp,
    Map<String, Long> nodeDurationsMs) {

  public DagResultSnapshot(
      Map<String, NodeStateSnapshot> nodeStates,
      Map<String, Object> completedResults,
      boolean allSucceeded,
      Duration elapsed,
      Instant timestamp) {
    this(nodeStates, completedResults, allSucceeded, elapsed, timestamp, null);
  }

  public static DagResultSnapshot from(DagResult<?> result, Instant timestamp) {
    return from(result, timestamp, null);
  }

  public static DagResultSnapshot from(
      DagResult<?> result, Instant timestamp, Map<String, Long> nodeDurationsMs) {
    Map<String, NodeStateSnapshot> states = new LinkedHashMap<>();
    for (var entry : result.nodeStates().entrySet()) {
      states.put(entry.getKey(), NodeStateSnapshot.from(entry.getValue()));
    }
    Map<String, Object> completed = new LinkedHashMap<>();
    for (var entry : result.completedResults().entrySet()) {
      completed.put(entry.getKey(), entry.getValue());
    }
    return new DagResultSnapshot(
        Map.copyOf(states),
        Map.copyOf(completed),
        result.allSucceeded(),
        result.elapsed(),
        timestamp,
        nodeDurationsMs);
  }

  public static DagResultSnapshot from(
      DagResult<?> result,
      Instant timestamp,
      Map<String, Long> nodeDurationsMs,
      Map<String, DagResultSnapshot> contingencyResults) {
    Map<String, NodeStateSnapshot> states = new LinkedHashMap<>();
    for (var entry : result.nodeStates().entrySet()) {
      DagResultSnapshot cr =
          contingencyResults != null ? contingencyResults.get(entry.getKey()) : null;
      states.put(entry.getKey(), NodeStateSnapshot.from(entry.getValue(), cr));
    }
    Map<String, Object> completed = new LinkedHashMap<>();
    for (var entry : result.completedResults().entrySet()) {
      completed.put(entry.getKey(), entry.getValue());
    }
    return new DagResultSnapshot(
        Map.copyOf(states),
        Map.copyOf(completed),
        result.allSucceeded(),
        result.elapsed(),
        timestamp,
        nodeDurationsMs);
  }
}
