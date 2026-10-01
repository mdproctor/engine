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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.stigmergy.CapabilityAreaAssessment;
import io.casehub.engine.common.spi.EventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CognitiveReasoningCapabilityArea extends AbstractCapabilityArea {

  private static final Collection<CaseHubEventType> GOAL_TYPES =
      List.of(
          CaseHubEventType.GOAL_FORMED,
          CaseHubEventType.GOAL_REACHED,
          CaseHubEventType.GOAL_REVISED,
          CaseHubEventType.PLAN_ADAPTED,
          CaseHubEventType.PLAN_CONCEDED);

  private final EventLogRepository eventLog;

  @Inject
  public CognitiveReasoningCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "cognitive-reasoning";
  }

  @Override
  public String name() {
    return "Cognitive Reasoning";
  }

  @Override
  public String description() {
    return "Goal achievement rate — ratio of reached goals to formed goals";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, GOAL_TYPES, tenancyId);
    if (events.isEmpty()) {
      return neutralAssessment();
    }
    long formed =
        events.stream().filter(e -> e.getEventType() == CaseHubEventType.GOAL_FORMED).count();
    long reached =
        events.stream().filter(e -> e.getEventType() == CaseHubEventType.GOAL_REACHED).count();
    if (formed == 0) {
      return neutralAssessment();
    }
    double healthScore = Math.min(1.0, (double) reached / formed);
    return buildAssessment(healthScore);
  }
}
