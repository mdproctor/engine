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
package io.casehub.engine.internal.improvement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.ImprovementOutcome;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class ImprovementOutcomeRecorder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EventLogRepository eventLogRepository;

  @Inject
  public ImprovementOutcomeRecorder(EventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  public void record(UUID caseId, String tenancyId, ImprovementOutcome outcome) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("category", outcome.category());
    payload.put("target", outcome.target());
    payload.put("status", outcome.status().name());
    payload.put("prUrl", Objects.toString(outcome.prUrl(), ""));
    payload.put("ciDelta", String.valueOf(outcome.ciDelta()));
    payload.put("coverageDelta", String.valueOf(outcome.coverageDelta()));
    payload.put("lintDelta", String.valueOf(outcome.lintDelta()));
    payload.put("improvementCaseId", outcome.improvementCaseId().toString());

    EventLog eventLog = new EventLog();
    eventLog.setCaseId(caseId);
    eventLog.setEventType(CaseHubEventType.IMPROVEMENT_OUTCOME);
    eventLog.setPayload(payload);
    eventLogRepository.append(eventLog, tenancyId);
  }
}
