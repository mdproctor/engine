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
package io.casehub.engine.planning.decomposition;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.engine.plan.PlanningConstraints;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoalDecompositionContextTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void constraintsReturnedWhenProvided() {
    var constraints = PlanningConstraints.of(Duration.ofMinutes(30), 3);
    var ctx = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of(), constraints);
    assertThat(ctx.constraints().timeBudget()).isEqualTo(Duration.ofMinutes(30));
    assertThat(ctx.constraints().resourceLimit()).isEqualTo(3);
  }

  @Test
  void constraintsDefaultToUnconstrainedWhenNull() {
    var ctx = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of(), null);
    assertThat(ctx.constraints()).isEqualTo(PlanningConstraints.unconstrained());
  }

  @Test
  void backwardCompatConstructorUsesUnconstrained() {
    var ctx = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of());
    assertThat(ctx.constraints()).isEqualTo(PlanningConstraints.unconstrained());
  }

  @Test
  void costBudgetsThreadedThroughConstraints() {
    var constraints =
        new PlanningConstraints(
            Duration.ofMinutes(30), 3, java.util.Map.of(), java.util.Map.of("tokens", 5000));
    var ctx = new GoalDecompositionContext(MAPPER.createObjectNode(), 0, List.of(), constraints);
    assertThat(ctx.constraints().costBudgets()).containsEntry("tokens", 5000);
  }
}
