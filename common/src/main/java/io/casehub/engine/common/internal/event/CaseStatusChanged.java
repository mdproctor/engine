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

import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.internal.model.CaseInstance;

/**
 * Published when a case transitions to a new status. Optional goal metadata is present when the
 * transition was triggered by goal satisfaction.
 *
 * @param instance the case instance
 * @param oldStatus previous status name
 * @param newStatus new status name
 * @param satisfiedGoalName name of the satisfied goal, or null if not goal-triggered
 * @param satisfiedGoalKind kind of the satisfied goal (SUCCESS or FAILURE), or null
 */
public record CaseStatusChanged(
    CaseInstance instance,
    String oldStatus,
    String newStatus,
    String satisfiedGoalName,
    GoalKind satisfiedGoalKind) {

  public CaseStatusChanged(CaseInstance instance, String oldStatus, String newStatus) {
    this(instance, oldStatus, newStatus, null, null);
  }
}
