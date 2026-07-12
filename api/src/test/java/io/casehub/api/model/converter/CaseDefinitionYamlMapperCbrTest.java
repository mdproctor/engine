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
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.JqFeatureExtractor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperCbrTest {

  @Test
  void cbr_block_maps_to_cbrConfig() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          cbr:
            features:
              posture: ".enemy.posture"
              build_order: ".enemy.build"
            weights:
              posture: 2.0
            topK: 3
            minSimilarity: 0.4
            vectorWeight: 0.7
            domain: "sc2"
            caseType: "game"
        """;
    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getCbrConfig()).isNotNull();
    CbrConfig config = def.getCbrConfig();
    assertThat(config.featureExtractor()).isInstanceOf(JqFeatureExtractor.class);

    JqFeatureExtractor jq = (JqFeatureExtractor) config.featureExtractor();
    assertThat(jq.featureExpressions())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("posture", ".enemy.posture", "build_order", ".enemy.build"));

    assertThat(config.topK()).isEqualTo(3);
    assertThat(config.minSimilarity()).isEqualTo(0.4);
    assertThat(config.vectorWeight()).isEqualTo(0.7);
    assertThat(config.domain()).isEqualTo("sc2");
    assertThat(config.caseType()).isEqualTo("game");
    assertThat(config.weights()).containsExactlyInAnyOrderEntriesOf(Map.of("posture", 2.0));
  }

  @Test
  void missing_cbr_block_results_in_null() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          capabilities:
            - name: cap1
        """;
    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getCbrConfig()).isNull();
  }

  @Test
  void cbr_with_defaults_only() throws IOException {
    String yaml =
        """
        dsl: "0.1.0"
        namespace: test
        name: test-case
        version: "1.0.0"
        spec:
          cbr:
            features:
              f1: ".x"
        """;
    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getCbrConfig()).isNotNull();
    assertThat(def.getCbrConfig().topK()).isEqualTo(5);
    assertThat(def.getCbrConfig().minSimilarity()).isEqualTo(0.0);
    assertThat(def.getCbrConfig().vectorWeight()).isEqualTo(0.5);
    assertThat(def.getCbrConfig().domain()).isNull();
    assertThat(def.getCbrConfig().caseType()).isNull();
    assertThat(def.getCbrConfig().weights()).isEmpty();
  }

  @Test
  void cbr_cbrType_parsed() throws IOException {
    String yaml =
        """
                      dsl: "0.1.0"
                      namespace: test
                      name: test-case
                      version: "1.0.0"
                      spec:
                        cbr:
                          features:
                            f1: ".x"
                          cbrType: feature-vector
                      """;
    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);
    assertThat(def.getCbrConfig()).isNotNull();
    assertThat(def.getCbrConfig().cbrType()).isEqualTo("feature-vector");
  }
}
