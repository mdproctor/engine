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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CaseDefinitionSignalTest {

  @Test
  void builder_signal_addsToList() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("sig-test")
            .version("1.0")
            .signal(SignalType.of("alert", String.class))
            .signal(SignalType.of("update", Integer.class))
            .build();
    assertThat(def.getSignals()).hasSize(2);
    assertThat(def.getSignals().get(0).name()).isEqualTo("alert");
    assertThat(def.getSignals().get(1).name()).isEqualTo("update");
  }

  @Test
  void builder_noSignals_emptyList() {
    CaseDefinition def =
        CaseDefinition.builder().namespace("test").name("no-sig").version("1.0").build();
    assertThat(def.getSignals()).isEmpty();
  }

  @Test
  void builder_duplicateSignalName_throws() {
    assertThatThrownBy(
            () ->
                CaseDefinition.builder()
                    .namespace("test")
                    .name("dup")
                    .version("1.0")
                    .signal(SignalType.of("x", String.class))
                    .signal(SignalType.of("x", Integer.class))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate signal name");
  }

  @Test
  void getSignals_returnsUnmodifiableCopy() {
    CaseDefinition def =
        CaseDefinition.builder()
            .namespace("test")
            .name("immutable")
            .version("1.0")
            .signal(SignalType.of("a", String.class))
            .build();
    assertThatThrownBy(() -> def.getSignals().add(SignalType.of("b", Integer.class)))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
