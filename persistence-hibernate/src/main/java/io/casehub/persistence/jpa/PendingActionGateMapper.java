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
package io.casehub.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.internal.model.PendingActionGate;
import io.casehub.worker.api.PlannedAction;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Serializes/deserializes {@link PendingActionGate} to/from JSON for the {@code
 * pending_action_gate} jsonb column. Handles {@code Class<?>} by storing as FQCN string.
 */
final class PendingActionGateMapper {

  private static final Logger LOG = Logger.getLogger(PendingActionGateMapper.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private PendingActionGateMapper() {}

  static String toJson(PendingActionGate gate) {
    if (gate == null) return null;
    try {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("gateId", gate.gateId());
      node.put("workerId", gate.workerId());
      node.put("idempotency", gate.idempotency());
      node.set("deferredOutput", MAPPER.valueToTree(gate.deferredOutput()));
      node.set("plannedAction", MAPPER.valueToTree(gate.plannedAction()));
      if (gate.bindingName() != null) node.put("bindingName", gate.bindingName());
      if (gate.capabilityName() != null) node.put("capabilityName", gate.capabilityName());
      if (gate.resolutionType() != null)
        node.put("resolutionType", gate.resolutionType().getName());
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      LOG.errorf(e, "Failed to serialize PendingActionGate for worker=%s", gate.workerId());
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  static PendingActionGate fromJson(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      ObjectNode node = (ObjectNode) MAPPER.readTree(json);
      long gateId = node.get("gateId").asLong();
      String workerId = node.get("workerId").asText();
      String idempotency = node.get("idempotency").asText();
      Map<String, Object> deferredOutput =
          MAPPER.convertValue(node.get("deferredOutput"), Map.class);
      PlannedAction plannedAction =
          MAPPER.convertValue(node.get("plannedAction"), PlannedAction.class);
      String bindingName = node.has("bindingName") ? node.get("bindingName").asText() : null;
      String capabilityName =
          node.has("capabilityName") ? node.get("capabilityName").asText() : null;
      Class<?> resolutionType = null;
      if (node.has("resolutionType") && !node.get("resolutionType").isNull()) {
        try {
          resolutionType = Class.forName(node.get("resolutionType").asText());
        } catch (ClassNotFoundException e) {
          LOG.warnf(
              "Resolution type class not found: %s — gate will load without typed resolution",
              node.get("resolutionType").asText());
        }
      }
      return new PendingActionGate(
          gateId,
          workerId,
          idempotency,
          deferredOutput,
          plannedAction,
          bindingName,
          capabilityName,
          resolutionType);
    } catch (Exception e) {
      LOG.errorf(e, "Failed to deserialize PendingActionGate from JSON");
      return null;
    }
  }
}
