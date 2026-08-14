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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.WorkerFunction;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperJsonNodeTest {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  @Test
  void loadFromJsonNodeProducesSameResultAsInputStream() throws Exception {
    try (var is =
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("casehub/minimal.yaml")) {
      assertThat(is).as("Test resource casehub/minimal.yaml").isNotNull();
      byte[] bytes = is.readAllBytes();

      CaseDefinition fromStream = CaseDefinitionYamlMapper.load(new ByteArrayInputStream(bytes));

      JsonNode node = YAML_MAPPER.readTree(bytes);
      CaseDefinition fromNode =
          CaseDefinitionYamlMapper.load(node, YAML_MAPPER, null, n -> WorkerFunction.NONE);

      assertThat(fromNode.getNamespace()).isEqualTo(fromStream.getNamespace());
      assertThat(fromNode.getName()).isEqualTo(fromStream.getName());
      assertThat(fromNode.getVersion()).isEqualTo(fromStream.getVersion());
      assertThat(fromNode.getCapabilities()).hasSameSizeAs(fromStream.getCapabilities());
      assertThat(fromNode.getBindings()).hasSameSizeAs(fromStream.getBindings());
    }
  }

  @Test
  void loadFromJsonNodeRejectsNull() {
    assertThatThrownBy(
            () ->
                CaseDefinitionYamlMapper.load(
                    (JsonNode) null, YAML_MAPPER, null, n -> WorkerFunction.NONE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }
}
