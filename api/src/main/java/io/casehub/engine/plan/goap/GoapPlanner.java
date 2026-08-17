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
    return plan(initial, Set.of(goalCondition), actions);
  }

  public List<GoapAction> plan(
      GoapWorldState initial, Set<String> goalConditions, List<GoapAction> actions) {
    if (goalConditions.isEmpty() || initial.satisfiesAll(goalConditions)) return List.of();

    record Node(GoapWorldState state, List<GoapAction> plan, double cost) {}

    PriorityQueue<Node> open =
        new PriorityQueue<>(
            Comparator.comparingDouble(n -> n.cost() + heuristic(n.state(), goalConditions)));
    open.add(new Node(initial, List.of(), 0.0));

    Set<Map<String, Boolean>> visited = new HashSet<>();

    while (!open.isEmpty()) {
      Node current = open.poll();
      if (current.state().satisfiesAll(goalConditions)) return current.plan();
      if (!visited.add(current.state().conditions())) continue;

      for (GoapAction action : actions) {
        if (action.isApplicable(current.state())) {
          GoapWorldState next = action.applyTo(current.state());
          List<GoapAction> newPlan = new ArrayList<>(current.plan());
          newPlan.add(action);
          double softPenalty = softPenalty(action, current.state());
          open.add(new Node(next, newPlan, current.cost() + action.effectiveCost() + softPenalty));
        }
      }
    }
    return List.of();
  }

  private double softPenalty(GoapAction action, GoapWorldState state) {
    long unsatisfied =
        action.softPreconditions().entrySet().stream()
            .filter(e -> state.get(e.getKey()) != e.getValue())
            .count();
    if (unsatisfied == 0) return 0.0;
    return Math.max(0.5 * action.cost(), 0.1);
  }

  private double heuristic(GoapWorldState state, Set<String> goalConditions) {
    return goalConditions.stream().filter(c -> !state.satisfies(c)).count();
  }
}
