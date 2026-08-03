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
package io.casehub.engine.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import io.casehub.engine.rest.dto.CaseStreamEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CaseStreamResourceTest {

  @Inject CaseStreamBroadcaster broadcaster;

  @Test
  void broadcasterFiltersByCaseId() {
    UUID targetCase = UUID.randomUUID();
    UUID otherCase = UUID.randomUUID();

    var subscription =
        broadcaster.stream(targetCase)
            .select()
            .first(1)
            .collect()
            .asList()
            .subscribeAsCompletionStage();

    broadcaster.onPlanItemChanged(
        new PlanItemStateChangedEvent(
            otherCase, "pi-other", "style-review", TaskStatus.PENDING, TaskStatus.RUNNING, "t1"));

    broadcaster.onPlanItemChanged(
        new PlanItemStateChangedEvent(
            targetCase, "pi-1", "security-review", TaskStatus.RUNNING, TaskStatus.COMPLETED, "t1"));

    List<CaseStreamEvent> result = subscription.toCompletableFuture().join();

    assertEquals(1, result.size());
    assertEquals("plan-item", result.get(0).type());
    assertEquals("pi-1", result.get(0).data().get("planItemId"));
    assertEquals("COMPLETED", result.get(0).data().get("newStatus"));
  }

  @Test
  void broadcasterEmitsContextUpdatedEvents() {
    UUID caseId = UUID.randomUUID();

    var subscription =
        broadcaster.stream(caseId)
            .select()
            .first(1)
            .collect()
            .asList()
            .subscribeAsCompletionStage();

    broadcaster.onContextUpdated(new CaseContextUpdatedEvent(caseId, "analysis", "t1"));

    List<CaseStreamEvent> result = subscription.toCompletableFuture().join();

    assertEquals(1, result.size());
    assertEquals("context", result.get(0).type());
    assertEquals("analysis", result.get(0).data().get("changedLayer"));
  }

  @Test
  void planItemEventHandlesNullPreviousStatus() {
    CaseStreamEvent event =
        CaseStreamEvent.planItem(
            new PlanItemStateChangedEvent(
                UUID.randomUUID(), "pi-new", "code-analysis", null, TaskStatus.PENDING, "t1"));

    assertEquals("NONE", event.data().get("previousStatus"));
    assertEquals("PENDING", event.data().get("newStatus"));
  }

  @Test
  void sseEndpointRespondsWithEventStream() {
    io.restassured.RestAssured.given()
        .when()
        .head("/api/v1/cases/" + UUID.randomUUID() + "/stream")
        .then()
        .statusCode(200);
  }
}
