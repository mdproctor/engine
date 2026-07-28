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
package io.casehub.engine.planning.subcase;

import io.casehub.engine.common.internal.model.GroupStatus;
import io.casehub.engine.common.internal.model.SubCaseGroup;

public final class SubCaseGroupPolicy {

  private SubCaseGroupPolicy() {}

  public static GroupStatus evaluate(SubCaseGroup group) {
    if (group.isPolicyTriggered()) return null;
    int remaining = group.getInstanceCount() - group.getCompletedCount() - group.getRejectedCount();
    int needed = group.getRequiredCount() - group.getCompletedCount();
    if (group.getCompletedCount() >= group.getRequiredCount()) return GroupStatus.COMPLETED;
    if (remaining < needed) return GroupStatus.REJECTED;
    return GroupStatus.IN_PROGRESS;
  }

  public static SubCaseGroupLifecycleEvent toEvent(
      SubCaseGroup group, GroupStatus status, String tenancyId) {
    return new SubCaseGroupLifecycleEvent(
        group.getParentCaseId(),
        tenancyId,
        group.getGroupId(),
        group.getInstanceCount(),
        group.getRequiredCount(),
        group.getCompletedCount(),
        group.getRejectedCount(),
        status);
  }
}
