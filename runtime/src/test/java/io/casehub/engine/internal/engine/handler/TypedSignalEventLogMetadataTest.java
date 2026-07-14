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
package io.casehub.engine.internal.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.JacksonPojoBridge;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedSignalEventLogMetadataTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  record PaymentEvent(String txnId, double amount) {}

  @Test
  void typedSignalEventLog_carriesSignalMetadata() {
    UUID caseId = UUID.randomUUID();
    PaymentEvent payload = new PaymentEvent("T1", 99.99);
    JacksonPojoBridge<PaymentEvent> bridge = new JacksonPojoBridge<>(PaymentEvent.class);
    JsonNode serialised = bridge.serialise(payload);

    assertThat(serialised.has("txnId")).isTrue();
    assertThat(serialised.get("txnId").asText()).isEqualTo("T1");
    assertThat(serialised.get("amount").asDouble()).isEqualTo(99.99);
  }

  @Test
  void jacksonPojoBridge_roundTrips() {
    PaymentEvent original = new PaymentEvent("T2", 42.0);
    JacksonPojoBridge<PaymentEvent> bridge = new JacksonPojoBridge<>(PaymentEvent.class);

    JsonNode serialised = bridge.serialise(original);
    PaymentEvent restored = bridge.deserialise(serialised);

    assertThat(restored.txnId()).isEqualTo("T2");
    assertThat(restored.amount()).isEqualTo(42.0);
  }
}
