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

import static org.assertj.core.api.Assertions.*;

import io.casehub.api.model.CaseDefinition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperConvergenceTest {

  @Test
  void budgetConfig_parsed_from_yaml() throws IOException {
    String yaml =
        """
        spec:
          name: test-case
          budgetConfig:
            maxDispatches: 1000
            maxSignalDeposits: 5000
        """;
    CaseDefinition def = CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));
    assertThat(def.getBudgetConfig()).isNotNull();
    assertThat(def.getBudgetConfig().maxDispatches()).isEqualTo(1000);
    assertThat(def.getBudgetConfig().maxSignalDeposits()).isEqualTo(5000);
    assertThat(def.getBudgetConfig().maxContextMutations()).isNull();
  }

  @Test
  void convergenceThresholdConfig_parsed_from_yaml() throws IOException {
    String yaml =
        """
        spec:
          name: test-case
          convergenceThresholdConfig:
            dispatchRateThreshold: 0.2
            stabilityWindow: PT30S
            rateWindow: PT60S
        """;
    CaseDefinition def = CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));
    assertThat(def.getConvergenceThresholdConfig()).isNotNull();
    assertThat(def.getConvergenceThresholdConfig().dispatchRateThreshold()).isEqualTo(0.2);
    assertThat(def.getConvergenceThresholdConfig().stabilityWindow())
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void outputConvergenceConfig_parsed_from_yaml() throws IOException {
    String yaml =
        """
        spec:
          name: test-case
          outputConvergenceConfig:
            convergenceThreshold: 0.8
            convergenceMinSamples: 5
            outputWindowSize: 20
        """;
    CaseDefinition def = CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));
    assertThat(def.getOutputConvergenceConfig()).isNotNull();
    assertThat(def.getOutputConvergenceConfig().convergenceThreshold()).isEqualTo(0.8);
    assertThat(def.getOutputConvergenceConfig().convergenceMinSamples()).isEqualTo(5);
    assertThat(def.getOutputConvergenceConfig().outputWindowSize()).isEqualTo(20);
  }

  @Test
  void all_configs_null_when_absent() throws IOException {
    String yaml =
        """
        spec:
          name: test-case
        """;
    CaseDefinition def = CaseDefinitionYamlMapper.load(new ByteArrayInputStream(yaml.getBytes()));
    assertThat(def.getBudgetConfig()).isNull();
    assertThat(def.getConvergenceThresholdConfig()).isNull();
    assertThat(def.getOutputConvergenceConfig()).isNull();
  }
}
