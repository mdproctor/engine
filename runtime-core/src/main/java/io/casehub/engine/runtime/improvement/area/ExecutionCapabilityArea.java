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
public class ExecutionCapabilityArea extends AbstractCapabilityArea {

  private static final Collection<CaseHubEventType> WORKER_TYPES =
      List.of(
          CaseHubEventType.WORKER_EXECUTION_COMPLETED,
          CaseHubEventType.WORKER_EXECUTION_FAILED,
          CaseHubEventType.WORKER_OUTCOME_DECLINED,
          CaseHubEventType.WORKER_OUTCOME_FAILED);

  private final EventLogRepository eventLog;

  @Inject
  public ExecutionCapabilityArea(EventLogRepository eventLog) {
    this.eventLog = eventLog;
  }

  @Override
  public String id() {
    return "execution";
  }

  @Override
  public String name() {
    return "Execution";
  }

  @Override
  public String description() {
    return "Worker success rate — ratio of completed workers to total terminal worker events";
  }

  @Override
  public CapabilityAreaAssessment assess(UUID caseId, String tenancyId) {
    var events = eventLog.findByCaseAndTypes(caseId, WORKER_TYPES, tenancyId);
    if (events.isEmpty()) {
      return neutralAssessment();
    }
    long successes =
        events.stream()
            .filter(e -> e.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED)
            .count();
    double healthScore = (double) successes / events.size();
    return buildAssessment(healthScore);
  }
}
