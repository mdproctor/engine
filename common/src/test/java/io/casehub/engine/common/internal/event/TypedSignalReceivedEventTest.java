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
package io.casehub.engine.common.internal.event;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedSignalReceivedEventTest {

  record Payment(String id, double amount) {}

  @Test
  void recordCarriesAllFields() {
    UUID caseId = UUID.randomUUID();
    Payment payload = new Payment("p1", 99.99);
    var event =
        new TypedSignalReceivedEvent(
            caseId,
            "payment-received",
            payload,
            Payment.class,
            Payment.class.getName(),
            "tenant-1");

    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.signalName()).isEqualTo("payment-received");
    assertThat(event.payload()).isSameAs(payload);
    assertThat(event.payloadType()).isEqualTo(Payment.class);
    assertThat(event.payloadTypeName()).isEqualTo(Payment.class.getName());
    assertThat(event.tenancyId()).isEqualTo("tenant-1");
  }

  @Test
  void equality() {
    UUID caseId = UUID.randomUUID();
    Payment payload = new Payment("p1", 99.99);
    var a =
        new TypedSignalReceivedEvent(
            caseId, "x", payload, Payment.class, Payment.class.getName(), "t1");
    var b =
        new TypedSignalReceivedEvent(
            caseId, "x", payload, Payment.class, Payment.class.getName(), "t1");
    assertThat(a).isEqualTo(b);
  }
}
