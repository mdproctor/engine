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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.JudgmentExpiredEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import java.time.Instant;
import org.jboss.logging.Logger;

public class JudgmentExpiredHandler {

  private static final Logger LOG = Logger.getLogger(JudgmentExpiredHandler.class);

  private final CaseInstanceCache caseInstanceCache;
  private final EventLogRepository eventLogRepository;
  private final EventDispatcher eventDispatcher;

  public JudgmentExpiredHandler(
      CaseInstanceCache caseInstanceCache,
      EventLogRepository eventLogRepository,
      EventDispatcher eventDispatcher) {
    this.caseInstanceCache = caseInstanceCache;
    this.eventLogRepository = eventLogRepository;
    this.eventDispatcher = eventDispatcher;
  }

  public void handle(JudgmentExpiredEvent event) {
    final CaseInstance instance = caseInstanceCache.get(event.caseId());
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not in cache for judgment expiry: caseId=%s — discarding", event.caseId());
      return;
    }
    if (instance.getState().isTerminal()) {
      LOG.warnf(
          "Judgment expired on terminated case (state=%s): caseId=%s — discarding",
          instance.getState(), event.caseId());
      return;
    }

    final EventLog log = new EventLog();
    log.setCaseId(instance.getUuid());
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    log.setEventType(CaseHubEventType.JUDGMENT_ESCALATED);
    com.fasterxml.jackson.databind.node.ObjectNode metadata =
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    metadata.put("bindingName", event.bindingName());
    metadata.put("reason", "expired");
    log.setMetadata(metadata);
    eventLogRepository.append(log, instance.tenancyId);

    eventDispatcher.dispatch(
        new CaseContextChangedEvent(instance, instance.getCaseContext(), "working"));

    LOG.infof("Judgment expired: caseId=%s binding=%s", event.caseId(), event.bindingName());
  }
}
