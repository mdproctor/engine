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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningConstraintsTest {

  @Test
  void unconstrainedHasNullFields() {
    var c = PlanningConstraints.unconstrained();
    assertThat(c.timeBudget()).isNull();
    assertThat(c.resourceLimit()).isNull();
    assertThat(c.weights()).isEmpty();
  }

  @Test
  void ofFactorySetsBudgetAndLimit() {
    var c = PlanningConstraints.of(Duration.ofMinutes(30), 3);
    assertThat(c.timeBudget()).isEqualTo(Duration.ofMinutes(30));
    assertThat(c.resourceLimit()).isEqualTo(3);
    assertThat(c.weights()).isEmpty();
  }

  @Test
  void fullConstructorSetsAllFields() {
    var weights = Map.of("speed", 0.8, "quality", 0.2);
    var c = new PlanningConstraints(Duration.ofMinutes(10), 5, weights, Map.of());
    assertThat(c.timeBudget()).isEqualTo(Duration.ofMinutes(10));
    assertThat(c.resourceLimit()).isEqualTo(5);
    assertThat(c.weights()).containsEntry("speed", 0.8);
    assertThat(c.costBudgets()).isEmpty();
  }

  @Test
  void weightsMapIsUnmodifiable() {
    var c = PlanningConstraints.of(null, null);
    assertThat(c.weights()).isUnmodifiable();
  }

  @Test
  void fullConstructorSetsAllFieldsIncludingCostBudgets() {
    var weights = Map.of("speed", 0.8);
    var costs = Map.of("tokens", 5000, "apiCalls", 10);
    var c = new PlanningConstraints(Duration.ofMinutes(10), 5, weights, costs);
    assertThat(c.timeBudget()).isEqualTo(Duration.ofMinutes(10));
    assertThat(c.resourceLimit()).isEqualTo(5);
    assertThat(c.weights()).containsEntry("speed", 0.8);
    assertThat(c.costBudgets()).containsEntry("tokens", 5000).containsEntry("apiCalls", 10);
  }

  @Test
  void costBudgetsMapIsUnmodifiable() {
    var c = new PlanningConstraints(null, null, Map.of(), Map.of("tokens", 100));
    assertThat(c.costBudgets()).isUnmodifiable();
  }

  @Test
  void costBudgetsDefaultsToEmptyWhenNull() {
    var c = new PlanningConstraints(null, null, null, null);
    assertThat(c.costBudgets()).isEmpty();
  }

  @Test
  void hasHardConstraintsReturnsTrueForTimeBudget() {
    var c = PlanningConstraints.of(Duration.ofMinutes(5), null);
    assertThat(c.hasHardConstraints()).isTrue();
  }

  @Test
  void hasHardConstraintsReturnsTrueForResourceLimit() {
    var c = PlanningConstraints.of(null, 3);
    assertThat(c.hasHardConstraints()).isTrue();
  }

  @Test
  void hasHardConstraintsReturnsTrueForCostBudgets() {
    var c = new PlanningConstraints(null, null, Map.of(), Map.of("tokens", 100));
    assertThat(c.hasHardConstraints()).isTrue();
  }

  @Test
  void hasHardConstraintsReturnsFalseForWeightsOnly() {
    var c = new PlanningConstraints(null, null, Map.of("speed", 0.8), Map.of());
    assertThat(c.hasHardConstraints()).isFalse();
  }

  @Test
  void hasHardConstraintsReturnsFalseWhenUnconstrained() {
    assertThat(PlanningConstraints.unconstrained().hasHardConstraints()).isFalse();
  }
}
