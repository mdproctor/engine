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
package io.casehub.engine.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.work.runtime.service.TenantContextRunner;
import io.casehub.work.runtime.service.WorkItemService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for InboundWorkItemBridge CDI guard behavior. Plain JUnit 5 — no Quarkus container
 * required; guard logic is pure Java with no I/O.
 */
@ExtendWith(MockitoExtension.class)
class InboundWorkItemBridgeGuardTest {

  @Mock Instance<InboundWorkItemPolicy> policy;
  @Mock WorkItemService workItemService;
  @Mock TenantContextRunner tenantContextRunner;

  private InboundWorkItemBridge bridge() {
    final InboundWorkItemBridge b = new InboundWorkItemBridge();
    b.policy = policy;
    b.workItemService = workItemService;
    b.tenantContextRunner = tenantContextRunner;
    return b;
  }

  private static MessageReceivedEvent anyEvent() {
    return new MessageReceivedEvent(
        "ch",
        UUID.randomUUID(),
        "t1",
        MessageType.COMMAND,
        "sender",
        "corr",
        Instant.now(),
        "{}",
        null);
  }

  @Test
  void noPolicy_messageReceived_silentlyIgnored() {
    when(policy.isUnsatisfied()).thenReturn(true);

    bridge().onMessage(anyEvent());

    verify(workItemService, never()).create(any());
  }

  @Test
  void ambiguousPolicy_atStartup_throwsIllegalStateException() {
    when(policy.isAmbiguous()).thenReturn(true);

    assertThatThrownBy(() -> bridge().onStartup(new StartupEvent()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Multiple InboundWorkItemPolicy beans");
  }
}
