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

import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class GizmoSubclassTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(root -> root.addClasses(GizmoCase.class, GizmoCase.Result.class));

  @Case(namespace = "test", name = "Gizmo", version = "1.0.0")
  public interface GizmoCase {

    @Worker(capability = "compute")
    @Bind(contextChange = ".input != null")
    default Result compute(String input) {
      return new Result("computed: " + input);
    }

    record Result(String value) {}
  }

  @Test
  void generated_class_exists() throws Exception {
    Class<?> implClass =
        Thread.currentThread()
            .getContextClassLoader()
            .loadClass(GizmoCase.class.getName() + "_CaseHubImpl");
    assertThat(implClass).isNotNull();
    assertThat(GizmoCase.class.isAssignableFrom(implClass)).isTrue();
  }

  @Test
  void generated_class_instantiable() throws Exception {
    Class<?> implClass =
        Thread.currentThread()
            .getContextClassLoader()
            .loadClass(GizmoCase.class.getName() + "_CaseHubImpl");
    Object instance = implClass.getDeclaredConstructor().newInstance();
    assertThat(instance).isInstanceOf(GizmoCase.class);
  }

  @Test
  void default_method_callable() throws Exception {
    Class<?> implClass =
        Thread.currentThread()
            .getContextClassLoader()
            .loadClass(GizmoCase.class.getName() + "_CaseHubImpl");
    GizmoCase instance = (GizmoCase) implClass.getDeclaredConstructor().newInstance();
    GizmoCase.Result result = instance.compute("test");
    assertThat(result.value()).isEqualTo("computed: test");
  }
}
