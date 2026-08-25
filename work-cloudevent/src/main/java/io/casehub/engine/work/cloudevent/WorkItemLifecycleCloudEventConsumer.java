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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.spi.CallerRefParser;
import io.casehub.engine.planning.completion.GateCompletionApplier;
import io.casehub.engine.planning.completion.PlanItemCompletionApplier;
import io.casehub.work.api.WorkCloudEventTypes;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkItemLifecycleCloudEventConsumer {

  private static final Logger LOG = Logger.getLogger(WorkItemLifecycleCloudEventConsumer.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject PlanItemCompletionApplier planItemApplier;
  @Inject GateCompletionApplier gateApplier;

  public void onLifecycleCloudEvent(@ObservesAsync CloudEvent ce) {
    String type = ce.getType();
    if (!type.startsWith(WorkCloudEventTypes.PREFIX)) {
      return;
    }

    if (isNonLifecycleType(type)) {
      return;
    }

    String tenancyId = (String) ce.getExtension(WorkCloudEventTypes.EXT_TENANCY_ID);
    if (tenancyId == null) {
      LOG.errorf("Missing tenancyid extension on CloudEvent id=%s type=%s", ce.getId(), type);
      return;
    }

    if (ce.getData() == null) {
      LOG.errorf("Null data on lifecycle CloudEvent id=%s type=%s", ce.getId(), type);
      return;
    }

    JsonNode data;
    try {
      data = MAPPER.readTree(ce.getData().toBytes());
    } catch (Exception e) {
      LOG.errorf(e, "Failed to parse CloudEvent data id=%s", ce.getId());
      return;
    }

    String callerRefStr = data.has("callerRef") ? data.get("callerRef").asText() : null;
    CallerRefParser.CallerRef ref = CallerRefParser.parse(callerRefStr);
    if (ref == null) {
      LOG.warnf("Invalid or missing callerRef in CloudEvent id=%s: %s", ce.getId(), callerRefStr);
      return;
    }

    String resolution = data.has("resolution") ? data.get("resolution").asText() : null;
    String resolutionTypeName =
        data.has("resolutionTypeName") ? data.get("resolutionTypeName").asText() : null;
    String actorId = data.has("actorId") ? data.get("actorId").asText() : null;

    switch (ref) {
      case CallerRefParser.PlanItemRef pi ->
          handlePlanItem(pi, type, resolution, resolutionTypeName);
      case CallerRefParser.GateRef gate -> handleGate(gate, tenancyId, type, resolution, actorId);
    }
  }

  private void handlePlanItem(
      CallerRefParser.PlanItemRef pi, String ceType, String resolution, String resolutionTypeName) {

    if (ceType.equals(WorkCloudEventTypes.SUSPENDED)) {
      planItemApplier.applySuspend(pi.caseId(), pi.planItemId());
      return;
    }
    if (ceType.equals(WorkCloudEventTypes.RESUMED)) {
      planItemApplier.applyResume(pi.caseId(), pi.planItemId());
      return;
    }

    TaskStatus status = mapToTaskStatus(ceType);
    if (status == null) {
      return;
    }

    planItemApplier.apply(pi.caseId(), pi.planItemId(), status, resolution, resolutionTypeName);
  }

  private void handleGate(
      CallerRefParser.GateRef gate,
      String tenancyId,
      String ceType,
      String resolution,
      String actorId) {

    TaskStatus status = mapToTaskStatus(ceType);
    if (status == null) {
      return;
    }

    gateApplier.apply(gate.caseId(), tenancyId, gate.gateId(), status, resolution, actorId);
  }

  private static TaskStatus mapToTaskStatus(String ceType) {
    return switch (ceType) {
      case WorkCloudEventTypes.COMPLETED -> TaskStatus.COMPLETED;
      case WorkCloudEventTypes.REJECTED -> TaskStatus.REJECTED;
      case WorkCloudEventTypes.FAULTED -> TaskStatus.FAULTED;
      case WorkCloudEventTypes.EXPIRED -> TaskStatus.FAULTED;
      case WorkCloudEventTypes.ESCALATED -> TaskStatus.FAULTED;
      case WorkCloudEventTypes.OBSOLETE -> TaskStatus.OBSOLETE;
      case WorkCloudEventTypes.CANCELLED -> TaskStatus.CANCELLED;
      default -> null;
    };
  }

  private static boolean isNonLifecycleType(String type) {
    return type.equals(WorkCloudEventTypes.CREATE)
        || type.equals(WorkCloudEventTypes.REQUESTED)
        || type.equals(WorkCloudEventTypes.CREATED)
        || type.equals(WorkCloudEventTypes.ASSIGNED)
        || type.equals(WorkCloudEventTypes.STARTED)
        || type.equals(WorkCloudEventTypes.DELEGATED)
        || type.equals(WorkCloudEventTypes.RELEASED)
        || type.equals(WorkCloudEventTypes.SPAWNED);
  }
}
