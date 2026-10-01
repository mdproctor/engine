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
package io.casehub.engine.internal.improvement.area;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment.LandscapePosition;
import io.casehub.engine.common.internal.history.EventLog;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafetyCapabilityAreaTest {

  private TestEventLogRepository eventLog;
  private SafetyCapabilityArea area;
  private UUID caseId;
  private static final String TENANCY = "test-tenant";

  @BeforeEach
  void setUp() {
    eventLog = new TestEventLogRepository();
    area = new SafetyCapabilityArea(eventLog);
    caseId = UUID.randomUUID();
  }

  @Test
  void noViolations_returnsScore1() {
    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isEqualTo(1.0);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.AHEAD);
  }

  @Test
  void oneViolation_returnsScore05() {
    appendEvent(CaseHubEventType.BUDGET_EXHAUSTED);

    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isCloseTo(0.5, within(0.01));
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.AT_PARITY);
  }

  @Test
  void fourViolations_returnsScore02() {
    appendEvent(CaseHubEventType.BUDGET_EXHAUSTED);
    appendEvent(CaseHubEventType.CIRCUIT_BREAKER_TRIPPED);
    appendEvent(CaseHubEventType.ACTION_GATE_REJECTED);
    appendEvent(CaseHubEventType.BUDGET_EXHAUSTED);

    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isCloseTo(0.2, within(0.01));
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.BEHIND);
  }

  @Test
  void idIsSafety() {
    assertThat(area.id()).isEqualTo("safety");
  }

  private void appendEvent(CaseHubEventType type) {
    var event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(type);
    event.setTimestamp(Instant.now());
    eventLog.append(event, TENANCY);
  }
}
