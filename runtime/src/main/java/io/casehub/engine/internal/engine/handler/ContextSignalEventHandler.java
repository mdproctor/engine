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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.ContextSignalEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ContextSignalEventHandler {

  private static final Logger LOG = Logger.getLogger(ContextSignalEventHandler.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject EventBus eventBus;

  @Inject EventLogRepository eventLogRepository;

  @ConsumeEvent(EventBusAddresses.CONTEXT_SIGNAL)
  @RunOnVirtualThread
  public void onContextSignal(ContextSignalEvent event) {
    var caseInstance = event.caseInstance();
    var bindingName = event.bindingName();
    var payload = event.payload();

    LOG.infof(
        "Applying context signal: case=%s, binding=%s, keys=%s",
        caseInstance.getUuid(), bindingName, payload.keySet());

    payload.forEach((key, value) -> caseInstance.getCaseContext().set(key, value));

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseInstance.getUuid());
    eventLog.setEventType(CaseHubEventType.CONTEXT_SIGNAL_APPLIED);
    eventLog.setStreamType(EventStreamType.CASE);
    eventLog.setTimestamp(Instant.now());
    eventLog.setMetadata(
        MAPPER
            .createObjectNode()
            .put("bindingName", bindingName)
            .set("signalKeys", MAPPER.valueToTree(payload.keySet())));
    eventLogRepository.append(eventLog, caseInstance.tenancyId);

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(caseInstance, caseInstance.getCaseContext(), null));
  }
}
