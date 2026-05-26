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
import io.casehub.engine.common.internal.model.CaseInstance;
import java.util.Objects;

public record MilestoneReachedEvent(CaseInstance caseInstance, Milestone milestone) {

  public MilestoneReachedEvent(CaseInstance caseInstance, Milestone milestone) {
    this.caseInstance = Objects.requireNonNull(caseInstance, "Instance cannot be null");
    this.milestone = Objects.requireNonNull(milestone, "Milestone cannot be null");
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof MilestoneReachedEvent that)) return false;
    return Objects.equals(milestone, that.milestone)
        && Objects.equals(caseInstance, that.caseInstance);
  }

  @Override
  public int hashCode() {
    return Objects.hash(caseInstance, milestone);
  }
}
