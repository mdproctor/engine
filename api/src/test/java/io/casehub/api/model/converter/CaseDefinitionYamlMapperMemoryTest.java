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
package io.casehub.api.model.converter;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ReflectionTriggerConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperMemoryTest {

  private CaseDefinition load(String yaml) throws IOException {
    InputStream in = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    return CaseDefinitionYamlMapper.load(in);
  }

  @Test
  void parsesReflectionBlock() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          reflection:
            enabled: true
            importanceThreshold: 5.0
            maxUnreflectedOutcomes: 20
            maxSourceMemories: 100
            importanceWeights:
              SUCCESS: 0.1
              FAILED: 0.9
        """;
    CaseDefinition def = load(yaml);
    var config = def.getReflectionTrigger();
    assertThat(config).isNotNull();
    assertThat(config.enabled()).isTrue();
    assertThat(config.importanceThreshold()).isEqualTo(5.0);
    assertThat(config.maxUnreflectedOutcomes()).isEqualTo(20);
    assertThat(config.maxSourceMemories()).isEqualTo(100);
    assertThat(config.importanceWeights().get("SUCCESS")).isEqualTo(0.1);
    assertThat(config.importanceWeights().get("FAILED")).isEqualTo(0.9);
  }

  @Test
  void parsesMemoryRetrievalBlock() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          memoryRetrieval:
            enabled: true
            maxMemories: 5
            domains: [experience, reflection, relationship]
        """;
    CaseDefinition def = load(yaml);
    var config = def.getMemoryRetrieval();
    assertThat(config).isNotNull();
    assertThat(config.enabled()).isTrue();
    assertThat(config.maxMemories()).isEqualTo(5);
    assertThat(config.domains()).isEqualTo(Set.of("experience", "reflection", "relationship"));
  }

  @Test
  void missingBlocksReturnNull() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getReflectionTrigger()).isNull();
    assertThat(def.getMemoryRetrieval()).isNull();
  }

  @Test
  void missingImportanceWeightsUsesDefaults() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          reflection:
            enabled: true
        """;
    CaseDefinition def = load(yaml);
    var config = def.getReflectionTrigger();
    assertThat(config).isNotNull();
    assertThat(config.importanceWeights())
        .isEqualTo(ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS);
  }
}
