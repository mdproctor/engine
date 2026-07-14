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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public record DagPlan<T>(Map<String, DagNode<T>> nodes) {

  public DagPlan {
    Objects.requireNonNull(nodes, "nodes required");
    if (nodes.isEmpty()) throw new IllegalArgumentException("nodes must not be empty");
    nodes = Map.copyOf(nodes);
    validateReferences(nodes);
    validateNoCycles(nodes);
    if (computeEntryNodes(nodes).isEmpty())
      throw new IllegalArgumentException("plan must have at least one entry node");
  }

  public Set<String> entryNodeIds() {
    return computeEntryNodes(nodes);
  }

  public Set<String> exitNodeIds() {
    Set<String> referenced =
        nodes.values().stream().flatMap(n -> n.dependsOn().stream()).collect(Collectors.toSet());
    return nodes.keySet().stream()
        .filter(id -> !referenced.contains(id))
        .collect(Collectors.toUnmodifiableSet());
  }

  public List<DagNode<T>> topologicalSort() {
    Map<String, Integer> inDegree = new HashMap<>();
    nodes.keySet().forEach(id -> inDegree.put(id, 0));
    Map<String, List<String>> successors = new HashMap<>();
    for (var n : nodes.values()) {
      for (String dep : n.dependsOn()) {
        successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.id());
        inDegree.merge(n.id(), 1, Integer::sum);
      }
    }
    Queue<String> queue = new ArrayDeque<>();
    inDegree.forEach(
        (id, deg) -> {
          if (deg == 0) queue.add(id);
        });
    List<DagNode<T>> sorted = new ArrayList<>();
    while (!queue.isEmpty()) {
      String current = queue.poll();
      sorted.add(nodes.get(current));
      for (String succ : successors.getOrDefault(current, List.of())) {
        if (inDegree.merge(succ, -1, Integer::sum) == 0) queue.add(succ);
      }
    }
    return Collections.unmodifiableList(sorted);
  }

  public static <T> DagPlan<T> singleton(String id, T task) {
    return new DagPlan<>(Map.of(id, new DagNode<>(id, task, Set.of(), JoinType.ALL_OF)));
  }

  public static <T> DagPlan<T> sequence(List<DagNode<T>> nodes) {
    if (nodes.isEmpty()) throw new IllegalArgumentException("nodes must not be empty");
    Map<String, DagNode<T>> map = new LinkedHashMap<>();
    for (var node : nodes) map.put(node.id(), node);
    return new DagPlan<>(map);
  }

  public static <T> DagPlan<T> parallel(List<T> tasks) {
    if (tasks.isEmpty()) throw new IllegalArgumentException("tasks must not be empty");
    Map<String, DagNode<T>> map = new LinkedHashMap<>();
    for (int i = 0; i < tasks.size(); i++) {
      String id = "node-" + i;
      map.put(id, new DagNode<>(id, tasks.get(i), Set.of(), JoinType.ALL_OF));
    }
    return new DagPlan<>(map);
  }

  private static <T> void validateReferences(Map<String, DagNode<T>> nodes) {
    for (var node : nodes.values()) {
      for (String dep : node.dependsOn()) {
        if (!nodes.containsKey(dep))
          throw new IllegalArgumentException(
              "Node '" + node.id() + "' depends on non-existent node '" + dep + "'");
      }
    }
  }

  private static <T> void validateNoCycles(Map<String, DagNode<T>> nodes) {
    Map<String, Integer> inDegree = new HashMap<>();
    nodes.keySet().forEach(id -> inDegree.put(id, 0));
    Map<String, List<String>> successors = new HashMap<>();
    for (var n : nodes.values()) {
      for (String dep : n.dependsOn()) {
        successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.id());
        inDegree.merge(n.id(), 1, Integer::sum);
      }
    }
    Queue<String> queue = new ArrayDeque<>();
    inDegree.forEach(
        (id, deg) -> {
          if (deg == 0) queue.add(id);
        });
    int visited = 0;
    while (!queue.isEmpty()) {
      visited++;
      String current = queue.poll();
      for (String succ : successors.getOrDefault(current, List.of())) {
        if (inDegree.merge(succ, -1, Integer::sum) == 0) queue.add(succ);
      }
    }
    if (visited != nodes.size()) throw new IllegalArgumentException("Plan contains a cycle");
  }

  private static <T> Set<String> computeEntryNodes(Map<String, DagNode<T>> nodes) {
    return nodes.values().stream()
        .filter(n -> n.dependsOn().isEmpty())
        .map(DagNode::id)
        .collect(Collectors.toUnmodifiableSet());
  }
}
