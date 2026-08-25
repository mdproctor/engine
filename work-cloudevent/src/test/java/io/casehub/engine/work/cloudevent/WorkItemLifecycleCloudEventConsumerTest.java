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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.work.api.WorkCloudEventTypes;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkItemLifecycleCloudEventConsumerTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";
  private static final String PLAN_ITEM_ID = "pi-001";
  private static final long GATE_ID = 42L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PlanItemCompletionApplier planItemApplier;
  private GateCompletionApplier gateApplier;
  private WorkItemLifecycleCloudEventConsumer consumer;

  @BeforeEach
  void setUp() {
    planItemApplier = mock(PlanItemCompletionApplier.class);
    gateApplier = mock(GateCompletionApplier.class);

    consumer = new WorkItemLifecycleCloudEventConsumer();
    consumer.planItemApplier = planItemApplier;
    consumer.gateApplier = gateApplier;
  }

  @Test
  void completed_planItem_delegates_to_applier() {
    CloudEvent ce =
        buildLifecycleCE(
            WorkCloudEventTypes.COMPLETED,
            planItemCallerRef(),
            "{\"approved\": true}",
            "io.casehub.Resolution");

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier)
        .apply(
            CASE_ID,
            PLAN_ITEM_ID,
            TaskStatus.COMPLETED,
            "{\"approved\": true}",
            "io.casehub.Resolution");
  }

  @Test
  void rejected_planItem_delegates_with_rejected_status() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.REJECTED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.REJECTED, null, null);
  }

  @Test
  void faulted_planItem_delegates_with_faulted_status() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.FAULTED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.FAULTED, null, null);
  }

  @Test
  void expired_planItem_maps_to_faulted() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.EXPIRED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.FAULTED, null, null);
  }

  @Test
  void escalated_planItem_maps_to_faulted() {
    CloudEvent ce =
        buildLifecycleCE(WorkCloudEventTypes.ESCALATED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.FAULTED, null, null);
  }

  @Test
  void obsolete_planItem_delegates_with_obsolete_status() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.OBSOLETE, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.OBSOLETE, null, null);
  }

  @Test
  void cancelled_planItem_delegates_with_cancelled_status() {
    CloudEvent ce =
        buildLifecycleCE(WorkCloudEventTypes.CANCELLED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).apply(CASE_ID, PLAN_ITEM_ID, TaskStatus.CANCELLED, null, null);
  }

  @Test
  void suspended_planItem_delegates_to_applySuspend() {
    CloudEvent ce =
        buildLifecycleCE(WorkCloudEventTypes.SUSPENDED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).applySuspend(CASE_ID, PLAN_ITEM_ID);
    verify(planItemApplier, never())
        .apply(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void resumed_planItem_delegates_to_applyResume() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.RESUMED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(planItemApplier).applyResume(CASE_ID, PLAN_ITEM_ID);
    verify(planItemApplier, never())
        .apply(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completed_gate_delegates_to_gate_applier() {
    CloudEvent ce =
        buildLifecycleCE(WorkCloudEventTypes.COMPLETED, gateCallerRef(), "{\"ok\":true}", null);

    consumer.onLifecycleCloudEvent(ce);

    verify(gateApplier)
        .apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.COMPLETED, "{\"ok\":true}", null);
  }

  @Test
  void rejected_gate_delegates_with_rejected() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.REJECTED, gateCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(gateApplier).apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.REJECTED, null, null);
  }

  @Test
  void expired_gate_maps_to_faulted() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.EXPIRED, gateCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verify(gateApplier).apply(CASE_ID, TENANCY_ID, GATE_ID, TaskStatus.FAULTED, null, null);
  }

  @Test
  void non_lifecycle_type_ignored() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.CREATED, planItemCallerRef(), null, null);

    consumer.onLifecycleCloudEvent(ce);

    verifyNoInteractions(planItemApplier);
    verifyNoInteractions(gateApplier);
  }

  @Test
  void missing_tenancyid_skips_processing() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("callerRef", planItemCallerRef());

    CloudEvent ce =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.COMPLETED)
            .withSource(URI.create("/work"))
            .withDataContentType("application/json")
            .withData(data.toString().getBytes())
            .build();

    consumer.onLifecycleCloudEvent(ce);

    verifyNoInteractions(planItemApplier);
  }

  @Test
  void invalid_callerRef_skips_processing() {
    CloudEvent ce = buildLifecycleCE(WorkCloudEventTypes.COMPLETED, "invalid-ref", null, null);

    consumer.onLifecycleCloudEvent(ce);

    verifyNoInteractions(planItemApplier);
    verifyNoInteractions(gateApplier);
  }

  @Test
  void null_data_skips_processing() {
    CloudEvent ce =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.COMPLETED)
            .withSource(URI.create("/work"))
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, TENANCY_ID)
            .build();

    consumer.onLifecycleCloudEvent(ce);

    verifyNoInteractions(planItemApplier);
  }

  private String planItemCallerRef() {
    return "case:" + CASE_ID + "/pi:" + PLAN_ITEM_ID;
  }

  private String gateCallerRef() {
    return "case:" + CASE_ID + "/gate:" + GATE_ID;
  }

  private CloudEvent buildLifecycleCE(
      String type, String callerRef, String resolution, String resolutionTypeName) {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("callerRef", callerRef);
    if (resolution != null) {
      data.put("resolution", resolution);
    }
    if (resolutionTypeName != null) {
      data.put("resolutionTypeName", resolutionTypeName);
    }

    return CloudEventBuilder.v1()
        .withId(UUID.randomUUID().toString())
        .withType(type)
        .withSource(URI.create("/work"))
        .withDataContentType("application/json")
        .withData(data.toString().getBytes())
        .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, TENANCY_ID)
        .build();
  }
}
