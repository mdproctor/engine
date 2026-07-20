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
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperInboundTest {

  @Test
  void parses_inboundMappings_block() throws IOException {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        signals:
          - name: aml-alert
            contextType: java.lang.String
        inboundMappings:
          - signal: aml-alert
            connectorType: aml-system
            correlation: '.metadata.caseRef'
            payload: '.content | fromjson'
            correlationResolver: uuid
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getInboundMappings()).hasSize(1);
    var mapping = def.getInboundMappings().get(0);
    assertThat(mapping.signalName()).isEqualTo("aml-alert");
    assertThat(mapping.connectorType()).isEqualTo("aml-system");
    assertThat(mapping.correlation()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) mapping.correlation()).expression())
        .isEqualTo(".metadata.caseRef");
    assertThat(((JQExpressionEvaluator) mapping.payload()).expression())
        .isEqualTo(".content | fromjson");
    assertThat(mapping.correlationResolver()).isEqualTo("uuid");
  }

  @Test
  void inboundMappings_default_correlationResolver_is_null() throws IOException {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        signals:
          - name: alert
            contextType: java.lang.String
        inboundMappings:
          - signal: alert
            connectorType: slack
            correlation: '.metadata.caseRef'
            payload: '.content'
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getInboundMappings().get(0).correlationResolver()).isNull();
  }

  @Test
  void no_inboundMappings_produces_empty_list() throws IOException {
    String yaml =
        """
        namespace: test
        name: test-case
        version: "1.0"
        """;
    CaseDefinition def = load(yaml);
    assertThat(def.getInboundMappings()).isEmpty();
  }

  private CaseDefinition load(String yaml) throws IOException {
    return CaseDefinitionYamlMapper.load(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }
}
