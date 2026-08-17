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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoapPlannerTest {

  private final GoapPlanner planner = new GoapPlanner();

  @Test
  void plan_compound_goals() {
    var a1 = new GoapAction("a1", Map.of(), Map.of("x", true), 0.3);
    var a2 = new GoapAction("a2", Map.of(), Map.of("y", true), 0.5);
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, Set.of("x", "y"), List.of(a1, a2));
    assertThat(plan).extracting(GoapAction::name).containsExactlyInAnyOrder("a1", "a2");
  }

  @Test
  void plan_compound_goals_already_satisfied() {
    var a1 = new GoapAction("a1", Map.of(), Map.of("x", true), 0.3);
    var initial = new GoapWorldState(Map.of("x", true, "y", true));

    List<GoapAction> plan = planner.plan(initial, Set.of("x", "y"), List.of(a1));
    assertThat(plan).isEmpty();
  }

  @Test
  void plan_prefers_lower_effective_cost() {
    var cheap = new GoapAction("cheap", Map.of(), Map.of("x", true), 0.3, 0.5, Map.of());
    var expensive = new GoapAction("expensive", Map.of(), Map.of("x", true), 0.8, 0.0, Map.of());
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, Set.of("x"), List.of(cheap, expensive));
    assertThat(plan).extracting(GoapAction::name).containsExactly("cheap");
  }

  @Test
  void plan_chains_dependencies() {
    var a1 = new GoapAction("a1", Map.of(), Map.of("x", true), 0.3);
    var a2 = new GoapAction("a2", Map.of("x", true), Map.of("y", true), 0.5);
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, Set.of("y"), List.of(a1, a2));
    assertThat(plan).extracting(GoapAction::name).containsExactly("a1", "a2");
  }

  @Test
  void plan_soft_precondition_penalty() {
    var withSoft =
        new GoapAction("withSoft", Map.of(), Map.of("x", true), 0.5, 0.0, Map.of("optional", true));
    var withoutSoft =
        new GoapAction("withoutSoft", Map.of(), Map.of("x", true), 0.5, 0.0, Map.of());
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, Set.of("x"), List.of(withSoft, withoutSoft));
    assertThat(plan).extracting(GoapAction::name).containsExactly("withoutSoft");
  }

  @Test
  void plan_single_goal_backward_compat() {
    var a1 = new GoapAction("a1", Map.of(), Map.of("x", true), 0.3);
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, "x", List.of(a1));
    assertThat(plan).extracting(GoapAction::name).containsExactly("a1");
  }

  @Test
  void plan_unreachable_returns_empty() {
    var a1 = new GoapAction("a1", Map.of("missing", true), Map.of("x", true), 0.3);
    var initial = new GoapWorldState(Map.of());

    List<GoapAction> plan = planner.plan(initial, Set.of("x"), List.of(a1));
    assertThat(plan).isEmpty();
  }
}
