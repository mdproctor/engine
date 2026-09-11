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
package io.casehub.engine.work.cloudevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.spi.QuorumConfig;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.engine.common.spi.ActionGateScheduleRequest;
import io.casehub.work.api.WorkCloudEventTypes;
import io.casehub.worker.api.PlannedAction;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CloudEventActionGateSchedulerTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";
  private static final long GATE_ID = 42L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @SuppressWarnings("unchecked")
  private Event<CloudEvent> cloudEventEmitter = mock(Event.class);

  private CloudEventActionGateScheduler scheduler;

  @BeforeEach
  void setUp() {
    when(cloudEventEmitter.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

    scheduler = new CloudEventActionGateScheduler();
    scheduler.cloudEventEmitter = cloudEventEmitter;
  }

  @Test
  void single_approver_gate_emits_create_cloudevent() throws Exception {
    PlannedAction action =
        PlannedAction.of("File SAR report", "sar.file", Map.of("accountId", "ACC-123"));
    RiskDecision.GateRequired gate =
        new RiskDecision.GateRequired(
            "High-value transaction requires approval",
            true,
            StaticSetStrategy.of(Set.of("compliance")),
            Duration.ofHours(4),
            "aml-review",
            null,
            null);

    ActionGateScheduleRequest request =
        new ActionGateScheduleRequest(
            CASE_ID,
            TENANCY_ID,
            GATE_ID,
            action,
            gate,
            Set.of("compliance"),
            "io.casehub.GateResolution");

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    CloudEvent ce = ceCaptor.getValue();

    assertThat(ce.getType()).isEqualTo(WorkCloudEventTypes.CREATE);
    assertThat(ce.getSource().toString())
        .isEqualTo("/engine/cases/" + CASE_ID + "/gates/" + GATE_ID);
    assertThat(ce.getDataContentType()).isEqualTo("application/json");
    assertThat(ce.getExtension("tenancyid")).isEqualTo(TENANCY_ID);

    JsonNode data = MAPPER.readTree(ce.getData().toBytes());
    assertThat(data.get("callerRef").asText()).isEqualTo("case:" + CASE_ID + "/gate:" + GATE_ID);
    assertThat(data.get("title").asText()).isEqualTo("High-value transaction requires approval");
    assertThat(data.get("candidateGroups").asText()).isEqualTo("compliance");
    assertThat(data.get("scope").asText()).isEqualTo("aml-review");
    assertThat(data.get("resolutionTypeName").asText()).isEqualTo("io.casehub.GateResolution");
    assertThat(data.has("payload")).isTrue();

    JsonNode payload = MAPPER.readTree(data.get("payload").asText());
    assertThat(payload.get("description").asText()).isEqualTo("File SAR report");
    assertThat(payload.get("actionType").asText()).isEqualTo("sar.file");
    assertThat(payload.get("reversible").asBoolean()).isTrue();
  }

  @Test
  void quorum_gate_logs_warning_and_skips() {
    PlannedAction action = PlannedAction.of("Quorum action", "quorum.type");
    QuorumConfig quorum = QuorumConfig.majority(3);
    RiskDecision.GateRequired gate =
        new RiskDecision.GateRequired(
            "Needs quorum",
            false,
            StaticSetStrategy.of(Set.of("board")),
            Duration.ofHours(1),
            null,
            null,
            quorum);

    ActionGateScheduleRequest request =
        new ActionGateScheduleRequest(
            CASE_ID, TENANCY_ID, GATE_ID, action, gate, Set.of("board"), null);

    scheduler.schedule(request);

    verify(cloudEventEmitter, never()).fireAsync(any());
  }

  @Test
  void null_candidateGroups_omitted_from_data() throws Exception {
    PlannedAction action = PlannedAction.of("Simple action", "simple.type");
    RiskDecision.GateRequired gate =
        new RiskDecision.GateRequired(
            "Needs review", false, null, Duration.ofHours(2), null, null, null);

    ActionGateScheduleRequest request =
        new ActionGateScheduleRequest(CASE_ID, TENANCY_ID, GATE_ID, action, gate, null, null);

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    JsonNode data = MAPPER.readTree(ceCaptor.getValue().getData().toBytes());

    assertThat(data.has("candidateGroups")).isFalse();
  }

  @Test
  void expiresAt_computed_from_gate_expiresIn() throws Exception {
    PlannedAction action = PlannedAction.of("Timed action", "timed.type");
    RiskDecision.GateRequired gate =
        new RiskDecision.GateRequired(
            "Timed review", true, null, Duration.ofHours(6), null, null, null);

    ActionGateScheduleRequest request =
        new ActionGateScheduleRequest(CASE_ID, TENANCY_ID, GATE_ID, action, gate, null, null);

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    JsonNode data = MAPPER.readTree(ceCaptor.getValue().getData().toBytes());

    assertThat(data.has("expiresAt")).isTrue();
  }
}
