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
import io.casehub.engine.graphql.dto.CaseContextChangeEventType;
import io.casehub.engine.graphql.dto.CaseLifecycleEventType;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseSubscriptionResolverTest {

  private CaseEventPublisher publisher;
  private CaseSubscriptionResolver resolver;

  @BeforeEach
  void setUp() {
    publisher = new CaseEventPublisher();
    resolver = new CaseSubscriptionResolver(publisher);
  }

  @Test
  void caseLifecycle_filters_by_caseId() {
    UUID targetCase = UUID.randomUUID();
    UUID otherCase = UUID.randomUUID();

    AssertSubscriber<CaseLifecycleEventType> subscriber =
        resolver.caseLifecycle(targetCase).subscribe().withSubscriber(AssertSubscriber.create(10));

    publisher.onLifecycleEvent(lifecycleEvent(otherCase, "StartCase", "CaseStarted"));
    publisher.onLifecycleEvent(lifecycleEvent(targetCase, "SuspendCase", "CaseSuspended"));
    publisher.onLifecycleEvent(lifecycleEvent(otherCase, "CancelCase", "CaseCancelled"));

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems()).hasSize(1);
    assertThat(subscriber.getItems().get(0).caseId()).isEqualTo(targetCase);
    assertThat(subscriber.getItems().get(0).eventType()).isEqualTo("CaseSuspended");
  }

  @Test
  void caseLifecycle_maps_all_fields() {
    UUID caseId = UUID.randomUUID();

    AssertSubscriber<CaseLifecycleEventType> subscriber =
        resolver.caseLifecycle(caseId).subscribe().withSubscriber(AssertSubscriber.create(10));

    CaseLifecycleEvent event =
        new CaseLifecycleEvent(
            caseId,
            "tenant-1",
            "StartCase",
            "CaseStarted",
            "ACTIVE",
            "actor-1",
            "Operator",
            "trace-123",
            "onboarding",
            "io.casehub",
            null,
            null,
            null);
    publisher.onLifecycleEvent(event);

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    CaseLifecycleEventType mapped = subscriber.getItems().get(0);
    assertThat(mapped.caseId()).isEqualTo(caseId);
    assertThat(mapped.commandType()).isEqualTo("StartCase");
    assertThat(mapped.eventType()).isEqualTo("CaseStarted");
    assertThat(mapped.caseStatus()).isEqualTo("ACTIVE");
    assertThat(mapped.actorId()).isEqualTo("actor-1");
    assertThat(mapped.actorRole()).isEqualTo("Operator");
    assertThat(mapped.caseDefinitionName()).isEqualTo("onboarding");
    assertThat(mapped.namespace()).isEqualTo("io.casehub");
  }

  @Test
  void caseContextChange_filters_by_caseId() {
    UUID targetCase = UUID.randomUUID();
    UUID otherCase = UUID.randomUUID();

    AssertSubscriber<CaseContextChangeEventType> subscriber =
        resolver
            .caseContextChange(targetCase)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(10));

    publisher.onContextChangedEvent(contextEvent(otherCase, "WORKING"));
    publisher.onContextChangedEvent(contextEvent(targetCase, "WORKING"));

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems()).hasSize(1);
    assertThat(subscriber.getItems().get(0).caseId()).isEqualTo(targetCase);
  }

  @Test
  void caseContextChange_maps_changed_layer() {
    UUID caseId = UUID.randomUUID();

    AssertSubscriber<CaseContextChangeEventType> subscriber =
        resolver.caseContextChange(caseId).subscribe().withSubscriber(AssertSubscriber.create(10));

    publisher.onContextChangedEvent(contextEvent(caseId, "COMPUTED"));

    subscriber.awaitItems(1, Duration.ofSeconds(1));
    assertThat(subscriber.getItems().get(0).changedLayer()).isEqualTo("COMPUTED");
  }

  @Test
  void multiple_lifecycle_subscribers_for_same_case_each_receive() {
    UUID caseId = UUID.randomUUID();

    AssertSubscriber<CaseLifecycleEventType> sub1 =
        resolver.caseLifecycle(caseId).subscribe().withSubscriber(AssertSubscriber.create(10));
    AssertSubscriber<CaseLifecycleEventType> sub2 =
        resolver.caseLifecycle(caseId).subscribe().withSubscriber(AssertSubscriber.create(10));

    publisher.onLifecycleEvent(lifecycleEvent(caseId, "StartCase", "CaseStarted"));

    sub1.awaitItems(1, Duration.ofSeconds(1));
    sub2.awaitItems(1, Duration.ofSeconds(1));
    assertThat(sub1.getItems()).hasSize(1);
    assertThat(sub2.getItems()).hasSize(1);
  }

  private static CaseLifecycleEvent lifecycleEvent(
      UUID caseId, String commandType, String eventType) {
    return CaseLifecycleEvent.of(
        caseId, "tenant-1", commandType, eventType, "ACTIVE", "actor-1", "System", null);
  }

  private static CaseContextChangedEvent contextEvent(UUID caseId, String layer) {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(caseId);
    instance.tenancyId = "tenant-1";
    return new CaseContextChangedEvent(instance, mock(CaseContext.class), layer);
  }
}
