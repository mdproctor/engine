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
package io.casehub.engine.actorstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@QuarkusTest
class ActorStateResourceTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new JavaTimeModule());

  @InjectMock ActorStateAggregator aggregator;

  @TestHTTPResource("/actors/agent-x/state")
  URI endpoint;

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

  private HttpResponse<String> get() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void get_returnsOk_withCorrectShape() throws Exception {
    Mockito.when(aggregator.forActor("agent-x")).thenReturn(successResponse());

    var response = get();
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("content-type"))
        .hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("actorId").asText()).isEqualTo("agent-x");
    assertThat(body.get("trustScore").asDouble()).isEqualTo(0.82);
    assertThat(body.get("retrievedAt").asText()).isNotEmpty();
    assertThat(body.get("engineActiveCaseIds")).hasSize(1);
    assertThat(body.get("sources")).hasSize(4);
  }

  @Test
  void get_sourceWarnings_absentWhenAllSucceeded() throws Exception {
    Mockito.when(aggregator.forActor(Mockito.anyString())).thenReturn(successResponse());

    var response = get();
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.has("sourceWarnings")).isFalse();
  }

  @Test
  void get_sourceWarnings_presentWhenSourceFailed() throws Exception {
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

    var response = get();
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode body = MAPPER.readTree(response.body());
    assertThat(body.get("sourceWarnings").get("work").asText()).isEqualTo("DB timeout");
    assertThat(body.get("sources")).hasSize(3);
  }
}
