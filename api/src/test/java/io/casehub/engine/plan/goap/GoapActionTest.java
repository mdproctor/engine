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

import java.util.Map;
import org.junit.jupiter.api.Test;

class GoapActionTest {

  @Test
  void effectiveCost_no_benefit() {
    var action = new GoapAction("a", Map.of(), Map.of("x", true), 0.5, 0.0, Map.of());
    assertThat(action.effectiveCost()).isEqualTo(0.5);
  }

  @Test
  void effectiveCost_with_benefit() {
    var action = new GoapAction("a", Map.of(), Map.of("x", true), 0.8, 0.5, Map.of());
    assertThat(action.effectiveCost()).isEqualTo(0.4);
  }

  @Test
  void effectiveCost_full_benefit() {
    var action = new GoapAction("a", Map.of(), Map.of("x", true), 0.8, 1.0, Map.of());
    assertThat(action.effectiveCost()).isEqualTo(0.0);
  }

  @Test
  void effectiveCost_defaults_zero() {
    var action = new GoapAction("a", Map.of(), Map.of("x", true), 0.0, 0.0, Map.of());
    assertThat(action.effectiveCost()).isEqualTo(0.0);
  }

  @Test
  void isApplicable_ignores_soft_preconditions() {
    var action =
        new GoapAction(
            "a", Map.of("hard", true), Map.of("result", true), 0.5, 0.0, Map.of("soft", true));
    var state = new GoapWorldState(Map.of("hard", true));
    assertThat(action.isApplicable(state)).isTrue();
  }

  @Test
  void isApplicable_requires_hard_preconditions() {
    var action =
        new GoapAction("a", Map.of("hard", true), Map.of("result", true), 0.5, 0.0, Map.of());
    var state = new GoapWorldState(Map.of());
    assertThat(action.isApplicable(state)).isFalse();
  }

  @Test
  void backward_compat_constructor() {
    var action = new GoapAction("a", Map.of(), Map.of("x", true), 0.5);
    assertThat(action.benefit()).isEqualTo(0.0);
    assertThat(action.softPreconditions()).isEmpty();
    assertThat(action.effectiveCost()).isEqualTo(0.5);
  }
}
