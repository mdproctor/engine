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
import io.casehub.api.model.WatchdogResponseAction;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperWatchdogTest {

  @Test
  void watchdogPolicy_parsed_from_yaml() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("watchdog-policy-test.yaml"));
    assertThat(def.getWatchdogPolicy())
        .containsEntry(WatchdogConditionType.AGENT_STALE, WatchdogResponseAction.CANCEL_AFFECTED)
        .containsEntry(WatchdogConditionType.LOOP_DETECTED, WatchdogResponseAction.IGNORE);
  }

  @Test
  void watchdogPolicy_null_when_absent() throws IOException {
    var def =
        CaseDefinitionYamlMapper.load(
            getClass().getClassLoader().getResourceAsStream("concurrency-budget-absent-test.yaml"));
    assertThat(def.getWatchdogPolicy()).isNull();
  }

  @Test
  void watchdogPolicy_via_builder() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("watchdog")
            .version("1.0")
            .watchdogPolicy(
                Map.of(
                    WatchdogConditionType.AGENT_STALE,
                    WatchdogResponseAction.CANCEL_AFFECTED,
                    WatchdogConditionType.LOOP_DETECTED,
                    WatchdogResponseAction.IGNORE))
            .build();
    assertThat(def.getWatchdogPolicy()).hasSize(2);
    assertThat(def.getWatchdogPolicy().get(WatchdogConditionType.AGENT_STALE))
        .isEqualTo(WatchdogResponseAction.CANCEL_AFFECTED);
  }
}
