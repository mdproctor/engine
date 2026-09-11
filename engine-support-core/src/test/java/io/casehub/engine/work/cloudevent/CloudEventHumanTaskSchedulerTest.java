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
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.spi.HumanTaskScheduleRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.work.api.WorkCloudEventTypes;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CloudEventHumanTaskSchedulerTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";
  private static final String BINDING_NAME = "review-task";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private BlackboardRegistry registry;
  private PlanItemStore planItemStore;

  @SuppressWarnings("unchecked")
  private Event<CloudEvent> cloudEventEmitter = mock(Event.class);

  private CasePlanModel planModel;

  private CloudEventHumanTaskScheduler scheduler;

  @BeforeEach
  void setUp() {
    registry = mock(BlackboardRegistry.class);
    planItemStore = mock(PlanItemStore.class);
    planModel = mock(CasePlanModel.class);

    when(cloudEventEmitter.fireAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

    scheduler = new CloudEventHumanTaskScheduler();
    scheduler.registry = registry;
    scheduler.planItemStore = planItemStore;
    scheduler.cloudEventEmitter = cloudEventEmitter;
  }

  private PlanItem createDispatchingPlanItem() {
    PlanItem item = PlanItem.create(BINDING_NAME, null, 0);
    item.tryMarkDispatching();
    return item;
  }

  @Test
  void inline_schedule_emits_create_cloudevent_and_marks_delegated() throws Exception {
    PlanItem planItem = createDispatchingPlanItem();
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.of(planItem));

    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review document")
            .scope("case-review")
            .expiresIn(Duration.ofHours(24))
            .outcomes(Set.of("approve", "reject"))
            .build();

    HumanTaskScheduleRequest request =
        new HumanTaskScheduleRequest(
            CASE_ID,
            TENANCY_ID,
            BINDING_NAME,
            target,
            Map.of("document", "doc-123"),
            null,
            "io.casehub.ReviewResolution",
            Set.of("reviewers", "managers"),
            Set.of("alice", "bob"),
            Instant.parse("2026-08-26T10:00:00Z"),
            null,
            "Review document",
            "case-review",
            List.of(),
            Map.of("alice", 0.8, "bob", 0.6));

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    CloudEvent ce = ceCaptor.getValue();

    assertThat(ce.getType()).isEqualTo(WorkCloudEventTypes.CREATE);
    assertThat(ce.getSource().toString()).isEqualTo("/engine/cases/" + CASE_ID);
    assertThat(ce.getDataContentType()).isEqualTo("application/json");
    assertThat(ce.getExtension("tenancyid")).isEqualTo(TENANCY_ID);

    JsonNode data = MAPPER.readTree(ce.getData().toBytes());
    assertThat(data.get("title").asText()).isEqualTo("Review document");
    assertThat(data.get("callerRef").asText())
        .isEqualTo("case:" + CASE_ID + "/pi:" + planItem.id());
    assertThat(data.get("candidateGroups").asText()).contains("reviewers");
    assertThat(data.get("candidateUsers").asText()).contains("alice");
    assertThat(data.get("scope").asText()).isEqualTo("case-review");
    assertThat(data.get("resolutionTypeName").asText()).isEqualTo("io.casehub.ReviewResolution");
    assertThat(data.has("payload")).isTrue();
    assertThat(data.has("candidateScores")).isTrue();
    assertThat(data.has("permittedOutcomes")).isTrue();

    ArgumentCaptor<PlanItemSaveRequest> saveCaptor =
        ArgumentCaptor.forClass(PlanItemSaveRequest.class);
    verify(planItemStore).save(saveCaptor.capture(), any());
    PlanItemSaveRequest saved = saveCaptor.getValue();
    assertThat(saved.status()).isEqualTo(TaskStatus.DELEGATED);
    assertThat(saved.caseId()).isEqualTo(CASE_ID);
    assertThat(saved.planItemId()).isEqualTo(planItem.id());

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);
  }

  @Test
  void template_schedule_includes_templateId() throws Exception {
    PlanItem planItem = createDispatchingPlanItem();
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.of(planItem));

    UUID templateId = UUID.randomUUID();
    HumanTaskTarget target =
        HumanTaskTarget.template(templateId.toString()).title("Templated task").build();

    HumanTaskScheduleRequest request =
        new HumanTaskScheduleRequest(
            CASE_ID,
            TENANCY_ID,
            BINDING_NAME,
            target,
            Map.of(),
            null,
            null,
            Set.of("group1"),
            Set.of(),
            null,
            null,
            "Templated task",
            null,
            List.of(),
            Map.of());

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    JsonNode data = MAPPER.readTree(ceCaptor.getValue().getData().toBytes());

    assertThat(data.get("templateId").asText()).isEqualTo(templateId.toString());
    assertThat(ceCaptor.getValue().getExtension("templateid")).isEqualTo(templateId.toString());
  }

  @Test
  void planItem_not_found_skips_emission() {
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.empty());

    scheduler.schedule(minimalRequest());

    verify(cloudEventEmitter, never()).fireAsync(any());
    verify(planItemStore, never()).save(any(), any());
  }

  @Test
  void planItem_not_dispatching_skips_emission() {
    PlanItem planItem = PlanItem.create(BINDING_NAME, null, 0);
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.of(planItem));

    scheduler.schedule(minimalRequest());

    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.PENDING);
    verify(cloudEventEmitter, never()).fireAsync(any());
    verify(planItemStore, never()).save(any(), any());
  }

  @Test
  void no_plan_model_skips_emission() {
    when(registry.get(CASE_ID)).thenReturn(Optional.empty());

    scheduler.schedule(minimalRequest());

    verify(cloudEventEmitter, never()).fireAsync(any());
  }

  @Test
  void deadline_selects_earliest_of_expiresAt_and_caseBudget() throws Exception {
    PlanItem planItem = createDispatchingPlanItem();
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.of(planItem));

    Instant earlierDeadline = Instant.parse("2026-08-25T08:00:00Z");
    Instant laterDeadline = Instant.parse("2026-08-26T08:00:00Z");

    HumanTaskTarget target = HumanTaskTarget.inline().title("Deadline test").build();
    HumanTaskScheduleRequest request =
        new HumanTaskScheduleRequest(
            CASE_ID,
            TENANCY_ID,
            BINDING_NAME,
            target,
            Map.of(),
            null,
            null,
            Set.of(),
            Set.of(),
            laterDeadline,
            earlierDeadline,
            "Deadline test",
            null,
            List.of(),
            Map.of());

    scheduler.schedule(request);

    ArgumentCaptor<CloudEvent> ceCaptor = ArgumentCaptor.forClass(CloudEvent.class);
    verify(cloudEventEmitter).fireAsync(ceCaptor.capture());
    JsonNode data = MAPPER.readTree(ceCaptor.getValue().getData().toBytes());

    assertThat(data.get("expiresAt").asText()).isEqualTo(earlierDeadline.toString());
  }

  private HumanTaskScheduleRequest minimalRequest() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Test").build();
    return new HumanTaskScheduleRequest(
        CASE_ID,
        TENANCY_ID,
        BINDING_NAME,
        target,
        Map.of(),
        null,
        null,
        Set.of(),
        Set.of(),
        null,
        null,
        "Test",
        null,
        List.of(),
        Map.of());
  }
}
