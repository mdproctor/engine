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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.api.spi.CaseEventRequest;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.persistence.memory.InMemoryEventLogRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultCaseEventRecorderTest {

  private DefaultCaseEventRecorder recorder;
  private InMemoryEventLogRepository repo;

  @BeforeEach
  void setUp() {
    repo = new InMemoryEventLogRepository();
    recorder = new DefaultCaseEventRecorder(repo);
  }

  @Test
  void record_delegatesToEventLogRepository() {
    UUID caseId = UUID.randomUUID();
    CaseEventRequest request =
        new CaseEventRequest(
            caseId,
            CaseHubEventType.AGENT_ROUTED,
            EventStreamType.ORCHESTRATION,
            "worker-1",
            "tenant-1",
            JsonNodeFactory.instance.objectNode().put("strategy", "trust-weighted"),
            NullNode.instance);

    recorder.record(request);

    List<EventLog> events =
        repo.findByCaseAndTypes(caseId, List.of(CaseHubEventType.AGENT_ROUTED), "tenant-1");
    assertEquals(1, events.size());
    EventLog recorded = events.get(0);
    assertEquals(caseId, recorded.getCaseId());
    assertEquals(CaseHubEventType.AGENT_ROUTED, recorded.getEventType());
    assertEquals(EventStreamType.ORCHESTRATION, recorded.getStreamType());
    assertEquals("worker-1", recorded.getWorkerId());
    assertEquals("strategy", recorded.getPayload().fieldNames().next());
  }

  @Test
  void recordAndReturnId_returnsId() {
    CaseEventRequest request =
        new CaseEventRequest(
            UUID.randomUUID(),
            CaseHubEventType.AGENT_DISPATCHED,
            EventStreamType.ORCHESTRATION,
            "w-2",
            "t-1",
            NullNode.instance,
            NullNode.instance);

    Long id = recorder.recordAndReturnId(request);

    assertNotNull(id);
    assertTrue(id > 0);
  }

  @Test
  void record_setsTimestamp() {
    CaseEventRequest request =
        new CaseEventRequest(
            UUID.randomUUID(),
            CaseHubEventType.AGENT_FAILED,
            EventStreamType.ORCHESTRATION,
            "w-5",
            "t-4",
            NullNode.instance,
            NullNode.instance);

    recorder.record(request);

    List<EventLog> events =
        repo.findByCaseAndTypes(request.caseId(), List.of(CaseHubEventType.AGENT_FAILED), "t-4");
    assertNotNull(events.get(0).getTimestamp());
  }
}
