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
public class StabilityCapabilityArea extends AbstractCapabilityArea {

  private static final Collection<CaseHubEventType> TERMINAL_TYPES =
      List.of(
          CaseHubEventType.CASE_COMPLETED,
          CaseHubEventType.CASE_FAULTED,
          CaseHubEventType.CASE_CANCELLED);

  private final EventLogRepository eventLog;

  @Inject
  public StabilityCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "stability";
  }

  @Override
  public String name() {
    return "Stability";
  }

  @Override
  public String description() {
    return "Case completion rate — ratio of successfully completed cases to total terminal events";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, TERMINAL_TYPES, tenancyId);
    if (events.isEmpty()) {
      return neutralAssessment();
    }
    long successes =
        events.stream().filter(e -> e.getEventType() == CaseHubEventType.CASE_COMPLETED).count();
    double healthScore = (double) successes / events.size();
    return buildAssessment(healthScore);
  }
}
