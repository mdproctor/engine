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

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CognitiveDemand;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CaseDefinitionYamlMapperRoutingTest {

  @Test
  void parsesRoutingSignalWeights() {
    String yaml =
        """
        namespace: test
        name: test-case
        version: 1.0.0
        spec:
          routingSignalWeights:
            trust: 0.4
            personality: 0.3
            workload: 0.2
            experience: 0.1
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getRoutingSignalWeights())
        .containsEntry("trust", 0.4)
        .containsEntry("personality", 0.3)
        .containsEntry("workload", 0.2)
        .containsEntry("experience", 0.1);
  }

  @Test
  void parsesCognitiveDemandOnCapability() {
    String yaml =
        """
        namespace: test
        name: test-case
        version: 1.0.0
        spec:
          capabilities:
            - name: code-review
              cognitiveDemand:
                Ti: 0.6
                Ne: 0.3
                Si: 0.1
        """;
    CaseDefinition def = load(yaml);
    CognitiveDemand demand = def.getCognitiveDemand("code-review");
    assertThat(demand).isNotNull();
    assertThat(demand.functionWeights())
        .containsEntry("Ti", 0.6)
        .containsEntry("Ne", 0.3)
        .containsEntry("Si", 0.1);
  }

  @Test
  void missingCognitiveDemand_returnsNull() {
    String yaml =
        """
        namespace: test
        name: test-case
        version: 1.0.0
        spec:
          capabilities:
            - name: code-review
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getCognitiveDemand("code-review")).isNull();
  }

  @Test
  void missingRoutingSignalWeights_returnsNull() {
    String yaml =
        """
        namespace: test
        name: test-case
        version: 1.0.0
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getRoutingSignalWeights()).isNull();
  }

  @Test
  void cognitiveDemand_viaBuilder() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .cognitiveDemand(
                "analysis", new CognitiveDemand(java.util.Map.of("Ti", 0.5, "Ne", 0.3, "Si", 0.2)))
            .build();
    assertThat(def.getCognitiveDemand("analysis")).isNotNull();
    assertThat(def.getCognitiveDemand("analysis").functionWeights()).containsEntry("Ti", 0.5);
    assertThat(def.getCognitiveDemand("unknown")).isNull();
  }

  @Test
  void routingSignalWeights_viaBuilder() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .routingSignalWeights(java.util.Map.of("trust", 0.6, "workload", 0.4))
            .build();
    assertThat(def.getRoutingSignalWeights()).containsEntry("trust", 0.6);
  }

  @SuppressWarnings("all")
  private static CaseDefinition load(String yaml) {
    try {
      return CaseDefinitionYamlMapper.load(
              new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
