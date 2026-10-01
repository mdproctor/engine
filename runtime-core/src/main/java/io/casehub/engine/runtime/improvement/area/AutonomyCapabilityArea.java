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
public class AutonomyCapabilityArea extends AbstractCapabilityArea {

  private static final Collection<CaseHubEventType> AUTONOMY_TYPES =
      List.of(
          CaseHubEventType.IMPROVEMENT_GOAL_FORMED,
          CaseHubEventType.IMPROVEMENT_OUTCOME,
          CaseHubEventType.SWARM_PROVISION_REQUESTED,
          CaseHubEventType.SWARM_PROVISION_COMPLETED);

  private static final int ACTIVITY_NORMALIZER = 10;

  private final EventLogRepository eventLog;

  @Inject
  public AutonomyCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "autonomy";
  }

  @Override
  public String name() {
    return "Autonomy";
  }

  @Override
  public String description() {
    return "Autonomous action rate — improvements and self-provisioning normalised";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, AUTONOMY_TYPES, tenancyId);
    if (events.isEmpty()) {
      return neutralAssessment();
    }
    double healthScore = Math.min(1.0, (double) events.size() / ACTIVITY_NORMALIZER);
    return buildAssessment(healthScore);
  }
}
