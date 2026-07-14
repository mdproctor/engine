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

import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalTypeTest {

  record PaymentEvent(String txnId, double amount) {}

  @Test
  void of_createsTypedSignal() {
    SignalType<PaymentEvent> signal = SignalType.of("payment-received", PaymentEvent.class);
    assertThat(signal.name()).isEqualTo("payment-received");
    assertThat(signal.payloadType()).isEqualTo(PaymentEvent.class);
  }

  @Test
  void untyped_createsMapSignal() {
    SignalType<Map<String, Object>> signal = SignalType.untyped("generic");
    assertThat(signal.name()).isEqualTo("generic");
    assertThat(signal.payloadType()).isEqualTo(Map.class);
  }

  @Test
  void nullName_throws() {
    assertThatThrownBy(() -> SignalType.of(null, String.class))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullPayloadType_throws() {
    assertThatThrownBy(() -> SignalType.of("test", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void equality_byNameAndType() {
    var a = SignalType.of("x", String.class);
    var b = SignalType.of("x", String.class);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void inequality_differentName() {
    var a = SignalType.of("x", String.class);
    var b = SignalType.of("y", String.class);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void inequality_differentType() {
    var a = SignalType.of("x", String.class);
    var b = SignalType.of("x", Integer.class);
    assertThat(a).isNotEqualTo(b);
  }
}
