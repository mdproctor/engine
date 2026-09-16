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
import io.casehub.api.model.signal.SignalConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperSignalConfigTest {

  @Test
  void signalConfig_parsedFromYaml() throws java.io.IOException {
    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("signal-config-test.yaml"));
    SignalConfig config = def.getSignalConfig();
    assertThat(config.defaultHalfLife()).isEqualTo(Duration.ofMinutes(10));
    assertThat(config.effectiveZeroThreshold()).isEqualTo(0.05);
    assertThat(config.maxSignalsPerCase()).isEqualTo(50);
  }

  @Test
  void signalConfig_absent_returnsDefaults() throws java.io.IOException {
    CaseDefinition def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("concurrency-budget-test.yaml"));
    SignalConfig config = def.getSignalConfig();
    assertThat(config.defaultHalfLife()).isEqualTo(Duration.ofMinutes(5));
    assertThat(config.effectiveZeroThreshold()).isEqualTo(0.01);
    assertThat(config.maxSignalsPerCase()).isEqualTo(100);
  }

  @Test
  void signalConfig_viaBuilder() {
    SignalConfig config = new SignalConfig(Duration.ofMinutes(3), 0.02, 200);
    CaseDefinition def =
        CaseDefinition.builder()
            .name("test")
            .namespace("test")
            .version("1.0")
            .signalConfig(config)
            .build();
    assertThat(def.getSignalConfig()).isEqualTo(config);
  }
}
