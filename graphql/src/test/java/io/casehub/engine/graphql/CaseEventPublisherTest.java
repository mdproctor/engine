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
package io.casehub.engine.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseEventPublisherTest {

  private CaseEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new CaseEventPublisher();
  }

  @Test
  void lifecycle_event_reaches_subscriber() {
    AssertSubscriber<CaseLifecycleEvent> subscriber =
        publisher.lifecycleStream().subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseLifecycleEvent event = lifecycleEvent(UUID.randomUUID(), "StartCase", "CaseStarted");
    publisher.onLifecycleEvent(event);

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems()).containsExactly(event);
  }

  @Test
  void multiple_subscribers_each_receive_lifecycle_event() {
    AssertSubscriber<CaseLifecycleEvent> sub1 =
        publisher.lifecycleStream().subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<CaseLifecycleEvent> sub2 =
        publisher.lifecycleStream().subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseLifecycleEvent event = lifecycleEvent(UUID.randomUUID(), "SuspendCase", "CaseSuspended");
    publisher.onLifecycleEvent(event);

    sub1.awaitItems(1, Duration.ofSeconds(1));
    sub2.awaitItems(1, Duration.ofSeconds(1));
    assertThat(sub1.getItems()).containsExactly(event);
    assertThat(sub2.getItems()).containsExactly(event);
  }

  @Test
  void context_change_event_reaches_subscriber() {
    AssertSubscriber<CaseContextChangedEvent> subscriber =
        publisher.contextChangeStream().subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    CaseContextChangedEvent event =
        new CaseContextChangedEvent(instance, mock(CaseContext.class), "WORKING");

    publisher.onContextChangedEvent(event);

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems()).containsExactly(event);
  }

  @Test
  void disconnected_subscriber_stops_receiving() {
    AssertSubscriber<CaseLifecycleEvent> subscriber =
        publisher.lifecycleStream().subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseLifecycleEvent event1 = lifecycleEvent(UUID.randomUUID(), "StartCase", "CaseStarted");
    publisher.onLifecycleEvent(event1);
    subscriber.awaitItems(1, Duration.ofSeconds(1));

    subscriber.cancel();

    CaseLifecycleEvent event2 = lifecycleEvent(UUID.randomUUID(), "CancelCase", "CaseCancelled");
    publisher.onLifecycleEvent(event2);

    assertThat(subscriber.getItems()).hasSize(1);
  }

  @Test
  void events_before_any_subscriber_are_dropped() {
    CaseLifecycleEvent earlyEvent = lifecycleEvent(UUID.randomUUID(), "StartCase", "CaseStarted");
    publisher.onLifecycleEvent(earlyEvent);

    AssertSubscriber<CaseLifecycleEvent> subscriber =
        publisher.lifecycleStream().subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseLifecycleEvent lateEvent = lifecycleEvent(UUID.randomUUID(), "CancelCase", "CaseCancelled");
    publisher.onLifecycleEvent(lateEvent);

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems()).containsExactly(lateEvent);
  }

  private static CaseLifecycleEvent lifecycleEvent(
      UUID caseId, String commandType, String eventType) {
    return CaseLifecycleEvent.of(
        caseId, "tenant-1", commandType, eventType, "ACTIVE", "actor-1", "System", null);
  }
}
