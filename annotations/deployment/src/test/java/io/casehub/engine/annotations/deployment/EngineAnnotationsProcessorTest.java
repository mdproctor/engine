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
import io.casehub.engine.annotations.Worker;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class EngineAnnotationsProcessorTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(root -> root.addClasses(SimpleCase.class, ProcessedDocument.class));

  @Case(namespace = "test", name = "Simple", version = "1.0.0")
  public interface SimpleCase {

    @Worker(capability = "process")
    @Bind(contextChange = ".status == 'ready'")
    default ProcessedDocument process(String input) {
      return new ProcessedDocument(input, "processed");
    }
  }

  public record ProcessedDocument(String content, String status) {}

  @Inject CaseDefinition simpleDefinition;

  @Test
  void generates_case_definition() {
    assertThat(simpleDefinition).isNotNull();
    assertThat(simpleDefinition.getNamespace()).isEqualTo("test");
    assertThat(simpleDefinition.getName()).isEqualTo("Simple");
    assertThat(simpleDefinition.getVersion()).isEqualTo("1.0.0");
  }

  @Test
  void generates_worker() {
    assertThat(simpleDefinition.getWorkers()).hasSize(1);
    assertThat(simpleDefinition.getWorkers().get(0).name()).isEqualTo("process");
  }

  @Test
  void generates_capability() {
    assertThat(simpleDefinition.getCapabilities()).hasSize(1);
    assertThat(simpleDefinition.getCapabilities().get(0).name()).isEqualTo("process");
  }

  @Test
  void generates_binding() {
    assertThat(simpleDefinition.getBindings()).hasSize(1);
    assertThat(simpleDefinition.getBindings().get(0).getName()).isEqualTo("process");
  }
}
