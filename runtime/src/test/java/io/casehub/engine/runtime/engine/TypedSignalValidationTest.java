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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.SignalRejectedException;
import io.casehub.api.model.SignalType;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedSignalValidationTest {

  record PaymentEvent(String txnId) {}

  record AlertEvent(String msg) {}

  @Test
  void validateSignal_declaredNameAndType_passes() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("t")
            .version("1.0")
            .signal(SignalType.of("payment", PaymentEvent.class))
            .build();

    assertThatCode(() -> validateSignal(definition, SignalType.of("payment", PaymentEvent.class)))
        .doesNotThrowAnyException();
  }

  @Test
  void validateSignal_undeclaredName_throws() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("t")
            .version("1.0")
            .signal(SignalType.of("payment", PaymentEvent.class))
            .build();

    assertThatThrownBy(() -> validateSignal(definition, SignalType.of("alert", AlertEvent.class)))
        .isInstanceOf(SignalRejectedException.class)
        .hasMessageContaining("not declared");
  }

  @Test
  void validateSignal_wrongPayloadType_throws() {
    var definition =
        CaseDefinition.builder()
            .namespace("test")
            .name("t")
            .version("1.0")
            .signal(SignalType.of("payment", PaymentEvent.class))
            .build();

    assertThatThrownBy(() -> validateSignal(definition, SignalType.of("payment", AlertEvent.class)))
        .isInstanceOf(SignalRejectedException.class)
        .hasMessageContaining("declared with type");
  }

  @Test
  void validateSignal_noSignalsDeclared_accepts() {
    var definition = CaseDefinition.builder().namespace("test").name("t").version("1.0").build();

    assertThatCode(() -> validateSignal(definition, SignalType.of("anything", String.class)))
        .doesNotThrowAnyException();
  }

  private void validateSignal(CaseDefinition definition, SignalType<?> signalType) {
    List<SignalType<?>> declared = definition.getSignals();
    if (!declared.isEmpty()) {
      var match =
          declared.stream()
              .filter(s -> s.name().equals(signalType.name()))
              .findFirst()
              .orElse(null);
      if (match == null) {
        throw new SignalRejectedException(
            "Signal '"
                + signalType.name()
                + "' not declared on definition "
                + definition.getName());
      }
      if (!match.payloadType().equals(signalType.payloadType())) {
        throw new SignalRejectedException(
            "Signal '"
                + signalType.name()
                + "' declared with type "
                + match.payloadType().getName()
                + " but received "
                + signalType.payloadType().getName());
      }
    }
  }
}
