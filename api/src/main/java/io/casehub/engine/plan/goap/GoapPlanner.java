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
package io.casehub.engine.plan.goap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Classic GOAP planner using A* search over boolean world state.
 *
 * <p>Given an initial world state, a goal condition, and a set of available actions, finds the
 * cheapest action sequence that reaches the goal. Returns an empty list if the goal is already
 * satisfied or unreachable.
 */
public class GoapPlanner {

  /**
   * A* search over world state nodes.
   *
   * @param initial starting world state
   * @param goalCondition boolean key that must be true in the goal state
   * @param actions all available actions (planner picks applicable ones)
   * @return cheapest action sequence, or empty list if goal already satisfied or unreachable
   */
  public List<GoapAction> plan(
      GoapWorldState initial, String goalCondition, List<GoapAction> actions) {
    if (initial.satisfies(goalCondition)) return List.of();

    record Node(GoapWorldState state, List<GoapAction> plan, int cost) {}

    PriorityQueue<Node> open =
        new PriorityQueue<>(
            Comparator.comparingInt(n -> n.cost() + heuristic(n.state(), goalCondition)));
    open.add(new Node(initial, List.of(), 0));

    Set<Map<String, Boolean>> visited = new HashSet<>();

    while (!open.isEmpty()) {
      Node current = open.poll();
      if (current.state().satisfies(goalCondition)) return current.plan();
      if (!visited.add(current.state().conditions())) continue;

      for (GoapAction action : actions) {
        if (action.isApplicable(current.state())) {
          GoapWorldState next = action.applyTo(current.state());
          List<GoapAction> newPlan = new ArrayList<>(current.plan());
          newPlan.add(action);
          open.add(new Node(next, newPlan, current.cost() + action.cost()));
        }
      }
    }
    return List.of();
  }

  private int heuristic(GoapWorldState state, String goalCondition) {
    return state.satisfies(goalCondition) ? 0 : 1;
  }
}
