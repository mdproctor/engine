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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.stigmergy.TickTrace;
import io.casehub.api.model.stigmergy.TickTrace.GateResult;
import io.casehub.api.model.stigmergy.TickTrace.GateResult.GateVerdict;
import io.casehub.api.model.stigmergy.TickTrace.TickTrigger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TickTraceBufferTest {

  private TickTraceBuffer buffer;

  @BeforeEach
  void setUp() {
    buffer = new TickTraceBuffer();
  }

  @Test
  void emptyBufferReturnsEmptyList() {
    assertThat(buffer.recent(UUID.randomUUID(), 10)).isEmpty();
  }

  @Test
  void recordAndRetrieveTraces() {
    var caseId = UUID.randomUUID();
    var trace1 = makeTrace(caseId, Instant.now().minusSeconds(60));
    var trace2 = makeTrace(caseId, Instant.now());

    buffer.record(trace1);
    buffer.record(trace2);

    var recent = buffer.recent(caseId, 10);
    assertThat(recent).hasSize(2);
    assertThat(recent.get(0).timestamp()).isAfterOrEqualTo(recent.get(1).timestamp());
  }

  @Test
  void respectsLimitParameter() {
    var caseId = UUID.randomUUID();
    for (int i = 0; i < 10; i++) {
      buffer.record(makeTrace(caseId, Instant.now().plusSeconds(i)));
    }
    assertThat(buffer.recent(caseId, 3)).hasSize(3);
  }

  @Test
  void perCaseIsolation() {
    var case1 = UUID.randomUUID();
    var case2 = UUID.randomUUID();
    buffer.record(makeTrace(case1, Instant.now()));
    buffer.record(makeTrace(case2, Instant.now()));

    assertThat(buffer.recent(case1, 10)).hasSize(1);
    assertThat(buffer.recent(case2, 10)).hasSize(1);
  }

  @Test
  void evictsOldestWhenCapacityReached() {
    var caseId = UUID.randomUUID();
    for (int i = 0; i < 150; i++) {
      buffer.record(makeTrace(caseId, Instant.now().plusSeconds(i)));
    }
    assertThat(buffer.recent(caseId, 200)).hasSize(100);
  }

  @Test
  void resetClearsAllBuffers() {
    var caseId = UUID.randomUUID();
    buffer.record(makeTrace(caseId, Instant.now()));
    assertThat(buffer.recent(caseId, 10)).hasSize(1);

    buffer.reset();
    assertThat(buffer.recent(caseId, 10)).isEmpty();
  }

  private TickTrace makeTrace(UUID caseId, Instant timestamp) {
    return new TickTrace(
        caseId,
        timestamp,
        TickTrigger.EVENT_DRIVEN,
        List.of(new GateResult("evolution_enabled", GateVerdict.PASSED, null)),
        null);
  }
}
