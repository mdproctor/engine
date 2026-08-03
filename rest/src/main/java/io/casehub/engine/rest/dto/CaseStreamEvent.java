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
package io.casehub.engine.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.common.spi.event.PlanItemStateChangedEvent;
import java.util.Map;
import java.util.UUID;

public record CaseStreamEvent(@JsonIgnore UUID caseId, String type, Map<String, String> data) {

  public static CaseStreamEvent planItem(PlanItemStateChangedEvent event) {
    return new CaseStreamEvent(
        event.caseId(),
        "plan-item",
        Map.of(
            "planItemId", event.planItemId(),
            "bindingName", event.bindingName(),
            "previousStatus",
                event.previousStatus() != null ? event.previousStatus().name() : "NONE",
            "newStatus", event.newStatus().name()));
  }

  public static CaseStreamEvent context(CaseContextUpdatedEvent event) {
    return new CaseStreamEvent(
        event.caseId(), "context", Map.of("changedLayer", event.changedLayer()));
  }
}
