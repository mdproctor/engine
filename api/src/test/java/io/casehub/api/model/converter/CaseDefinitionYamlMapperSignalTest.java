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

import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ScheduleTrigger;
import io.casehub.api.model.SignalTarget;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CaseDefinitionYamlMapperSignalTest {

  @Test
  void scheduleTrigger_delay_parsesFromYaml() {
    CaseDefinition def = loadDefinition("signal-sla-test.yaml");
    Binding timeout =
        def.getBindings().stream()
            .filter(b -> "case-timeout".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(timeout.getOn()).isInstanceOf(ScheduleTrigger.class);
    ScheduleTrigger trigger = (ScheduleTrigger) timeout.getOn();
    assertThat(trigger.isDelay()).isTrue();
    assertThat(trigger.getDelay()).isEqualTo(Duration.ofHours(48));
  }

  @Test
  void scheduleTrigger_cron_parsesFromYaml() {
    CaseDefinition def = loadDefinition("signal-cron-test.yaml");
    Binding periodic =
        def.getBindings().stream()
            .filter(b -> "periodic-check".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(periodic.getOn()).isInstanceOf(ScheduleTrigger.class);
    ScheduleTrigger trigger = (ScheduleTrigger) periodic.getOn();
    assertThat(trigger.isCron()).isTrue();
    assertThat(trigger.getCron()).isEqualTo("0 0 * * *");
  }

  @Test
  void signalTarget_parsesFromYaml() {
    CaseDefinition def = loadDefinition("signal-sla-test.yaml");
    Binding timeout =
        def.getBindings().stream()
            .filter(b -> "case-timeout".equals(b.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(timeout.target()).isInstanceOf(SignalTarget.class);
    SignalTarget st = (SignalTarget) timeout.target();
    assertThat(st.payload()).containsKey("caseSla");
  }

  private CaseDefinition loadDefinition(String filename) {
    InputStream is = getClass().getClassLoader().getResourceAsStream("yaml/" + filename);
    try {
      return CaseDefinitionYamlMapper.load(is);
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }
}
