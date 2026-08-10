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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.plan.execution.AgentResultRecord;
import io.casehub.engine.plan.execution.PatternExecutionCheckpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatternCheckpointStoreTest {

  private final List<EventLog> stored = new ArrayList<>();
  private PatternCheckpointStore store;

  @BeforeEach
  void setUp() {
    stored.clear();
    EventLogRepository repo = new StubEventLogRepository(stored);
    store = new PatternCheckpointStore(repo, new ObjectMapper());
  }

  @Test
  void saveWritesEventLogEntry() {
    var checkpoint =
        new PatternExecutionCheckpoint(
            UUID.randomUUID(),
            "worker-1",
            2,
            List.of(AgentResultRecord.of("a1", Map.of("r", "ok"), 100L, "SUCCESS")),
            Set.of(),
            null,
            0,
            Map.of());
    store.save(checkpoint, "tenant-1");
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getEventType()).isEqualTo(CaseHubEventType.PATTERN_CHECKPOINT);
    assertThat(stored.get(0).getWorkerId()).isEqualTo("worker-1");
  }

  @Test
  void findLatestReturnsDeserializedCheckpoint() {
    var caseId = UUID.randomUUID();
    var checkpoint =
        new PatternExecutionCheckpoint(
            caseId,
            "worker-1",
            3,
            List.of(AgentResultRecord.of("a1", Map.of("x", 1), 200L, "SUCCESS")),
            Set.of("excluded"),
            null,
            0,
            Map.of("activationCounts", Map.of("a1", 2)));
    store.save(checkpoint, "tenant-1");

    var found = store.findLatest(caseId, "worker-1", "tenant-1");
    assertThat(found).isPresent();
    assertThat(found.get().completedIterations()).isEqualTo(3);
    assertThat(found.get().results()).hasSize(1);
    assertThat(found.get().excludedAgents()).containsExactly("excluded");
  }

  @Test
  void findLatestReturnsEmptyWhenNoCheckpoints() {
    var found = store.findLatest(UUID.randomUUID(), "worker-1", "tenant-1");
    assertThat(found).isEmpty();
  }

  @Test
  void findLatestReturnsNewestBySequence() {
    var caseId = UUID.randomUUID();
    store.save(
        new PatternExecutionCheckpoint(
            caseId, "worker-1", 1, List.of(), Set.of(), null, 0, Map.of()),
        "t1");
    store.save(
        new PatternExecutionCheckpoint(
            caseId, "worker-1", 5, List.of(), Set.of(), null, 0, Map.of()),
        "t1");
    store.save(
        new PatternExecutionCheckpoint(
            caseId, "worker-1", 3, List.of(), Set.of(), null, 0, Map.of()),
        "t1");

    var found = store.findLatest(caseId, "worker-1", "t1");
    assertThat(found).isPresent();
    assertThat(found.get().completedIterations()).isEqualTo(3);
  }

  static class StubEventLogRepository implements EventLogRepository {

    private final List<EventLog> store;
    private final AtomicLong seq = new AtomicLong(0);

    StubEventLogRepository(List<EventLog> store) {
      this.store = store;
    }

    @Override
    public void append(EventLog eventLog, String tenancyId) {
      eventLog.setSeq(seq.incrementAndGet());
      eventLog.tenancyId = tenancyId;
      if (eventLog.getTimestamp() == null) {
        eventLog.setTimestamp(Instant.now());
      }
      store.add(eventLog);
    }

    @Override
    public Long appendAndReturnId(EventLog eventLog, String tenancyId) {
      append(eventLog, tenancyId);
      return eventLog.getSeq();
    }

    @Override
    public EventLog findById(Long id, String tenancyId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<EventLog> findSchedulingEvents(
        UUID caseId, String workerId, Instant after, String tenancyId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<EventLog> findByCaseAndTypes(
        UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<EventLog> findByCaseAndWorkerAndType(
        UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
      return store.stream()
          .filter(
              e ->
                  e.getCaseId().equals(caseId)
                      && e.getWorkerId().equals(workerId)
                      && e.getEventType() == type
                      && tenancyId.equals(e.tenancyId))
          .toList();
    }

    @Override
    public List<EventLog> findByWorkerAndType(
        String workerId, CaseHubEventType type, String tenancyId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<EventLog> findByCaseWithFilters(
        UUID caseId,
        Collection<CaseHubEventType> eventTypes,
        Collection<EventStreamType> streamTypes,
        String tenancyId) {
      throw new UnsupportedOperationException();
    }
  }
}
