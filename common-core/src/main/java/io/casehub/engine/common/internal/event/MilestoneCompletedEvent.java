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
package io.casehub.engine.common.internal.event;

import io.casehub.api.model.Milestone;
import io.casehub.api.model.SlaStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.time.Instant;
import java.util.Objects;

/**
 * Published when a milestone's completionCriteria becomes true (ACTIVE → COMPLETED transition).
 *
 * <p>SLA status at completion time is included (ON_TRACK or BREACHED).
 */
public record MilestoneCompletedEvent(
    CaseInstance caseInstance,
    Milestone milestone,
    Instant completedAt,
    SlaStatus slaStatusAtCompletion) {

  public MilestoneCompletedEvent {
    Objects.requireNonNull(caseInstance, "caseInstance must not be null");
    Objects.requireNonNull(milestone, "milestone must not be null");
    Objects.requireNonNull(completedAt, "completedAt must not be null");
    Objects.requireNonNull(slaStatusAtCompletion, "slaStatusAtCompletion must not be null");
  }
}
