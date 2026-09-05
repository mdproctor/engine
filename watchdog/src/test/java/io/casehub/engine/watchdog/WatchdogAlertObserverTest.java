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
package io.casehub.engine.watchdog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.casehub.api.model.CaseChannel;
import io.casehub.qhorus.api.watchdog.AgentStaleContext;
import io.casehub.qhorus.api.watchdog.ApprovalPendingContext;
import io.casehub.qhorus.api.watchdog.BarrierStuckContext;
import io.casehub.qhorus.api.watchdog.ChannelIdleContext;
import io.casehub.qhorus.api.watchdog.CircularDelegationContext;
import io.casehub.qhorus.api.watchdog.ContextPressureContext;
import io.casehub.qhorus.api.watchdog.ConversationStallContext;
import io.casehub.qhorus.api.watchdog.DeliveryLagContext;
import io.casehub.qhorus.api.watchdog.EchoChamberContext;
import io.casehub.qhorus.api.watchdog.LoopDetectedContext;
import io.casehub.qhorus.api.watchdog.ObligationFanOutContext;
import io.casehub.qhorus.api.watchdog.QueueDepthContext;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WatchdogAlertObserverTest {

  @Test
  void resolvesFromTargetName() {
    UUID caseId = UUID.randomUUID();
    String channelName = CaseChannel.channelName(caseId, "main");
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            channelName,
            null,
            "test",
            Instant.now(),
            new ChannelIdleContext(List.of(channelName), 60));

    UUID resolved = CaseChannel.parseCaseId(event.targetName());
    assertEquals(caseId, resolved);
  }

  @Test
  void returnsNullForWildcardTarget() {
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(),
            "*",
            null,
            "test",
            Instant.now(),
            new AgentStaleContext(1, List.of("agent-1")));

    UUID resolved = CaseChannel.parseCaseId(event.targetName());
    assertNull(resolved);
  }

  @Test
  void extractsChannelFromBarrierStuck() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new BarrierStuckContext(UUID.randomUUID(), channel, List.of("a"), 30);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromLoopDetected() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new LoopDetectedContext(UUID.randomUUID(), channel, "sender-1", 5, 0.9);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromContextPressure() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new ContextPressureContext(UUID.randomUUID(), channel, "actor-1", 85);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromEchoChamber() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new EchoChamberContext(UUID.randomUUID(), channel, List.of("a", "b"), 0.95);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromCircularDelegation() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx =
        new CircularDelegationContext(
            UUID.randomUUID(), channel, "corr-1", List.of("a", "b", "a"), 3);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromDeliveryLag() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new DeliveryLagContext(UUID.randomUUID(), channel, List.of(), 100L);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromObligationFanOut() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new ObligationFanOutContext(UUID.randomUUID(), channel, 3, List.of());
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromConversationStall() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new ConversationStallContext(UUID.randomUUID(), channel, 2, List.of(), 120);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsChannelFromQueueDepth() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new QueueDepthContext(channel, 50, 20);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void extractsFirstChannelFromChannelIdle() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var ctx = new ChannelIdleContext(List.of(channel, "other"), 60);
    assertEquals(channel, WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void returnsNullForEmptyChannelIdle() {
    var ctx = new ChannelIdleContext(List.of(), 60);
    assertNull(WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void returnsNullForAgentStale() {
    var ctx = new AgentStaleContext(1, List.of("instance-uuid"));
    assertNull(WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void returnsNullForApprovalPending() {
    var ctx = new ApprovalPendingContext(2, Instant.now());
    assertNull(WatchdogAlertObserver.extractChannelName(ctx));
  }

  @Test
  void fallbackResolvesFromAlertContext() {
    UUID caseId = UUID.randomUUID();
    String channel = CaseChannel.channelName(caseId, "main");
    var alertCtx = new BarrierStuckContext(UUID.randomUUID(), channel, List.of("a"), 30);
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(), "*", null, "barrier stuck", Instant.now(), alertCtx);

    var observer = new WatchdogAlertObserver();
    UUID resolved = observer.resolveCaseId(event);
    assertNotNull(resolved);
    assertEquals(caseId, resolved);
  }

  @Test
  void fallbackReturnsNullWhenNoChannel() {
    var alertCtx = new AgentStaleContext(1, List.of("instance-1"));
    var event =
        new WatchdogAlertEvent(
            UUID.randomUUID(), "*", null, "agent stale", Instant.now(), alertCtx);

    var observer = new WatchdogAlertObserver();
    UUID resolved = observer.resolveCaseId(event);
    assertNull(resolved);
  }
}
