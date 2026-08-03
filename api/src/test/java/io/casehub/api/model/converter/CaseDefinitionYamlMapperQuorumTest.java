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

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.OnThresholdReached;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperQuorumTest {

  @Test
  void load_withQuorumConfig_parsesSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: Quorum Test Case
        version: 1.0.0
        spec:
          quorum:
            instances: 3
            required: 2
            onThresholdReached: KEEP
            allowSameAssignee: false
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getDefaultQuorum()).isNotNull();
    assertThat(def.getDefaultQuorum().instances()).isEqualTo(3);
    assertThat(def.getDefaultQuorum().required()).isEqualTo(2);
    assertThat(def.getDefaultQuorum().onThresholdReached()).isEqualTo(OnThresholdReached.KEEP);
    assertThat(def.getDefaultQuorum().allowSameAssignee()).isFalse();
  }

  @Test
  void load_withQuorumConfigCancel_parsesSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: Quorum Cancel Test
        version: 1.0.0
        spec:
          quorum:
            instances: 5
            required: 3
            onThresholdReached: CANCEL
            allowSameAssignee: true
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getDefaultQuorum()).isNotNull();
    assertThat(def.getDefaultQuorum().instances()).isEqualTo(5);
    assertThat(def.getDefaultQuorum().required()).isEqualTo(3);
    assertThat(def.getDefaultQuorum().onThresholdReached()).isEqualTo(OnThresholdReached.CANCEL);
    assertThat(def.getDefaultQuorum().allowSameAssignee()).isTrue();
  }

  @Test
  void load_withQuorumConfigMinimalFields_parsesSuccessfully() throws IOException {
    String yaml =
        """
        namespace: test
        name: Quorum Minimal Test
        version: 1.0.0
        spec:
          quorum:
            instances: 2
            required: 1
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getDefaultQuorum()).isNotNull();
    assertThat(def.getDefaultQuorum().instances()).isEqualTo(2);
    assertThat(def.getDefaultQuorum().required()).isEqualTo(1);
    assertThat(def.getDefaultQuorum().onThresholdReached()).isNull();
    assertThat(def.getDefaultQuorum().allowSameAssignee()).isFalse();
  }

  @Test
  void load_withoutQuorumConfig_returnsNull() throws IOException {
    String yaml =
        """
        namespace: test
        name: No Quorum Test
        version: 1.0.0
        spec:
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getDefaultQuorum()).isNull();
  }

  @Test
  void load_withInvalidOnThresholdReached_throwsException() {
    String yaml =
        """
        namespace: test
        name: Invalid Quorum Test
        version: 1.0.0
        spec:
          quorum:
            instances: 3
            required: 2
            onThresholdReached: INVALID_VALUE
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(is))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid onThresholdReached value");
  }

  @Test
  void load_withInvalidQuorumInstances_throwsException() {
    String yaml =
        """
        namespace: test
        name: Invalid Instances Test
        version: 1.0.0
        spec:
          quorum:
            instances: 1
            required: 1
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(is))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("instances must be >= 2");
  }

  @Test
  void load_withInvalidQuorumRequired_throwsException() {
    String yaml =
        """
        namespace: test
        name: Invalid Required Test
        version: 1.0.0
        spec:
          quorum:
            instances: 3
            required: 4
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> CaseDefinitionYamlMapper.load(is))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required must be 1..3");
  }
}
