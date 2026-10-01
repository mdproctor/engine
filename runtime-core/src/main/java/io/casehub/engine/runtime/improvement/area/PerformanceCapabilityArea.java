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
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PerformanceCapabilityArea extends AbstractCapabilityArea {

  static final Duration DEFAULT_SLA_THRESHOLD = Duration.ofSeconds(60);

  private static final Collection<CaseHubEventType> LIFECYCLE_TYPES =
      List.of(CaseHubEventType.CASE_STARTED, CaseHubEventType.CASE_COMPLETED);

  private final EventLogRepository eventLog;

  @Inject
  public PerformanceCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "performance";
  }

  @Override
  public String name() {
    return "Performance";
  }

  @Override
  public String description() {
    return "Case completion speed — ratio of cases completing within SLA threshold";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, LIFECYCLE_TYPES, tenancyId);
    EventLog started = null;
    EventLog completed = null;
    for (var event : events) {
      if (event.getEventType() == CaseHubEventType.CASE_STARTED) {
        started = event;
      } else if (event.getEventType() == CaseHubEventType.CASE_COMPLETED) {
        completed = event;
      }
    }
    if (started == null || completed == null) {
      return neutralAssessment();
    }
    var duration = Duration.between(started.getTimestamp(), completed.getTimestamp());
    double healthScore = duration.compareTo(DEFAULT_SLA_THRESHOLD) <= 0 ? 1.0 : 0.0;
    return buildAssessment(healthScore);
  }
}
