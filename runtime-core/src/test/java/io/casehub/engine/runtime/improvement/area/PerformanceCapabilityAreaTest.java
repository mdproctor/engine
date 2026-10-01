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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment.LandscapePosition;
import io.casehub.engine.common.internal.history.EventLog;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerformanceCapabilityAreaTest {

  private TestEventLogRepository eventLog;
  private PerformanceCapabilityArea area;
  private UUID caseId;
  private static final String TENANCY = "test-tenant";

  @BeforeEach
  void setUp() {
    eventLog = new TestEventLogRepository();
    area = new PerformanceCapabilityArea(eventLog);
    caseId = UUID.randomUUID();
  }

  @Test
  void noEvents_returnsNeutralAssessment() {
    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isEqualTo(0.5);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.ABSENT);
  }

  @Test
  void fastCase_returnsHighScore() {
    var now = Instant.now();
    appendEvent(CaseHubEventType.CASE_STARTED, now);
    appendEvent(CaseHubEventType.CASE_COMPLETED, now.plusSeconds(10));

    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isEqualTo(1.0);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.AHEAD);
  }

  @Test
  void slowCase_returnsLowScore() {
    var now = Instant.now();
    appendEvent(CaseHubEventType.CASE_STARTED, now);
    appendEvent(CaseHubEventType.CASE_COMPLETED, now.plusSeconds(120));

    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isEqualTo(0.0);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.BEHIND);
  }

  @Test
  void startedWithoutCompleted_returnsNeutral() {
    appendEvent(CaseHubEventType.CASE_STARTED, Instant.now());

    var assessment = area.assess(caseId, TENANCY);
    assertThat(assessment.healthScore()).isEqualTo(0.5);
    assertThat(assessment.landscapePosition()).isEqualTo(LandscapePosition.ABSENT);
  }

  @Test
  void idIsPerformance() {
    assertThat(area.id()).isEqualTo("performance");
  }

  private void appendEvent(CaseHubEventType type, Instant timestamp) {
    var event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(type);
    event.setTimestamp(timestamp);
    eventLog.append(event, TENANCY);
  }
}
