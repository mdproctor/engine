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
import java.io.IOException;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperConcurrencyTest {

  @Test
  void maxConcurrentDispatches_parsed_from_yaml() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("concurrency-budget-test.yaml"));
    assertThat(def.getMaxConcurrentDispatches()).isEqualTo(5);
  }

  @Test
  void maxConcurrentDispatches_null_when_absent() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("concurrency-budget-absent-test.yaml"));
    assertThat(def.getMaxConcurrentDispatches()).isNull();
  }

  @Test
  void maxConcurrentDispatches_via_builder() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("throttled")
            .version("1.0")
            .maxConcurrentDispatches(3)
            .build();
    assertThat(def.getMaxConcurrentDispatches()).isEqualTo(3);
  }
}
