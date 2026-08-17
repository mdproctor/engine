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

class SimpleAnnotatedCaseTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      SimpleAnnotatedCase.class, SimpleAnnotatedCase.ProcessedDocument.class));

  @Inject CaseDefinition definition;

  @Test
  void namespace_and_name() {
    assertThat(definition.getNamespace()).isEqualTo("example");
    assertThat(definition.getName()).isEqualTo("Simple Document Processing");
    assertThat(definition.getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void worker_generated() {
    assertThat(definition.getWorkers()).hasSize(1);
    assertThat(definition.getWorkers().get(0).name()).isEqualTo("process");
    assertThat(definition.getWorkers().get(0).capabilityNames()).contains("processDocument");
  }

  @Test
  void capability_generated() {
    assertThat(definition.getCapabilities()).hasSize(1);
    assertThat(definition.getCapabilities().get(0).name()).isEqualTo("processDocument");
  }

  @Test
  void binding_with_context_change_trigger() {
    assertThat(definition.getBindings()).hasSize(1);
    assertThat(definition.getBindings().get(0).getName()).isEqualTo("process");
  }

  @Test
  void goal_generated() {
    assertThat(definition.getGoals()).hasSize(1);
    assertThat(definition.getGoals().get(0).getName()).isEqualTo("done");
    assertThat(definition.getGoals().get(0).getDescription())
        .isEqualTo("Document processing complete");
  }

  @Test
  void milestone_generated() {
    assertThat(definition.getMilestones()).hasSize(1);
    assertThat(definition.getMilestones().get(0).getName()).isEqualTo("documentProcessed");
  }
}
