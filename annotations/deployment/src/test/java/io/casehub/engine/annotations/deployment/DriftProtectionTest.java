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
package io.casehub.engine.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.PlanningMode;
import io.casehub.engine.annotations.Worker;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DriftProtectionTest {

  @Test
  void case_annotation_has_expected_attributes() {
    Set<String> attrs =
        Arrays.stream(Case.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertThat(attrs)
        .containsExactlyInAnyOrder("namespace", "name", "version", "title", "summary", "planning");
  }

  @Test
  void case_has_runtime_retention() {
    assertThat(Case.class.getAnnotation(Retention.class).value())
        .isEqualTo(RetentionPolicy.RUNTIME);
  }

  @Test
  void worker_annotation_has_expected_attributes() {
    Set<String> attrs =
        Arrays.stream(Worker.class.getDeclaredMethods())
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertThat(attrs)
        .contains(
            "value",
            "capability",
            "capabilities",
            "description",
            "cost",
            "benefit",
            "timeoutMs",
            "maxRetries",
            "scope",
            "participation",
            "executionMode");
  }

  @Test
  void worker_defaults() throws NoSuchMethodException {
    assertThat(Worker.class.getMethod("cost").getDefaultValue()).isEqualTo(0.0);
    assertThat(Worker.class.getMethod("benefit").getDefaultValue()).isEqualTo(0.0);
    assertThat(Worker.class.getMethod("maxRetries").getDefaultValue()).isEqualTo(-1);
  }

  @Test
  void planning_mode_has_three_values() {
    assertThat(PlanningMode.values())
        .containsExactly(PlanningMode.EXPLICIT, PlanningMode.GOAP, PlanningMode.ADAPTIVE);
  }

  @Test
  void bind_is_repeatable() {
    assertThat(io.casehub.engine.annotations.Bind.class.getAnnotation(Repeatable.class))
        .isNotNull();
  }

  @Test
  void completion_is_repeatable() {
    assertThat(io.casehub.engine.annotations.Completion.class.getAnnotation(Repeatable.class))
        .isNotNull();
  }

  @Test
  void customize_is_repeatable() {
    assertThat(io.casehub.engine.annotations.Customize.class.getAnnotation(Repeatable.class))
        .isNotNull();
  }
}
