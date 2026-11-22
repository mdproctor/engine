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
package io.casehub.engine.rest.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionMcpAdapterTest {

  @Test
  void classAnnotatedWithMcpDomain() {
    var annotation = EvolutionMcpAdapter.class.getAnnotation(McpDomain.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("engine/evolution");
  }

  @Test
  void everyPublicMethodHasQueryOrMutationAnnotation() {
    List<Method> publicMethods =
        Arrays.stream(EvolutionMcpAdapter.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .toList();

    assertThat(publicMethods).isNotEmpty();

    for (Method method : publicMethods) {
      boolean hasQuery = method.isAnnotationPresent(PlatformQuery.class);
      boolean hasMutation = method.isAnnotationPresent(PlatformMutation.class);
      assertThat(hasQuery || hasMutation)
          .as("Method %s must have @PlatformQuery or @PlatformMutation", method.getName())
          .isTrue();
    }
  }

  @Test
  void queryMethodsReturnValues() {
    List<Method> queryMethods =
        Arrays.stream(EvolutionMcpAdapter.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(PlatformQuery.class))
            .toList();

    for (Method method : queryMethods) {
      assertThat(method.getReturnType())
          .as("@PlatformQuery method %s should return a value", method.getName())
          .isNotEqualTo(void.class);
    }
  }

  @Test
  void hasExpectedMethodCount() {
    List<Method> annotated =
        Arrays.stream(EvolutionMcpAdapter.class.getDeclaredMethods())
            .filter(
                m ->
                    m.isAnnotationPresent(PlatformQuery.class)
                        || m.isAnnotationPresent(PlatformMutation.class))
            .toList();

    assertThat(annotated).hasSize(15);
  }
}
