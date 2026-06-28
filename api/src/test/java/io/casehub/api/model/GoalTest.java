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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class GoalTest {

  @Test
  void goal_builder_has_no_terminal_method() {
    var methods = Arrays.stream(Goal.Builder.class.getMethods()).map(Method::getName).toList();
    assertThat(methods).doesNotContain("terminal");
  }

  @Test
  void goal_has_no_terminal_getter() {
    var methods = Arrays.stream(Goal.class.getMethods()).map(Method::getName).toList();
    assertThat(methods).doesNotContain("getTerminal");
    assertThat(methods).doesNotContain("setTerminal");
  }

  @Test
  void goal_equals_ignores_terminal() {
    Goal g1 =
        Goal.builder()
            .name("approved")
            .condition(".decision == \"approved\"")
            .kind(GoalKind.SUCCESS)
            .build();
    Goal g2 =
        Goal.builder()
            .name("approved")
            .condition(".decision == \"approved\"")
            .kind(GoalKind.SUCCESS)
            .build();
    assertThat(g1).isEqualTo(g2);
    assertThat(g1.hashCode()).isEqualTo(g2.hashCode());
  }
}
