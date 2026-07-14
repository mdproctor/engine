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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.spi.WorkloadProvider;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.casehub.work.runtime.service.TenantContextRunner;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InboundWorkItemBridgeTest {

  // ── Test doubles ─────────────────────────────────────────────────────────

  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class RecordingPolicy implements InboundWorkItemPolicy {
    static volatile MessageReceivedEvent lastEvent;
    static volatile Optional<WorkItemCreateRequest> nextResponse = Optional.empty();
    static volatile RuntimeException toThrow;

    static void reset() {
      lastEvent = null;
      nextResponse = Optional.empty();
      toThrow = null;
    }

    static void willReturn(final WorkItemCreateRequest request) {
      nextResponse = Optional.of(request);
    }

    static void willThrow(final RuntimeException ex) {
      toThrow = ex;
    }

    @Override
    public Optional<WorkItemCreateRequest> decide(final MessageReceivedEvent event) {
      lastEvent = event;
      if (toThrow != null) {
        throw toThrow;
      }
      return nextResponse;
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class RecordingTenantContextRunner extends TenantContextRunner {
    static volatile String lastTenancyId;

    static void reset() {
      lastTenancyId = null;
    }

    @Override
    public void runInTenantContext(final String tenancyId, final Runnable work) {
      lastTenancyId = tenancyId;
      super.runInTenantContext(tenancyId, work);
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class StubWorkloadProvider implements WorkloadProvider {
    @Override
    public int getActiveWorkCount(final String workerId) {
      return 0;
    }
  }

  @Alternative
  @Priority(1)
  @ApplicationScoped
  static class NoOpPreferenceProvider implements PreferenceProvider {
    @Override
    public Preferences resolve(final SettingsScope scope) {
      return new MapPreferences(Map.of());
    }
  }

  // ── Fields ────────────────────────────────────────────────────────────────

  @Inject InboundWorkItemBridge bridge;
  @Inject WorkItemStore workItemStore;
  @Inject Instance<MessageObserver> observers;

  @BeforeEach
  void setUp() {
    RecordingPolicy.reset();
    RecordingTenantContextRunner.reset();
    if (workItemStore instanceof InMemoryWorkItemStore mem) {
      mem.clear();
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static MessageReceivedEvent commandEvent(final String tenancyId) {
    return new MessageReceivedEvent(
        null,
        "test-channel",
        UUID.randomUUID(),
        tenancyId,
        MessageType.COMMAND,
        "sender-id",
        "corr-id-1",
        Instant.now(),
        "{\"msg\":\"hello\"}",
        null);
  }

  private static WorkItemCreateRequest minimalRequest() {
    return WorkItemCreateRequest.builder().title("Test WorkItem").build();
  }

  // ── Happy path ────────────────────────────────────────────────────────────

  @Test
  void decide_returnsRequest_workItemCreated() {
    RecordingPolicy.willReturn(minimalRequest());

    bridge.onMessage(commandEvent("tenant-a"));

    final List<WorkItem> items = workItemStore.scanAll();
    assertThat(items).hasSize(1);
    assertThat(items.get(0).title).isEqualTo("Test WorkItem");
  }

  @Test
  void decide_returnsRequest_createdByIsStampedByBridge() {
    RecordingPolicy.willReturn(minimalRequest());

    bridge.onMessage(commandEvent("tenant-a"));

    assertThat(workItemStore.scanAll())
        .extracting(wi -> wi.createdBy)
        .containsExactly("casehub-engine-inbound");
  }

  @Test
  void decide_returnsRequest_tenancyIdThreadedToTenantContextRunner() {
    RecordingPolicy.willReturn(minimalRequest());

    bridge.onMessage(commandEvent("tenant-xyz"));

    assertThat(RecordingTenantContextRunner.lastTenancyId).isEqualTo("tenant-xyz");
  }

  // ── Policy filtering ──────────────────────────────────────────────────────

  @Test
  void decide_returnsEmpty_noWorkItemCreated() {
    // default: RecordingPolicy.nextResponse = Optional.empty()
    bridge.onMessage(commandEvent("tenant-a"));

    assertThat(workItemStore.scanAll()).isEmpty();
  }

  @Test
  void decide_policyReceivesFullEvent_channelName_messageType_senderId_correlationId() {
    final MessageReceivedEvent event = commandEvent("tenant-b");
    bridge.onMessage(event);

    assertThat(RecordingPolicy.lastEvent).isNotNull();
    assertThat(RecordingPolicy.lastEvent.channelName()).isEqualTo("test-channel");
    assertThat(RecordingPolicy.lastEvent.messageType()).isEqualTo(MessageType.COMMAND);
    assertThat(RecordingPolicy.lastEvent.senderId()).isEqualTo("sender-id");
    assertThat(RecordingPolicy.lastEvent.correlationId()).isEqualTo("corr-id-1");
    assertThat(RecordingPolicy.lastEvent.tenancyId()).isEqualTo("tenant-b");
  }

  // ── Fault tolerance ───────────────────────────────────────────────────────

  @Test
  void decide_throws_noWorkItemCreated_noExceptionPropagated() {
    RecordingPolicy.willThrow(new RuntimeException("policy exploded"));

    // Must not propagate — bridge catches policy exceptions
    bridge.onMessage(commandEvent("tenant-a"));

    assertThat(workItemStore.scanAll()).isEmpty();
  }

  // ── Channel routing ───────────────────────────────────────────────────────

  @Test
  void bridge_deliversMessagesFromAnyChannel_toPolicyForFiltering() {
    // The bridge overrides no channels() filter — MessageObserver.channels() returns Set.of()
    // which MessageObserverDispatcher treats as "all channels". Policies must self-filter.
    final MessageReceivedEvent channelA =
        new MessageReceivedEvent(
            null,
            "channel-a",
            UUID.randomUUID(),
            "tenant-a",
            MessageType.COMMAND,
            "sender",
            "corr-a",
            Instant.now(),
            "{}",
            null);
    final MessageReceivedEvent channelB =
        new MessageReceivedEvent(
            null,
            "channel-b",
            UUID.randomUUID(),
            "tenant-a",
            MessageType.COMMAND,
            "sender",
            "corr-b",
            Instant.now(),
            "{}",
            null);

    bridge.onMessage(channelA);
    assertThat(RecordingPolicy.lastEvent.channelName()).isEqualTo("channel-a");

    bridge.onMessage(channelB);
    assertThat(RecordingPolicy.lastEvent.channelName()).isEqualTo("channel-b");
  }

  // ── Wiring ────────────────────────────────────────────────────────────────

  @Test
  void messageObserver_bridgeIsRegistered() {
    // Confirms CDI discovery — bridge is visible to MessageObserverDispatcher.
    // Direct onMessage() invocation in other tests verifies dispatch behavior without
    // requiring the full qhorus runtime stack.
    final boolean bridgePresent =
        observers.stream().anyMatch(o -> o instanceof InboundWorkItemBridge);
    assertThat(bridgePresent).isTrue();
  }
}
