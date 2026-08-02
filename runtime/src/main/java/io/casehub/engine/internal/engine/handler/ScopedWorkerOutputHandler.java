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
package io.casehub.engine.internal.engine.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.ScopedWorkerOutputEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ScopedWorkerOutputHandler {

  private static final Logger LOG = Logger.getLogger(ScopedWorkerOutputHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Inject ContextOutputApplier contextOutputApplier;
  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;

  @ConsumeEvent(value = EventBusAddresses.SCOPED_WORKER_OUTPUT)
  @RunOnVirtualThread
  public void onScopedWorkerOutput(ScopedWorkerOutputEvent event) {
    try {
      CaseInstance caseInstance = event.caseInstance();
      CaseStatus state = caseInstance.getState();

      if (state != CaseStatus.RUNNING && state != CaseStatus.WAITING) {
        LOG.debugf(
            "Ignoring scoped worker output for caseId=%s — case is %s",
            caseInstance.getUuid(), state);
        return;
      }

      JsonNode diff = contextOutputApplier.apply(caseInstance, event.output(), event.bindingName());
      if (diff == null) {
        return;
      }

      EventLog eventLog =
          buildEventLog(
              caseInstance, event.workerName(), event.output(), event.bindingName(), diff);
      eventLogRepository.append(eventLog, caseInstance.tenancyId);

      eventBus.publish(
          EventBusAddresses.CONTEXT_CHANGED,
          new CaseContextChangedEvent(
              caseInstance, caseInstance.getCaseContext().snapshot(), ContextLayer.WORKING));
    } catch (Exception e) {
      LOG.errorf(
          e,
          "Failed to apply scoped worker output for caseId=%s worker=%s",
          event.caseInstance().getUuid(),
          event.workerName());
    }
  }

  private EventLog buildEventLog(
      CaseInstance caseInstance,
      String workerName,
      Map<String, Object> output,
      String bindingName,
      JsonNode contextDiff) {
    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setWorkerId(workerName);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setEventType(CaseHubEventType.SCOPED_WORKER_OUTPUT);
    eventLog.setPayload(OBJECT_MAPPER.valueToTree(output == null ? Map.of() : output));

    ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
    if (bindingName != null) {
      metadata.put("bindingName", bindingName);
    }
    if (contextDiff != null) {
      metadata.set("contextChanges", contextDiff);
      var keys = OBJECT_MAPPER.createArrayNode();
      contextDiff.fieldNames().forEachRemaining(keys::add);
      if (!keys.isEmpty()) {
        metadata.set("producedKeys", keys);
      }
    }
    eventLog.setMetadata(metadata);
    return eventLog;
  }
}
