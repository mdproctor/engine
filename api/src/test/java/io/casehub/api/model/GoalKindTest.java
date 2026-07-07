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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GoalKindTest {

  @Nested
  @DisplayName("GoalKind interface constants")
  class InterfaceConstantsTests {

    @Test
    @DisplayName("GoalKind.SUCCESS delegates to StandardGoalKind.SUCCESS")
    void success_delegatesToStandard() {
      assertSame(StandardGoalKind.SUCCESS, GoalKind.SUCCESS);
      assertEquals("success", GoalKind.SUCCESS.value());
      assertEquals(CaseStatus.COMPLETED, GoalKind.SUCCESS.terminalStatus());
    }

    @Test
    @DisplayName("GoalKind.FAILURE delegates to StandardGoalKind.FAILURE")
    void failure_delegatesToStandard() {
      assertSame(StandardGoalKind.FAILURE, GoalKind.FAILURE);
      assertEquals("failure", GoalKind.FAILURE.value());
      assertEquals(CaseStatus.FAULTED, GoalKind.FAILURE.terminalStatus());
    }
  }

  @Nested
  @DisplayName("GoalKind.of() factory")
  class OfFactoryTests {

    @Test
    @DisplayName("creates custom GoalKind with correct value and status")
    void of_createsCustomKind() {
      GoalKind escalated = GoalKind.of("escalated", CaseStatus.FAULTED);
      assertEquals("escalated", escalated.value());
      assertEquals(CaseStatus.FAULTED, escalated.terminalStatus());
    }

    @Test
    @DisplayName("null value throws NullPointerException")
    void of_nullValue_throws() {
      assertThrows(NullPointerException.class, () -> GoalKind.of(null, CaseStatus.FAULTED));
    }

    @Test
    @DisplayName("null terminalStatus throws NullPointerException")
    void of_nullStatus_throws() {
      assertThrows(NullPointerException.class, () -> GoalKind.of("escalated", null));
    }

    @Test
    @DisplayName("two custom kinds with same value and status are equal")
    void of_sameValueAndStatus_equal() {
      GoalKind a = GoalKind.of("escalated", CaseStatus.FAULTED);
      GoalKind b = GoalKind.of("escalated", CaseStatus.FAULTED);
      assertEquals(a, b);
      assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("custom kinds with different values are not equal")
    void of_differentValues_notEqual() {
      GoalKind a = GoalKind.of("escalated", CaseStatus.FAULTED);
      GoalKind b = GoalKind.of("referred", CaseStatus.FAULTED);
      assertNotEquals(a, b);
    }

    @Test
    @DisplayName("CANCELLED terminal status throws IllegalArgumentException")
    void of_cancelledStatus_throws() {
      assertThrows(
          IllegalArgumentException.class, () -> GoalKind.of("withdrawn", CaseStatus.CANCELLED));
    }
  }

  @Nested
  @DisplayName("GoalKind.fromValue()")
  class FromValueTests {

    @Test
    @DisplayName("'success' returns StandardGoalKind.SUCCESS")
    void success_returnsStandard() {
      assertEquals(StandardGoalKind.SUCCESS, GoalKind.fromValue("success"));
    }

    @Test
    @DisplayName("'failure' returns StandardGoalKind.FAILURE")
    void failure_returnsStandard() {
      assertEquals(StandardGoalKind.FAILURE, GoalKind.fromValue("failure"));
    }

    @Test
    @DisplayName("unknown value throws IllegalArgumentException")
    void unknown_throws() {
      var ex = assertThrows(IllegalArgumentException.class, () -> GoalKind.fromValue("escalated"));
      assertTrue(ex.getMessage().contains("GoalKind.of"));
    }
  }

  @Nested
  @DisplayName("StandardGoalKind")
  class StandardGoalKindTests {

    @Test
    @DisplayName("fromValue('success') returns SUCCESS")
    void fromValue_success() {
      assertEquals(StandardGoalKind.SUCCESS, StandardGoalKind.fromValue("success"));
    }

    @Test
    @DisplayName("fromValue('failure') returns FAILURE")
    void fromValue_failure() {
      assertEquals(StandardGoalKind.FAILURE, StandardGoalKind.fromValue("failure"));
    }

    @Test
    @DisplayName("fromValue('unknown') throws")
    void fromValue_unknown_throws() {
      assertThrows(IllegalArgumentException.class, () -> StandardGoalKind.fromValue("unknown"));
    }

    @Test
    @DisplayName("value() returns lower-case string")
    void value_returnsLowercase() {
      assertEquals("success", StandardGoalKind.SUCCESS.value());
      assertEquals("failure", StandardGoalKind.FAILURE.value());
    }

    @Test
    @DisplayName("toString() matches value()")
    void toString_matchesValue() {
      assertEquals("success", StandardGoalKind.SUCCESS.toString());
      assertEquals("failure", StandardGoalKind.FAILURE.toString());
    }
  }
}
