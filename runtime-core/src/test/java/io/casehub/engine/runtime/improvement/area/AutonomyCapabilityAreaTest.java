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

class AutonomyCapabilityAreaTest {

  private TestEventLogRepository eventLog;
  private AutonomyCapabilityArea area;
  private UUID caseId;
  private static final String TENANCY = "test-tenant";

  @BeforeEach
  void setUp() {
    eventLog = new TestEventLogRepository();
    area = new AutonomyCapabilityArea(eventLog);
    caseId = UUID.randomUUID();
  }

  @Test
  void noEvents_returnsNeutral() {
    assertThat(area.assess(caseId, TENANCY).landscapePosition())
        .isEqualTo(LandscapePosition.ABSENT);
  }

  @Test
  void someAutonomousActions_returnsNormalisedScore() {
    for (int i = 0; i < 5; i++) {
      appendEvent(CaseHubEventType.IMPROVEMENT_GOAL_FORMED);
    }
    assertThat(area.assess(caseId, TENANCY).healthScore()).isCloseTo(0.5, within(0.01));
  }

  private void appendEvent(CaseHubEventType type) {
    var event = new EventLog();
    event.setCaseId(caseId);
    event.setEventType(type);
    event.setTimestamp(Instant.now());
    eventLog.append(event, TENANCY);
  }
}
