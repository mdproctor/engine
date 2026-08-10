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
package io.casehub.engine.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PatternCheckpointStore {

  private static final System.Logger LOG = System.getLogger(PatternCheckpointStore.class.getName());

  private final EventLogRepository eventLogRepository;
  private final ObjectMapper objectMapper;

  @Inject
  public PatternCheckpointStore(EventLogRepository eventLogRepository, ObjectMapper objectMapper) {
    this.eventLogRepository = eventLogRepository;
    this.objectMapper = objectMapper;
  }

  public void save(PatternExecutionCheckpoint checkpoint, String tenancyId) {
    var eventLog = new EventLog();
    eventLog.setCaseId(checkpoint.caseId());
    eventLog.setEventType(CaseHubEventType.PATTERN_CHECKPOINT);
    eventLog.setWorkerId(checkpoint.patternId());
    eventLog.setTimestamp(Instant.now());
    eventLog.setPayload(objectMapper.valueToTree(checkpoint));
    eventLogRepository.append(eventLog, tenancyId);
  }

  public Optional<PatternExecutionCheckpoint> findLatest(
      UUID caseId, String patternId, String tenancyId) {
    var events =
        eventLogRepository.findByCaseAndWorkerAndType(
            caseId, patternId, CaseHubEventType.PATTERN_CHECKPOINT, tenancyId);
    if (events.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(deserialize(events.get(events.size() - 1)));
  }

  private PatternExecutionCheckpoint deserialize(EventLog eventLog) {
    try {
      return objectMapper.treeToValue(eventLog.getPayload(), PatternExecutionCheckpoint.class);
    } catch (Exception e) {
      LOG.log(System.Logger.Level.WARNING, "Failed to deserialize checkpoint", e);
      return null;
    }
  }
}
