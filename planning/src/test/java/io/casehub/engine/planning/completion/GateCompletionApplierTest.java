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
package io.casehub.engine.planning.completion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GateCompletionApplierTest {

  private GateCompletionApplier applier;
  private EventBus eventBus;

  private static final UUID CASE_ID = UUID.randomUUID();
  private static final String TENANCY_ID = "test-tenant";
  private static final long GATE_ID = 42L;

  @BeforeEach
  void setUp() {
    eventBus = mock(EventBus.class);
    applier = new GateCompletionApplier();
    applier.eventBus = eventBus;
  }

  @Test
  void completed_publishes_approved_event() {
    applier.apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.COMPLETED, "{}", "approver-1");

    verify(eventBus)
        .publish(eq(EventBusAddresses.ACTION_GATE_APPROVED), any(ActionGateApprovedEvent.class));
  }

  @Test
  void rejected_publishes_rejected_event() {
    applier.apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.REJECTED, "{}", "rejector-1");

    verify(eventBus)
        .publish(eq(EventBusAddresses.ACTION_GATE_REJECTED), any(ActionGateRejectedEvent.class));
  }

  @Test
  void cancelled_publishes_rejected_event() {
    applier.apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.CANCELLED, null, null);

    verify(eventBus)
        .publish(eq(EventBusAddresses.ACTION_GATE_REJECTED), any(ActionGateRejectedEvent.class));
  }

  @Test
  void faulted_publishes_expired_event() {
    applier.apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.FAULTED, null, null);

    verify(eventBus)
        .publish(eq(EventBusAddresses.ACTION_GATE_EXPIRED), any(ActionGateExpiredEvent.class));
  }

  @Test
  void unsupported_status_does_not_publish() {
    applier.apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.PENDING, null, null);

    verify(eventBus, never()).publish(any(), any());
  }
}
