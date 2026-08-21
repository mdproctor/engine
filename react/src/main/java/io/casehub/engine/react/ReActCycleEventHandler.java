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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class ReActCycleEventHandler {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject EventLogRepository eventLogRepository;

  @ConsumeEvent(EventBusAddresses.REACT_CYCLE)
  @RunOnVirtualThread
  public void onReactCycle(JsonObject message) {
    try {
      JsonNode root = MAPPER.readTree(message.encode());

      var eventLog = new EventLog();
      eventLog.setCaseId(UUID.fromString(root.get("caseId").asText()));
      eventLog.setEventType(CaseHubEventType.REACT_CYCLE);
      eventLog.setWorkerId(root.get("workerName").asText());

      ObjectNode meta = MAPPER.createObjectNode();
      meta.put("cycleIndex", root.get("cycleIndex").asInt());
      meta.put(
          "reasoningText", root.has("reasoningText") ? root.get("reasoningText").asText() : "");
      meta.set("toolCalls", root.get("toolCalls"));

      if (root.has("tokenUsage") && !root.get("tokenUsage").isNull()) {
        meta.set("tokenUsage", root.get("tokenUsage"));
      }

      eventLog.setMetadata(meta);

      String tenancyId =
          root.has("tenancyId") && !root.get("tenancyId").isNull()
              ? root.get("tenancyId").asText()
              : null;
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      System.getLogger(ReActCycleEventHandler.class.getName())
          .log(System.Logger.Level.ERROR, "Failed to process REACT_CYCLE event", e);
    }
  }
}
