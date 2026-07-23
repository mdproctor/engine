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
package io.casehub.engine.internal.engine;

import io.casehub.api.spi.CaseEventRecorder;
import io.casehub.api.spi.CaseEventRequest;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Blocking {@link CaseEventRecorder}. Constructs {@link EventLog} domain objects internally —
 * consumers never import {@code EventLog}.
 */
@ApplicationScoped
public class DefaultCaseEventRecorder implements CaseEventRecorder {

  private final EventLogRepository eventLogRepository;

  @Inject
  public DefaultCaseEventRecorder(EventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public void record(CaseEventRequest request) {
    eventLogRepository.append(toEventLog(request), request.tenancyId());
  }

  @Override
  public Long recordAndReturnId(CaseEventRequest request) {
    return eventLogRepository.appendAndReturnId(toEventLog(request), request.tenancyId());
  }

  private EventLog toEventLog(CaseEventRequest request) {
    EventLog log = new EventLog();
    log.setCaseId(request.caseId());
    log.setEventType(request.type());
    log.setStreamType(request.stream());
    log.setWorkerId(request.workerId());
    log.setTimestamp(Instant.now());
    log.setPayload(request.payload());
    log.setMetadata(request.metadata());
    return log;
  }
}
