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
import io.casehub.api.model.JudgmentTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.spi.RiskDecision;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.spi.JudgmentPayload;
import io.casehub.engine.common.spi.JudgmentRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.worker.api.PlannedAction;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CloudEventJudgmentSchedulerTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";
  private static final long GATE_ID = 99L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private BlackboardRegistry registry;
  private PlanItemStore planItemStore;

  @SuppressWarnings("unchecked")
  private Event<CloudEvent> cloudEventEmitter = mock(Event.class);

  private CloudEventJudgmentScheduler scheduler;

  @BeforeEach
  void setUp() {
    registry = mock(BlackboardRegistry.class);
    planItemStore = mock(PlanItemStore.class);
    when(cloudEventEmitter.fireAsync(any())).thenReturn(mock(CompletionStage.class));

    scheduler = new CloudEventJudgmentScheduler();
    scheduler.registry = registry;
    scheduler.planItemStore = planItemStore;
    scheduler.cloudEventEmitter = cloudEventEmitter;
  }

  @Test
  void binding_payload_emits_cloudevent_with_correct_data() throws Exception {
    PlanItem item = PlanItem.create("review-binding", null, 0);
    item.tryMarkDispatching();

    CasePlanModel plan = mock(CasePlanModel.class);
    when(plan.getPlanItemByBindingName("review-binding")).thenReturn(Optional.of(item));
    when(registry.get(CASE_ID)).thenReturn(Optional.of(plan));

    var target =
        JudgmentTarget.forHuman()
            .prompt("Review the analysis")
            .candidateGroups(Set.of("reviewers"))
            .title("Analysis Review")
            .outcomes(Set.of("approve", "reject"))
            .expiresIn(Duration.ofHours(24))
            .build();

    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of("key", "value"),
            null,
            null,
            Set.of("reviewers"),
            Set.of(),
            null,
            null,
            "Analysis Review",
            null,
            List.of(),
            Map.of());

    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "review-binding", target, payload);

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> captor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(captor.capture());

    CloudEvent ce = captor.getValue();
    assertThat(ce.getType()).isEqualTo("io.casehub.work.workitem.create");
    assertThat(ce.getSource().toString()).contains(CASE_ID.toString());

    JsonNode data = MAPPER.readTree(new String(ce.getData().toBytes()));
    assertThat(data.has("callerRef")).isTrue();
    assertThat(data.get("title").asText()).isEqualTo("Analysis Review");
    assertThat(data.get("candidateGroups").asText()).isEqualTo("reviewers");
    assertThat(data.has("expiresAt")).isTrue();
    assertThat(data.get("permittedOutcomes")).isNotNull();

    verify(planItemStore).save(any(PlanItemSaveRequest.class), any());
    assertThat(item.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void binding_payload_skips_when_no_plan() {
    when(registry.get(CASE_ID)).thenReturn(Optional.empty());

    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of(), null, null, null, null, null, null, null, null, List.of(), Map.of());
    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "b1", target, payload);

    scheduler.schedule(request);

    verify(cloudEventEmitter, never()).fireAsync(any());
  }

  @Test
  void binding_payload_skips_when_not_dispatching() {
    PlanItem item = PlanItem.create("b1", null, 0);

    CasePlanModel plan = mock(CasePlanModel.class);
    when(plan.getPlanItemByBindingName("b1")).thenReturn(Optional.of(item));
    when(registry.get(CASE_ID)).thenReturn(Optional.of(plan));

    var target = JudgmentTarget.forHuman().prompt("Review").build();
    var payload =
        new JudgmentPayload.BindingPayload(
            Map.of(), null, null, null, null, null, null, null, null, List.of(), Map.of());
    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "b1", target, payload);

    scheduler.schedule(request);

    verify(cloudEventEmitter, never()).fireAsync(any());
  }

  @Test
  void gate_payload_emits_cloudevent() throws Exception {
    var target = JudgmentTarget.forHuman().prompt("Approve action").build();
    var action =
        PlannedAction.of("Cancel subscription", "sub.cancel", Map.of("accountId", "ACC-123"));
    var gate = new RiskDecision.GateRequired("Review needed", true, null, null, null, null, null);
    var payload =
        new JudgmentPayload.GatePayload(
            GATE_ID, action, gate, Set.of("approvers"), null, Map.of("output", "value"));
    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "__gate__", target, payload);

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> captor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(captor.capture());

    CloudEvent ce = captor.getValue();
    assertThat(ce.getType()).isEqualTo("io.casehub.work.workitem.create");
    assertThat(ce.getSource().toString()).contains("gates/" + GATE_ID);

    JsonNode data = MAPPER.readTree(new String(ce.getData().toBytes()));
    assertThat(data.get("callerRef").asText()).contains("gate:");
    assertThat(data.get("title").asText()).isEqualTo("Review needed");
    assertThat(data.get("candidateGroups").asText()).isEqualTo("approvers");

    JsonNode payloadJson = MAPPER.readTree(data.get("payload").asText());
    assertThat(payloadJson.get("description").asText()).isEqualTo("Cancel subscription");
    assertThat(payloadJson.get("actionType").asText()).isEqualTo("sub.cancel");
    assertThat(payloadJson.get("reversible").asBoolean()).isTrue();
  }

  @Test
  void gate_payload_quorum_logs_warning_and_skips() {
    var target = JudgmentTarget.forHuman().prompt("Approve").build();
    var action = PlannedAction.of("Action", "type", Map.of());
    var gate =
        new RiskDecision.GateRequired(
            "Review", true, null, null, null, null, io.casehub.api.spi.QuorumConfig.majority(3));
    var payload =
        new JudgmentPayload.GatePayload(GATE_ID, action, gate, Set.of("approvers"), null, Map.of());
    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "__gate__", target, payload);

    scheduler.schedule(request);

    verify(cloudEventEmitter, never()).fireAsync(any());
  }

  @Test
  void gate_payload_expiresAt_computed_from_gate_expiresIn() throws Exception {
    Instant before = Instant.now();
    var target = JudgmentTarget.forHuman().prompt("Approve").build();
    var action = PlannedAction.of("Action", "type", Map.of());
    var gate =
        new RiskDecision.GateRequired("Review", false, null, Duration.ofHours(2), null, null, null);
    var payload = new JudgmentPayload.GatePayload(GATE_ID, action, gate, Set.of(), null, Map.of());
    var request = new JudgmentRequest(CASE_ID, TENANCY_ID, "__gate__", target, payload);

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> captor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(captor.capture());

    JsonNode data = MAPPER.readTree(new String(captor.getValue().getData().toBytes()));
    assertThat(data.has("expiresAt")).isTrue();
    Instant expiresAt = Instant.parse(data.get("expiresAt").asText());
    assertThat(expiresAt).isAfter(before.plus(Duration.ofHours(1)));
    assertThat(expiresAt).isBefore(before.plus(Duration.ofHours(3)));
  }
}
