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
package io.casehub.engine.internal.improvement.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImprovementIntegrationWorkerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private RecordingEventLogRepository eventLogRepo;
  private ImprovementIntegrationWorker worker;
  private UUID caseId;
  private String tenancyId;

  @BeforeEach
  void setUp() {
    eventLogRepo = new RecordingEventLogRepository();
    worker = new ImprovementIntegrationWorker(eventLogRepo);
    caseId = UUID.randomUUID();
    tenancyId = "tenant-1";
  }

  @Test
  void blocksIntegrationWithoutReview() {
    assertThatThrownBy(() -> worker.verifyReviewGate(caseId, tenancyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no passing review record found");
  }

  @Test
  void allowsIntegrationWithPassingReview() {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("capability", "code-review");
    payload.put("verdict", "approved");

    EventLog reviewEvent = new EventLog();
    reviewEvent.setCaseId(caseId);
    reviewEvent.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    reviewEvent.setPayload(payload);
    eventLogRepo.append(reviewEvent, tenancyId);

    worker.verifyReviewGate(caseId, tenancyId);
  }

  @Test
  void blocksIntegrationWithRejectedReview() {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("capability", "code-review");
    payload.put("verdict", "rejected");

    EventLog reviewEvent = new EventLog();
    reviewEvent.setCaseId(caseId);
    reviewEvent.setEventType(CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    reviewEvent.setPayload(payload);
    eventLogRepo.append(reviewEvent, tenancyId);

    assertThatThrownBy(() -> worker.verifyReviewGate(caseId, tenancyId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no passing review record found");
  }

  static class RecordingEventLogRepository implements EventLogRepository {
    final List<EventLog> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(EventLog eventLog, String tenancyId) {
      eventLog.tenancyId = tenancyId;
      entries.add(eventLog);
    }

    @Override
    public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
      append(eventLog, tenancyId);
      return 1L;
    }

    @Override
    public EventLog findById(Long id, String tenancyId) {
      return null;
    }

    @Override
    public List<EventLog> findSchedulingEvents(
        UUID caseId, String workerId, Instant after, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseAndTypes(
        UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
      return entries.stream()
          .filter(
              e ->
                  e.getCaseId().equals(caseId)
                      && types.contains(e.getEventType())
                      && tenancyId.equals(e.tenancyId))
          .toList();
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByWorkerAndType(
        String workerId, CaseHubEventType type, String tenancyId) {
      return List.of();
    }

    @Override
    public List<EventLog> findByCaseWithFilters(
        UUID caseId,
        Collection<CaseHubEventType> eventTypes,
        Collection<EventStreamType> streamTypes,
        String tenancyId) {
      return List.of();
    }
  }
}
