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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.enterprise.util.Nonbinding;
import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

class CaseTypeTest {

  @Test
  void shouldBeAnnotatedWithQualifier() {
    assertThat(CaseType.class.isAnnotationPresent(Qualifier.class))
        .as("@CaseType must be a CDI qualifier")
        .isTrue();
  }

  @Test
  void shouldHaveRuntimeRetention() {
    Retention retention = CaseType.class.getAnnotation(Retention.class);
    assertThat(retention).isNotNull();
    assertThat(retention.value())
        .as("@CaseType must have runtime retention for CDI discovery")
        .isEqualTo(RUNTIME);
  }

  @Test
  void shouldTargetTypeMethodFieldAndParameter() {
    Target target = CaseType.class.getAnnotation(Target.class);
    assertThat(target).isNotNull();
    assertThat(target.value())
        .as("@CaseType must target TYPE, METHOD, FIELD, PARAMETER for CDI injection points")
        .containsExactlyInAnyOrder(TYPE, METHOD, FIELD, PARAMETER);
  }

  @Test
  void shouldHaveNonbindingValue() throws NoSuchMethodException {
    assertThat(CaseType.class.getMethod("value").isAnnotationPresent(Nonbinding.class))
        .as("@CaseType.value() must be @Nonbinding — not used for bean selection")
        .isTrue();
  }
}
