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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GoalBasedCompletionTest {

  private final Goal goalA =
      Goal.builder().name("a").condition(".a == true").kind(GoalKind.SUCCESS).build();
  private final Goal goalB =
      Goal.builder().name("b").condition(".b == true").kind(GoalKind.FAILURE).build();

  @Nested
  @DisplayName("Builder")
  class BuilderTests {

    @Test
    @DisplayName("single goal kind creates completion with one entry")
    void singleKind() {
      var completion =
          GoalBasedCompletion.<StandardGoalKind>builder()
              .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(goalA))
              .build();
      assertEquals(1, completion.getGoals().size());
      assertNotNull(completion.getGoals().get(StandardGoalKind.SUCCESS));
    }

    @Test
    @DisplayName("multiple goal kinds preserve insertion order")
    void insertionOrderPreserved() {
      var completion =
          GoalBasedCompletion.<StandardGoalKind>builder()
              .goal(StandardGoalKind.FAILURE, GoalExpression.anyOf(goalB))
              .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(goalA))
              .build();

      List<GoalKind> keys = new ArrayList<>(completion.getGoals().keySet());
      assertEquals(StandardGoalKind.FAILURE, keys.get(0));
      assertEquals(StandardGoalKind.SUCCESS, keys.get(1));
    }

    @Test
    @DisplayName("duplicate goal kind throws IllegalStateException")
    void duplicateKind_throws() {
      var builder =
          GoalBasedCompletion.<StandardGoalKind>builder()
              .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(goalA));
      assertThrows(
          IllegalStateException.class,
          () -> builder.goal(StandardGoalKind.SUCCESS, GoalExpression.anyOf(goalB)));
    }

    @Test
    @DisplayName("empty builder throws IllegalStateException")
    void emptyBuilder_throws() {
      assertThrows(
          IllegalStateException.class,
          () -> GoalBasedCompletion.<StandardGoalKind>builder().build());
    }

    @Test
    @DisplayName("null kind throws NullPointerException")
    void nullKind_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              GoalBasedCompletion.<StandardGoalKind>builder()
                  .goal(null, GoalExpression.allOf(goalA)));
    }

    @Test
    @DisplayName("null expression throws NullPointerException")
    void nullExpression_throws() {
      assertThrows(
          NullPointerException.class,
          () ->
              GoalBasedCompletion.<StandardGoalKind>builder().goal(StandardGoalKind.SUCCESS, null));
    }

    @Test
    @DisplayName("goals map is unmodifiable")
    void goalsMapUnmodifiable() {
      var completion =
          GoalBasedCompletion.<StandardGoalKind>builder()
              .goal(StandardGoalKind.SUCCESS, GoalExpression.allOf(goalA))
              .build();
      assertThrows(
          UnsupportedOperationException.class,
          () -> completion.getGoals().put(StandardGoalKind.FAILURE, GoalExpression.anyOf(goalB)));
    }

    @Test
    @DisplayName("custom GoalKind works as map key")
    void customGoalKind() {
      GoalKind escalated = GoalKind.of("escalated", CaseStatus.FAULTED);
      var completion =
          GoalBasedCompletion.builder().goal(escalated, GoalExpression.allOf(goalA)).build();
      assertEquals(1, completion.getGoals().size());
      assertNotNull(completion.getGoals().get(GoalKind.of("escalated", CaseStatus.FAULTED)));
    }
  }
}
