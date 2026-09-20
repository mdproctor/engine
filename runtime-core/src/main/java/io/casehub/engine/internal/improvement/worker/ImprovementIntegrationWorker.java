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
package io.casehub.engine.internal.improvement.worker;

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.spi.EventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ImprovementIntegrationWorker {

  private final EventLogRepository eventLogRepository;

  @Inject
  public ImprovementIntegrationWorker(EventLogRepository eventLogRepository) {
    this.eventLogRepository = eventLogRepository;
  }

  public void verifyReviewGate(UUID caseId, String tenancyId) {
    var reviewEvents =
        eventLogRepository.findByCaseAndTypes(
            caseId, List.of(CaseHubEventType.WORKER_EXECUTION_COMPLETED), tenancyId);
    boolean hasPassingReview =
        reviewEvents.stream()
            .anyMatch(
                e ->
                    e.getPayload() != null
                        && e.getPayload().has("capability")
                        && "code-review".equals(e.getPayload().get("capability").asText())
                        && e.getPayload().has("verdict")
                        && "approved".equals(e.getPayload().get("verdict").asText()));
    if (!hasPassingReview) {
      throw new IllegalStateException(
          "Integration blocked: no passing review record found for improvement case " + caseId);
    }
  }
}
