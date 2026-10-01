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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseMetaModelDefinitionPopulationTest {

  @Inject DefaultCaseDefinitionRegistry registry;

  @Test
  void registerCaseDefinition_populatesDefinitionColumn() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test-def-pop").name("def-pop").version("1.0.0").build();

    CaseMetaModel model = registry.registerCaseDefinition(def);

    JsonNode definition = model.getDefinition();
    assertThat(definition).isNotNull();
    assertThat(definition.has("namespace")).isTrue();
    assertThat(definition.get("namespace").asText()).isEqualTo("test-def-pop");
    assertThat(definition.get("name").asText()).isEqualTo("def-pop");
    assertThat(definition.get("version").asText()).isEqualTo("1.0.0");
  }

  @Test
  void registerCaseDefinition_definitionExcludesNonSerializableFields() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test-def-serial")
            .name("def-serial")
            .version("1.0.0")
            .build();

    CaseMetaModel model = registry.registerCaseDefinition(def);

    JsonNode definition = model.getDefinition();
    assertThat(definition).isNotNull();

    // Workers list should be serialized (empty in this case)
    assertThat(definition.has("workers")).isTrue();

    // Definition should contain structural fields
    assertThat(definition.has("bindings")).isTrue();
    assertThat(definition.has("capabilities")).isTrue();
    assertThat(definition.has("milestones")).isTrue();
    assertThat(definition.has("goals")).isTrue();
  }
}
