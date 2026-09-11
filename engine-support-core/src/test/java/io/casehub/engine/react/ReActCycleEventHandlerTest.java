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
package io.casehub.engine.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ai.TokenUsage;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReActCycleEventHandlerTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  @Test
  void writesEventLogWithCorrectFields() throws Exception {
    var repo = mock(EventLogRepository.class);
    var handler = new ReActCycleEventHandler();

    try {
      var field = ReActCycleEventHandler.class.getDeclaredField("eventLogRepository");
      field.setAccessible(true);
      field.set(handler, repo);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    var caseId = UUID.randomUUID();
    var event =
        new ReActCycleEvent(
            caseId,
            "research-analyst",
            "tenant-1",
            2,
            "I should search for more data",
            List.of(
                new ToolCallRecord(
                    "web-search",
                    Map.of("query", "test"),
                    Map.of("results", List.of("r1", "r2")),
                    "worker",
                    Duration.ofMillis(450))),
            new TokenUsage(1200, 85));

    var json = new JsonObject(MAPPER.writeValueAsString(event));
    handler.onReactCycle(json);

    var captor = ArgumentCaptor.forClass(EventLog.class);
    verify(repo).append(captor.capture(), eq("tenant-1"));

    var eventLog = captor.getValue();
    assertThat(eventLog.getCaseId()).isEqualTo(caseId);
    assertThat(eventLog.getEventType()).isEqualTo(CaseHubEventType.REACT_CYCLE);
    assertThat(eventLog.getWorkerId()).isEqualTo("research-analyst");

    var meta = eventLog.getMetadata();
    assertThat(meta.get("cycleIndex").asInt()).isEqualTo(2);
    assertThat(meta.get("reasoningText").asText()).isEqualTo("I should search for more data");
    assertThat(meta.get("toolCalls").isArray()).isTrue();
    assertThat(meta.get("toolCalls")).hasSize(1);
    assertThat(meta.get("tokenUsage").get("inputTokens").asInt()).isEqualTo(1200);
    assertThat(meta.get("tokenUsage").get("outputTokens").asInt()).isEqualTo(85);
  }

  @Test
  void handlesNullTokenUsage() throws Exception {
    var repo = mock(EventLogRepository.class);
    var handler = new ReActCycleEventHandler();

    try {
      var field = ReActCycleEventHandler.class.getDeclaredField("eventLogRepository");
      field.setAccessible(true);
      field.set(handler, repo);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    var event =
        new ReActCycleEvent(
            UUID.randomUUID(),
            "worker",
            "tenant-1",
            0,
            "",
            List.of(
                new ToolCallRecord(
                    "calc", Map.of(), Map.of("result", 42), "local", Duration.ofMillis(5))),
            null);

    var json = new JsonObject(MAPPER.writeValueAsString(event));
    handler.onReactCycle(json);

    var captor = ArgumentCaptor.forClass(EventLog.class);
    verify(repo).append(captor.capture(), eq("tenant-1"));

    var meta = captor.getValue().getMetadata();
    assertThat(meta.has("tokenUsage")).isFalse();
  }
}
