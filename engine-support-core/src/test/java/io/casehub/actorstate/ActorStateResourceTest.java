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
package io.casehub.actorstate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
class ActorStateResourceTest {

  @InjectMock ActorStateAggregator aggregator;

  private ActorStateResponse successResponse() {
    return new ActorStateResponse(
        "agent-x",
        Instant.now(),
        0.82,
        Map.of("sar-drafting", 0.79),
        List.of(
            new ActorStateResponse.WorkItemSummary(
                UUID.randomUUID(), "title", "IN_PROGRESS", "aml", UUID.randomUUID())),
        List.of(),
        List.of(UUID.randomUUID()),
        List.of("ledger", "work", "qhorus", "engine"),
        null,
        null,
        null);
  }

  @Test
  void get_returnsOk_withCorrectShape() {
    Mockito.when(aggregator.forActor("agent-x")).thenReturn(successResponse());

    given()
        .when()
        .get("/actors/agent-x/state")
        .then()
        .statusCode(200)
        .contentType("application/json")
        .body("actorId", equalTo("agent-x"))
        .body("trustScore", equalTo(0.82f))
        .body("sources", hasItems("ledger", "work", "qhorus", "engine"))
        .body("retrievedAt", notNullValue())
        .body("engineActiveCaseIds.size()", equalTo(1));
  }

  @Test
  void get_sourceWarnings_absentWhenAllSucceeded() {
    Mockito.when(aggregator.forActor(Mockito.anyString())).thenReturn(successResponse());

    given()
        .when()
        .get("/actors/agent-x/state")
        .then()
        .statusCode(200)
        .body("sourceWarnings", nullValue());
  }

  @Test
  void get_sourceWarnings_presentWhenSourceFailed() {
    final var resp =
        new ActorStateResponse(
            "agent-x",
            Instant.now(),
            null,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("ledger", "qhorus", "engine"),
            null,
            null,
            Map.of("work", "DB timeout"));
    Mockito.when(aggregator.forActor(Mockito.anyString())).thenReturn(resp);

    given()
        .when()
        .get("/actors/agent-x/state")
        .then()
        .statusCode(200)
        .body("sourceWarnings.work", equalTo("DB timeout"))
        .body("sources.size()", equalTo(3));
  }
}
