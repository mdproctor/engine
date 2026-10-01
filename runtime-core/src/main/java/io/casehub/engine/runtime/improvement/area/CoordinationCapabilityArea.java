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
public class CoordinationCapabilityArea extends AbstractCapabilityArea {

  private static final Collection<CaseHubEventType> COORDINATION_TYPES =
      List.of(
          CaseHubEventType.CONVERGENCE_DETECTED,
          CaseHubEventType.COORDINATION_STORM_DETECTED,
          CaseHubEventType.SWARM_TEAM_FORMED,
          CaseHubEventType.SWARM_TEAM_DISSOLVED,
          CaseHubEventType.SWARM_PROVISION_COMPLETED,
          CaseHubEventType.SWARM_PROVISION_FAILED);

  private static final Collection<CaseHubEventType> SUCCESS_TYPES =
      List.of(
          CaseHubEventType.CONVERGENCE_DETECTED,
          CaseHubEventType.SWARM_TEAM_FORMED,
          CaseHubEventType.SWARM_PROVISION_COMPLETED);

  private final EventLogRepository eventLog;

  @Inject
  public CoordinationCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "coordination";
  }

  @Override
  public String name() {
    return "Coordination";
  }

  @Override
  public String description() {
    return "Stigmergy convergence and swarm coordination success rate";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, COORDINATION_TYPES, tenancyId);
    if (events.isEmpty()) {
      return neutralAssessment();
    }
    long successes = events.stream().filter(e -> SUCCESS_TYPES.contains(e.getEventType())).count();
    double healthScore = (double) successes / events.size();
    return buildAssessment(healthScore);
  }
}
