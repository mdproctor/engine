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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GoalExpression")
class GoalExpressionTest {

  @Nested
  @DisplayName("SingleGoalExpression")
  class SingleTests {

    @Test
    @DisplayName("satisfied when goal name is in reached set")
    void satisfied() {
      var expr = GoalExpression.goal("approved");
      assertTrue(expr.isSatisfiedBy(Set.of("approved", "verified")));
    }

    @Test
    @DisplayName("not satisfied when goal name is absent")
    void notSatisfied() {
      var expr = GoalExpression.goal("approved");
      assertFalse(expr.isSatisfiedBy(Set.of("verified")));
    }

    @Test
    @DisplayName("goalNames returns singleton")
    void goalNames() {
      assertEquals(Set.of("approved"), GoalExpression.goal("approved").goalNames());
    }

    @Test
    @DisplayName("satisfiedGoalName returns name when present, null when absent")
    void satisfiedGoalName() {
      var expr = GoalExpression.goal("approved");
      assertEquals("approved", expr.satisfiedGoalName(Set.of("approved")));
      assertNull(expr.satisfiedGoalName(Set.of("other")));
    }

    @Test
    @DisplayName("rejects null goal name")
    void rejectsNull() {
      assertThrows(NullPointerException.class, () -> new SingleGoalExpression(null));
    }
  }

  @Nested
  @DisplayName("AllOfGoalExpression")
  class AllOfTests {

    @Test
    @DisplayName("satisfied when all children are satisfied")
    void allSatisfied() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertTrue(expr.isSatisfiedBy(Set.of("a", "b", "c")));
    }

    @Test
    @DisplayName("not satisfied when only partial children match")
    void partialMatch() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertFalse(expr.isSatisfiedBy(Set.of("a")));
    }

    @Test
    @DisplayName("not satisfied when no children match")
    void noneMatch() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertFalse(expr.isSatisfiedBy(Set.of("x", "y")));
    }

    @Test
    @DisplayName("satisfiedGoalName returns first child name when all satisfied")
    void satisfiedGoalName_allPresent() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertEquals("a", expr.satisfiedGoalName(Set.of("a", "b")));
    }

    @Test
    @DisplayName("satisfiedGoalName returns null when partial")
    void satisfiedGoalName_partial() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertNull(expr.satisfiedGoalName(Set.of("a")));
    }

    @Test
    @DisplayName("goalNames returns union of all children")
    void goalNames() {
      var expr = GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertEquals(Set.of("a", "b"), expr.goalNames());
    }

    @Test
    @DisplayName("rejects empty children")
    void emptyChildren() {
      assertThrows(IllegalArgumentException.class, () -> new AllOfGoalExpression(List.of()));
    }
  }

  @Nested
  @DisplayName("AnyOfGoalExpression")
  class AnyOfTests {

    @Test
    @DisplayName("satisfied when any child is satisfied")
    void anySatisfied() {
      var expr = GoalExpression.anyOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertTrue(expr.isSatisfiedBy(Set.of("b")));
    }

    @Test
    @DisplayName("not satisfied when no child matches")
    void noneMatch() {
      var expr = GoalExpression.anyOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertFalse(expr.isSatisfiedBy(Set.of("x")));
    }

    @Test
    @DisplayName("satisfiedGoalName returns first satisfied child name")
    void satisfiedGoalName() {
      var expr = GoalExpression.anyOf(GoalExpression.goal("a"), GoalExpression.goal("b"));
      assertEquals("b", expr.satisfiedGoalName(Set.of("b")));
    }

    @Test
    @DisplayName("rejects empty children")
    void emptyChildren() {
      assertThrows(IllegalArgumentException.class, () -> new AnyOfGoalExpression(List.of()));
    }
  }

  @Nested
  @DisplayName("Composed expressions")
  class ComposedTests {

    @Test
    @DisplayName("anyOf(allOf(a,b,c), goal(d)) — d alone satisfies")
    void anyOf_singleLeaf() {
      var expr =
          GoalExpression.anyOf(
              GoalExpression.allOf(
                  GoalExpression.goal("a"), GoalExpression.goal("b"), GoalExpression.goal("c")),
              GoalExpression.goal("d"));
      assertTrue(expr.isSatisfiedBy(Set.of("d")));
      assertEquals("d", expr.satisfiedGoalName(Set.of("d")));
    }

    @Test
    @DisplayName("anyOf(allOf(a,b,c), goal(d)) — a,b,c satisfies via allOf branch")
    void anyOf_allOfBranch() {
      var expr =
          GoalExpression.anyOf(
              GoalExpression.allOf(
                  GoalExpression.goal("a"), GoalExpression.goal("b"), GoalExpression.goal("c")),
              GoalExpression.goal("d"));
      assertTrue(expr.isSatisfiedBy(Set.of("a", "b", "c")));
      assertEquals("a", expr.satisfiedGoalName(Set.of("a", "b", "c")));
    }

    @Test
    @DisplayName("anyOf(allOf(a,b,c), goal(d)) — partial allOf, no d → not satisfied")
    void anyOf_partial() {
      var expr =
          GoalExpression.anyOf(
              GoalExpression.allOf(
                  GoalExpression.goal("a"), GoalExpression.goal("b"), GoalExpression.goal("c")),
              GoalExpression.goal("d"));
      assertFalse(expr.isSatisfiedBy(Set.of("a", "b")));
      assertNull(expr.satisfiedGoalName(Set.of("a", "b")));
    }

    @Test
    @DisplayName("allOf(anyOf(a,b), goal(c)) — a and c satisfies")
    void allOf_anyOf() {
      var expr =
          GoalExpression.allOf(
              GoalExpression.anyOf(GoalExpression.goal("a"), GoalExpression.goal("b")),
              GoalExpression.goal("c"));
      assertTrue(expr.isSatisfiedBy(Set.of("a", "c")));
      assertEquals("a", expr.satisfiedGoalName(Set.of("a", "c")));
    }

    @Test
    @DisplayName("deeply nested goalNames collects all leaves")
    void goalNames_deep() {
      var expr =
          GoalExpression.anyOf(
              GoalExpression.allOf(GoalExpression.goal("a"), GoalExpression.goal("b")),
              GoalExpression.goal("c"));
      assertEquals(Set.of("a", "b", "c"), expr.goalNames());
    }
  }

  @Nested
  @DisplayName("Backward-compatible factories")
  class FactoryTests {

    @Test
    @DisplayName("allOf(Goal...) extracts names into SingleGoalExpression children")
    void allOfGoals() {
      Goal g1 = Goal.builder().name("a").condition(".a").kind(GoalKind.SUCCESS).build();
      Goal g2 = Goal.builder().name("b").condition(".b").kind(GoalKind.SUCCESS).build();
      var expr = GoalExpression.allOf(g1, g2);
      assertTrue(expr.isSatisfiedBy(Set.of("a", "b")));
      assertFalse(expr.isSatisfiedBy(Set.of("a")));
      assertEquals(Set.of("a", "b"), expr.goalNames());
    }

    @Test
    @DisplayName("anyOf(Goal...) extracts names into SingleGoalExpression children")
    void anyOfGoals() {
      Goal g1 = Goal.builder().name("a").condition(".a").kind(GoalKind.SUCCESS).build();
      var expr = GoalExpression.anyOf(g1);
      assertTrue(expr.isSatisfiedBy(Set.of("a")));
      assertFalse(expr.isSatisfiedBy(Set.of("b")));
    }

    @Test
    @DisplayName("goal(String) creates SingleGoalExpression")
    void goalFactory() {
      var expr = GoalExpression.goal("x");
      assertInstanceOf(SingleGoalExpression.class, expr);
      assertEquals(Set.of("x"), expr.goalNames());
    }

    @Test
    @DisplayName("allOf(Collection<Goal>) extracts names correctly")
    void allOfCollection() {
      Goal g1 = Goal.builder().name("a").condition(".a").kind(GoalKind.SUCCESS).build();
      Goal g2 = Goal.builder().name("b").condition(".b").kind(GoalKind.SUCCESS).build();
      var expr = GoalExpression.allOf(List.of(g1, g2));
      assertTrue(expr.isSatisfiedBy(Set.of("a", "b")));
      assertFalse(expr.isSatisfiedBy(Set.of("a")));
    }
  }
}
