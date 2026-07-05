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
package io.casehub.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryReactiveEventLogRepositoryTest {

  InMemoryReactiveEventLogRepository repository;

  @BeforeEach
  void setUp() {
    InMemoryEventLogRepository blockingRepo = new InMemoryEventLogRepository();
    repository = new InMemoryReactiveEventLogRepository();
    repository.setDelegate(blockingRepo);
  }

  // --- Happy path ---

  @Test
  void append_populatesIdAndSeq() {
    EventLog log = event(UUID.randomUUID(), "worker-1", CaseHubEventType.WORKER_SCHEDULED);

    repository.append(log, "test-tenant").await().indefinitely();

    assertThat(log.id).isNotNull().isPositive();
    assertThat(log.getSeq()).isNotNull().isPositive();
  }

  @Test
  void append_seqIsMonotonicallyIncreasing() {
    UUID caseId = UUID.randomUUID();
    EventLog first = event(caseId, "w-1", CaseHubEventType.WORKER_SCHEDULED);
    EventLog second = event(caseId, "w-2", CaseHubEventType.WORKER_SCHEDULED);

    repository.append(first, "test-tenant").await().indefinitely();
    repository.append(second, "test-tenant").await().indefinitely();

    assertThat(second.getSeq()).isGreaterThan(first.getSeq());
  }

  @Test
  void appendAndReturnId_returnsIdAndPopulatesLog() {
    EventLog log = event(UUID.randomUUID(), "worker-ret", CaseHubEventType.WORKER_SCHEDULED);

    Long returned = repository.appendAndReturnId(log, "test-tenant").await().indefinitely();

    assertThat(returned).isNotNull().isPositive();
    assertThat(returned).isEqualTo(log.id);
    assertThat(log.getSeq()).isNotNull().isPositive();
  }

  @Test
  void findById_returnsAppendedEvent() {
    UUID caseId = UUID.randomUUID();
    EventLog log = event(caseId, "worker-find", CaseHubEventType.WORKER_SCHEDULED);
    repository.append(log, "test-tenant").await().indefinitely();

    EventLog found = repository.findById(log.id, "test-tenant").await().indefinitely();

    assertThat(found).isNotNull();
    assertThat(found.getCaseId()).isEqualTo(caseId);
    assertThat(found.getEventType()).isEqualTo(CaseHubEventType.WORKER_SCHEDULED);
    assertThat(found.getWorkerId()).isEqualTo("worker-find");
  }

  @Test
  void findSchedulingEvents_returnsScheduledStartedAndCompleted() {
    UUID caseId = UUID.randomUUID();
    String workerId = "worker-sched-" + UUID.randomUUID();

    EventLog scheduled = event(caseId, workerId, CaseHubEventType.WORKER_SCHEDULED);
    EventLog started = event(caseId, workerId, CaseHubEventType.WORKER_EXECUTION_STARTED);
    EventLog otherWorker = event(caseId, "other", CaseHubEventType.WORKER_SCHEDULED);
    EventLog otherCase = event(UUID.randomUUID(), workerId, CaseHubEventType.WORKER_SCHEDULED);

    repository.append(scheduled, "test-tenant").await().indefinitely();
    repository.append(started, "test-tenant").await().indefinitely();
    repository.append(otherWorker, "test-tenant").await().indefinitely();
    repository.append(otherCase, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findSchedulingEvents(caseId, workerId, null, "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(EventLog::getEventType)
        .containsExactlyInAnyOrder(
            CaseHubEventType.WORKER_SCHEDULED, CaseHubEventType.WORKER_EXECUTION_STARTED);
  }

  @Test
  void findByTypes_returnsMatchingEventsOrderedBySeq() {
    UUID caseId = UUID.randomUUID();
    EventLog e1 = event(caseId, "w", CaseHubEventType.CASE_STARTED);
    EventLog e2 = event(caseId, "w", CaseHubEventType.WORKER_EXECUTION_COMPLETED);
    EventLog noise = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);

    repository.append(e1, "test-tenant").await().indefinitely();
    repository.append(e2, "test-tenant").await().indefinitely();
    repository.append(noise, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByTypes(
                List.of(CaseHubEventType.CASE_STARTED, CaseHubEventType.WORKER_EXECUTION_COMPLETED))
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
    assertThat(result.stream().map(EventLog::getSeq).toList()).isSorted();
    assertThat(result.stream().map(EventLog::getEventType).toList())
        .doesNotContain(CaseHubEventType.WORKER_SCHEDULED);
  }

  @Test
  void findByCaseAndTypes_filtersByCaseId() {
    UUID target = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    repository
        .append(event(target, "w", CaseHubEventType.CASE_STARTED), "test-tenant")
        .await()
        .indefinitely();
    repository
        .append(event(other, "w", CaseHubEventType.CASE_STARTED), "test-tenant")
        .await()
        .indefinitely();

    List<EventLog> result =
        repository
            .findByCaseAndTypes(target, List.of(CaseHubEventType.CASE_STARTED), "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getCaseId()).isEqualTo(target);
    assertThat(result.stream().map(EventLog::getSeq).toList()).isSorted();
  }

  @Test
  void findByCaseAndWorkerAndType_filtersAllThreeDimensions() {
    UUID caseId = UUID.randomUUID();
    String workerId = "worker-filter-" + UUID.randomUUID();

    EventLog match = event(caseId, workerId, CaseHubEventType.WORKER_EXECUTION_FAILED);
    EventLog wrongWorker = event(caseId, "other", CaseHubEventType.WORKER_EXECUTION_FAILED);
    EventLog wrongType = event(caseId, workerId, CaseHubEventType.WORKER_SCHEDULED);

    repository.append(match, "test-tenant").await().indefinitely();
    repository.append(wrongWorker, "test-tenant").await().indefinitely();
    repository.append(wrongType, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseAndWorkerAndType(
                caseId, workerId, CaseHubEventType.WORKER_EXECUTION_FAILED, "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getWorkerId()).isEqualTo(workerId);
    assertThat(result.get(0).getEventType()).isEqualTo(CaseHubEventType.WORKER_EXECUTION_FAILED);
  }

  // --- Edge cases ---

  @Test
  void findById_returnsNullForUnknownId() {
    EventLog result = repository.findById(Long.MAX_VALUE, "test-tenant").await().indefinitely();
    assertThat(result).isNull();
  }

  @Test
  void findSchedulingEvents_returnsEmptyWhenNoneMatch() {
    List<EventLog> result =
        repository
            .findSchedulingEvents(UUID.randomUUID(), "ghost", null, "test-tenant")
            .await()
            .indefinitely();
    assertThat(result).isEmpty();
  }

  @Test
  void findByTypes_returnsEmptyWhenNoneMatch() {
    List<EventLog> result =
        repository.findByTypes(List.of(CaseHubEventType.CASE_CANCELLED)).await().indefinitely();
    assertThat(result).isEmpty();
  }

  @Test
  void idsAreUnique() {
    EventLog a = event(UUID.randomUUID(), "w", CaseHubEventType.WORKER_SCHEDULED);
    EventLog b = event(UUID.randomUUID(), "w", CaseHubEventType.WORKER_SCHEDULED);

    repository.append(a, "test-tenant").await().indefinitely();
    repository.append(b, "test-tenant").await().indefinitely();

    assertThat(a.id).isNotEqualTo(b.id);
    assertThat(a.getSeq()).isNotEqualTo(b.getSeq());
  }

  @Test
  void findSchedulingEvents_withAfterCutoff_excludesOlderEvents() {
    UUID caseId = UUID.randomUUID();
    Instant cutoff = Instant.now().minusSeconds(60);

    // old event — before cutoff
    EventLog old = new EventLog();
    old.setCaseId(caseId);
    old.setWorkerId("w1");
    old.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    old.setStreamType(EventStreamType.CASE);
    old.setTimestamp(Instant.now().minusSeconds(120));
    old.setMetadata(new ObjectMapper().createObjectNode().put("inputDataHash", "h-old"));
    repository.append(old, "test-tenant").await().indefinitely();

    // recent event — after cutoff
    EventLog recent = new EventLog();
    recent.setCaseId(caseId);
    recent.setWorkerId("w1");
    recent.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    recent.setStreamType(EventStreamType.CASE);
    recent.setTimestamp(Instant.now());
    recent.setMetadata(new ObjectMapper().createObjectNode().put("inputDataHash", "h-recent"));
    repository.append(recent, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository.findSchedulingEvents(caseId, "w1", cutoff, "test-tenant").await().indefinitely();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getTimestamp()).isAfter(cutoff);
  }

  @Test
  void findSchedulingEvents_withNullAfter_returnsAll() {
    UUID caseId = UUID.randomUUID();

    EventLog e1 = new EventLog();
    e1.setCaseId(caseId);
    e1.setWorkerId("w2");
    e1.setEventType(CaseHubEventType.WORKER_SCHEDULED);
    e1.setStreamType(EventStreamType.CASE);
    e1.setTimestamp(Instant.now().minusSeconds(120));
    e1.setMetadata(new ObjectMapper().createObjectNode().put("inputDataHash", "h1"));
    repository.append(e1, "test-tenant").await().indefinitely();

    EventLog e2 = new EventLog();
    e2.setCaseId(caseId);
    e2.setWorkerId("w2");
    e2.setEventType(CaseHubEventType.WORKER_EXECUTION_STARTED);
    e2.setStreamType(EventStreamType.CASE);
    e2.setTimestamp(Instant.now());
    e2.setMetadata(new ObjectMapper().createObjectNode().put("inputDataHash", "h1"));
    repository.append(e2, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository.findSchedulingEvents(caseId, "w2", null, "test-tenant").await().indefinitely();

    assertThat(result).hasSize(2);
  }

  @Test
  void findByCaseWithFilters_noFilters_returnsAllCaseEvents() {
    UUID targetCase = UUID.randomUUID();
    UUID otherCase = UUID.randomUUID();

    EventLog e1 = event(targetCase, "w1", CaseHubEventType.CASE_STARTED);
    e1.setStreamType(EventStreamType.CASE);
    EventLog e2 = event(targetCase, "w1", CaseHubEventType.WORKER_SCHEDULED);
    e2.setStreamType(EventStreamType.WORKER);
    EventLog e3 = event(targetCase, "w1", CaseHubEventType.MILESTONE_REACHED);
    e3.setStreamType(EventStreamType.SYSTEM);
    EventLog other = event(otherCase, "w1", CaseHubEventType.CASE_STARTED);

    repository.append(e1, "test-tenant").await().indefinitely();
    repository.append(e2, "test-tenant").await().indefinitely();
    repository.append(e3, "test-tenant").await().indefinitely();
    repository.append(other, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseWithFilters(targetCase, null, null, "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(3);
    assertThat(result).allMatch(e -> targetCase.equals(e.getCaseId()));
    assertThat(result.stream().map(EventLog::getSeq).toList()).isSorted();
  }

  @Test
  void findByCaseWithFilters_eventTypeFilter_returnsMatchingEvents() {
    UUID caseId = UUID.randomUUID();

    EventLog e1 = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);
    EventLog e2 = event(caseId, "w", CaseHubEventType.WORKER_EXECUTION_STARTED);
    EventLog e3 = event(caseId, "w", CaseHubEventType.CASE_STARTED);

    repository.append(e1, "test-tenant").await().indefinitely();
    repository.append(e2, "test-tenant").await().indefinitely();
    repository.append(e3, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseWithFilters(
                caseId,
                List.of(
                    CaseHubEventType.WORKER_SCHEDULED, CaseHubEventType.WORKER_EXECUTION_STARTED),
                null,
                "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(EventLog::getEventType)
        .containsExactlyInAnyOrder(
            CaseHubEventType.WORKER_SCHEDULED, CaseHubEventType.WORKER_EXECUTION_STARTED);
    assertThat(result.stream().map(EventLog::getSeq).toList()).isSorted();
  }

  @Test
  void findByCaseWithFilters_streamTypeFilter_returnsMatchingEvents() {
    UUID caseId = UUID.randomUUID();

    EventLog e1 = event(caseId, "w", CaseHubEventType.CASE_STARTED);
    e1.setStreamType(EventStreamType.CASE);
    EventLog e2 = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);
    e2.setStreamType(EventStreamType.WORKER);
    EventLog e3 = event(caseId, "w", CaseHubEventType.WORKER_EXECUTION_STARTED);
    e3.setStreamType(EventStreamType.WORKER);

    repository.append(e1, "test-tenant").await().indefinitely();
    repository.append(e2, "test-tenant").await().indefinitely();
    repository.append(e3, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseWithFilters(caseId, null, List.of(EventStreamType.WORKER), "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
    assertThat(result).allMatch(e -> EventStreamType.WORKER.equals(e.getStreamType()));
    assertThat(result.stream().map(EventLog::getSeq).toList()).isSorted();
  }

  @Test
  void findByCaseWithFilters_combinedFilters_returnsMatchingEvents() {
    UUID caseId = UUID.randomUUID();

    EventLog match = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);
    match.setStreamType(EventStreamType.WORKER);

    EventLog wrongType = event(caseId, "w", CaseHubEventType.CASE_STARTED);
    wrongType.setStreamType(EventStreamType.WORKER);

    EventLog wrongStream = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);
    wrongStream.setStreamType(EventStreamType.CASE);

    repository.append(match, "test-tenant").await().indefinitely();
    repository.append(wrongType, "test-tenant").await().indefinitely();
    repository.append(wrongStream, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseWithFilters(
                caseId,
                List.of(CaseHubEventType.WORKER_SCHEDULED),
                List.of(EventStreamType.WORKER),
                "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getEventType()).isEqualTo(CaseHubEventType.WORKER_SCHEDULED);
    assertThat(result.get(0).getStreamType()).isEqualTo(EventStreamType.WORKER);
  }

  @Test
  void findByCaseWithFilters_emptyFilters_behavesLikeNull() {
    UUID caseId = UUID.randomUUID();

    EventLog e1 = event(caseId, "w", CaseHubEventType.CASE_STARTED);
    EventLog e2 = event(caseId, "w", CaseHubEventType.WORKER_SCHEDULED);

    repository.append(e1, "test-tenant").await().indefinitely();
    repository.append(e2, "test-tenant").await().indefinitely();

    List<EventLog> result =
        repository
            .findByCaseWithFilters(caseId, List.of(), List.of(), "test-tenant")
            .await()
            .indefinitely();

    assertThat(result).hasSize(2);
  }

  // --- Helper ---

  private EventLog event(UUID caseId, String workerId, CaseHubEventType type) {
    EventLog log = new EventLog();
    log.setCaseId(caseId);
    log.setWorkerId(workerId);
    log.setEventType(type);
    log.setStreamType(EventStreamType.WORKER);
    log.setTimestamp(Instant.now());
    return log;
  }
}
