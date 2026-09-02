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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CompensationYamlTest {

  private CaseDefinition loadDefinition(String filename) {
    InputStream is = getClass().getClassLoader().getResourceAsStream("yaml/" + filename);
    try {
      return CaseDefinitionYamlMapper.load(is);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private CaseDefinition loadYaml(String yaml) {
    try {
      return CaseDefinitionYamlMapper.load(
          new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void compensateRef_parsedFromYaml() {
    CaseDefinition def = loadDefinition("compensation-test.yaml");
    Binding irb =
        def.getBindings().stream()
            .filter(b -> b.getName().equals("irb-review"))
            .findFirst()
            .orElseThrow();
    assertThat(irb.getCompensateRef()).isEqualTo("irb-review-reversal");
  }

  @Test
  void compensationFlag_parsedFromYaml() {
    CaseDefinition def = loadDefinition("compensation-test.yaml");
    Binding reversal =
        def.getBindings().stream()
            .filter(b -> b.getName().equals("irb-review-reversal"))
            .findFirst()
            .orElseThrow();
    assertThat(reversal.isCompensation()).isTrue();
  }

  @Test
  void bindingWithoutCompensation_defaultsFalse() {
    CaseDefinition def = loadDefinition("compensation-test.yaml");
    Binding dataExport =
        def.getBindings().stream()
            .filter(b -> b.getName().equals("data-export"))
            .findFirst()
            .orElseThrow();
    assertThat(dataExport.getCompensateRef()).isNull();
    assertThat(dataExport.isCompensation()).isFalse();
  }

  @Test
  void selfCompensation_throwsOnLoad() {
    String yaml =
        """
        spec:
          bindings:
            - name: step-a
              capability: svc
              on:
                contextChange: "$"
              compensate: step-a
        """;
    assertThatThrownBy(() -> loadYaml(yaml))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("itself");
  }

  @Test
  void missingCompensateTarget_throwsOnLoad() {
    String yaml =
        """
        spec:
          bindings:
            - name: step-a
              capability: svc
              on:
                contextChange: "$"
              compensate: nonexistent
        """;
    assertThatThrownBy(() -> loadYaml(yaml))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nonexistent");
  }
}
