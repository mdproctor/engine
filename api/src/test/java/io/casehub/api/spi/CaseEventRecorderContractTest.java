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
package io.casehub.api.spi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.NullNode;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseEventRecorderContractTest {

  @Test
  void caseEventRequest_exposesAllFields() {
    final UUID caseId = UUID.randomUUID();
    final var request =
        new CaseEventRequest(
            caseId,
            CaseHubEventType.AGENT_ROUTED,
            EventStreamType.ORCHESTRATION,
            "worker-1",
            "tenant-1",
            NullNode.instance,
            NullNode.instance);

    assertEquals(caseId, request.caseId());
    assertEquals(CaseHubEventType.AGENT_ROUTED, request.type());
    assertEquals(EventStreamType.ORCHESTRATION, request.stream());
    assertEquals("worker-1", request.workerId());
    assertEquals("tenant-1", request.tenancyId());
  }

  @Test
  void noOpRecorder_recordDoesNotThrow() {
    final var recorder = new NoOpCaseEventRecorder();
    final var request =
        new CaseEventRequest(
            UUID.randomUUID(),
            CaseHubEventType.AGENT_ROUTED,
            EventStreamType.ORCHESTRATION,
            "w",
            "t",
            NullNode.instance,
            NullNode.instance);
    assertDoesNotThrow(() -> recorder.record(request));
    assertEquals(0L, recorder.recordAndReturnId(request));
  }

  @Test
  void noOpReactiveRecorder_recordDoesNotThrow() {
    final var recorder = new NoOpCaseEventRecorder();
    final var request =
        new CaseEventRequest(
            UUID.randomUUID(),
            CaseHubEventType.AGENT_ROUTED,
            EventStreamType.ORCHESTRATION,
            "w",
            "t",
            NullNode.instance,
            NullNode.instance);
    assertDoesNotThrow(() -> recorder.record(request));
    assertEquals(0L, recorder.recordAndReturnId(request));
  }
}
