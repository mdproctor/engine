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
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.spi.HumanTaskScheduleRequest;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItem;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.casehub.work.api.WorkCloudEventTypes;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.event.Event;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class DistributedHumanTaskRoundTripTest {

  private static final UUID CASE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private static final String TENANCY_ID = "tenant-1";
  private static final String BINDING_NAME = "review-task";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void outbound_create_then_inbound_completed_round_trip() throws Exception {
    BlackboardRegistry registry = mock(BlackboardRegistry.class);
    PlanItemStore planItemStore = mock(PlanItemStore.class);
    PlanItemCompletionApplier planItemApplier = mock(PlanItemCompletionApplier.class);
    GateCompletionApplier gateApplier = mock(GateCompletionApplier.class);

    CopyOnWriteArrayList<CloudEvent> emittedEvents = new CopyOnWriteArrayList<>();
    @SuppressWarnings("unchecked")
    Event<CloudEvent> emitter = mock(Event.class);
    when(emitter.fireAsync(any()))
        .thenAnswer(
            inv -> {
              emittedEvents.add(inv.getArgument(0));
              return CompletableFuture.completedFuture(null);
            });

    PlanItem planItem = PlanItem.create(BINDING_NAME, null, 0);
    planItem.tryMarkDispatching();

    CasePlanModel planModel = mock(CasePlanModel.class);
    when(registry.get(CASE_ID)).thenReturn(Optional.of(planModel));
    when(planModel.getPlanItemByBindingName(BINDING_NAME)).thenReturn(Optional.of(planItem));

    // --- Outbound: emit CREATE CloudEvent ---
    CloudEventHumanTaskScheduler scheduler = new CloudEventHumanTaskScheduler();
    scheduler.registry = registry;
    scheduler.planItemStore = planItemStore;
    scheduler.cloudEventEmitter = emitter;

    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review document").outcomes(Set.of("approve")).build();

    scheduler.schedule(
        new HumanTaskScheduleRequest(
            CASE_ID,
            TENANCY_ID,
            BINDING_NAME,
            target,
            Map.of("doc", "D-1"),
            null,
            null,
            Set.of("reviewers"),
            Set.of(),
            null,
            null,
            "Review document",
            null,
            List.of(),
            Map.of()));

    assertThat(emittedEvents).hasSize(1);
    CloudEvent outboundCE = emittedEvents.get(0);
    assertThat(outboundCE.getType()).isEqualTo(WorkCloudEventTypes.CREATE);
    assertThat(planItem.getStatus()).isEqualTo(TaskStatus.DELEGATED);

    // Extract callerRef from the emitted CloudEvent
    JsonNode outboundData = MAPPER.readTree(outboundCE.getData().toBytes());
    String callerRef = outboundData.get("callerRef").asText();
    assertThat(callerRef).startsWith("case:" + CASE_ID + "/pi:");

    // --- Inbound: simulate work-side COMPLETED response ---
    WorkItemLifecycleCloudEventConsumer consumer = new WorkItemLifecycleCloudEventConsumer();
    consumer.planItemApplier = planItemApplier;
    consumer.gateApplier = gateApplier;

    com.fasterxml.jackson.databind.node.ObjectNode responseData = MAPPER.createObjectNode();
    responseData.put("callerRef", callerRef);
    responseData.put("resolution", "{\"outcome\":\"approved\"}");
    responseData.put("resolutionTypeName", "java.util.Map");

    CloudEvent inboundCE =
        CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withType(WorkCloudEventTypes.COMPLETED)
            .withSource(URI.create("/work"))
            .withDataContentType("application/json")
            .withData(responseData.toString().getBytes())
            .withExtension(WorkCloudEventTypes.EXT_TENANCY_ID, TENANCY_ID)
            .build();

    consumer.onLifecycleCloudEvent(inboundCE);

    // Extract planItemId from the callerRef for verification
    String planItemId = callerRef.substring(callerRef.indexOf("/pi:") + 4);
    org.mockito.Mockito.verify(planItemApplier)
        .apply(
            CASE_ID,
            planItemId,
            TaskStatus.COMPLETED,
            "{\"outcome\":\"approved\"}",
            "java.util.Map");
  }
}
