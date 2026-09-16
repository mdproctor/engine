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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.spi.observation.ObservationConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperObservationTest {

  @Test
  void parsesObservationConfig() throws Exception {
    String yaml =
        """
                name: test-case
                spec:
                  observation:
                    maxHistoryEntries: 100
                    maxHistoryAge: PT10M
                    maxObserversPerCase: 50
                """;
    CaseDefinition def =
        CaseDefinitionYamlMapper.load(new java.io.ByteArrayInputStream(yaml.getBytes()));
    var config = def.getObservationConfig();
    assertEquals(100, config.maxHistoryEntries());
    assertEquals(Duration.ofMinutes(10), config.maxHistoryAge());
    assertEquals(50, config.maxObserversPerCase());
  }

  @Test
  void defaultsWhenAbsent() throws Exception {
    String yaml =
        """
                name: test-case
                spec: {}
                """;
    CaseDefinition def =
        CaseDefinitionYamlMapper.load(new java.io.ByteArrayInputStream(yaml.getBytes()));
    var config = def.getObservationConfig();
    assertEquals(ObservationConfig.DEFAULT_MAX_HISTORY_ENTRIES, config.maxHistoryEntries());
    assertEquals(ObservationConfig.DEFAULT_MAX_HISTORY_AGE, config.maxHistoryAge());
    assertEquals(ObservationConfig.DEFAULT_MAX_OBSERVERS_PER_CASE, config.maxObserversPerCase());
  }

  @Test
  void builderSetsObservationConfig() {
    var config = new ObservationConfig(10, Duration.ofSeconds(30), 5);
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("test-case")
            .version("1.0")
            .observationConfig(config)
            .build();
    assertEquals(config, def.getObservationConfig());
  }

  @Test
  void partialObservationConfigUsesDefaults() throws Exception {
    String yaml =
        """
                name: test-case
                spec:
                  observation:
                    maxHistoryEntries: 25
                """;
    CaseDefinition def =
        CaseDefinitionYamlMapper.load(new java.io.ByteArrayInputStream(yaml.getBytes()));
    var config = def.getObservationConfig();
    assertEquals(25, config.maxHistoryEntries());
    assertEquals(ObservationConfig.DEFAULT_MAX_HISTORY_AGE, config.maxHistoryAge());
    assertEquals(ObservationConfig.DEFAULT_MAX_OBSERVERS_PER_CASE, config.maxObserversPerCase());
  }
}
