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

import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.annotations.Bind;
import io.casehub.engine.annotations.Case;
import io.casehub.engine.annotations.Customize;
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class CustomizeTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest().withApplicationRoot(root -> root.addClasses(CustomizedCase.class));

  @Case(namespace = "test", name = "Customized", version = "1.0.0")
  public interface CustomizedCase {

    @Worker(capability = "work")
    @Bind(contextChange = ".input != null")
    default String work(String input) {
      return "done";
    }

    @Customize
    static void customize(CaseDefinition.Builder builder) {
      builder.title("Custom Title");
    }
  }

  @Inject CaseDefinition definition;

  @Test
  void customizer_applied() {
    assertThat(definition.getTitle()).isEqualTo("Custom Title");
  }
}
