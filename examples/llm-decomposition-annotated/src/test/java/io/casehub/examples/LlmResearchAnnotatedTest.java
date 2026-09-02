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
package io.casehub.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class LlmResearchAnnotatedTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(root -> root.addClasses(LlmResearchAnnotated.class));

  @Inject CaseDefinition definition;

  @Test
  void definesLlmDecompositionCase() {
    assertThat(definition.getNamespace()).isEqualTo("research");
    assertThat(definition.getName()).isEqualTo("AnalysisPipeline");
    assertThat(definition.getDecompositionStrategy()).isEqualTo("llm");
    assertThat(definition.getCapabilities()).hasSize(3);
    assertThat(definition.getWorkers()).hasSize(3);
    assertThat(definition.getGoals()).hasSize(2);
    assertThat(definition.getAdaptationConfig()).isNotNull();
    assertThat(definition.getPlanningConstraints()).isNotNull();
  }
}
