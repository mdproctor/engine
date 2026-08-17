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
import io.casehub.engine.annotations.SystemPrompt;
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SystemPromptTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest().withApplicationRoot(root -> root.addClasses(AiCase.class));

  @Case(namespace = "test", name = "AiCase", version = "1.0.0")
  public interface AiCase {

    @Worker(capability = "analyse")
    @Bind(contextChange = ".input != null")
    @SystemPrompt("You are an analyst. Analyse the input.")
    default void analyse(String input) {}
  }

  @Inject CaseDefinition definition;

  @Test
  void worker_exists() {
    assertThat(definition.getWorkers()).hasSize(1);
    assertThat(definition.getWorkers().get(0).name()).isEqualTo("analyse");
  }

  @Test
  void capability_exists() {
    assertThat(definition.getCapabilities()).hasSize(1);
    assertThat(definition.getCapabilities().get(0).name()).isEqualTo("analyse");
  }
}
