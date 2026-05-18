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
package io.casehub.engine.spi;

import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Blocking SPI for durable {@link io.casehub.blackboard.plan.PlanItem} status persistence.
 *
 * <p>Used by {@code HumanTaskScheduleHandler} (blocking context) to write PlanItem status in the
 * same JTA transaction as WorkItem creation. The default no-op implementation ({@code
 * NoOpPlanItemStore}) is active when no real store is on the classpath.
 *
 * @see ReactivePlanItemStore for the Uni-returning mirror
 */
public interface PlanItemStore {

  /** Record a new PlanItem. Called from {@code DefaultCasePlanModel.addPlanItem()}. */
  void save(
      UUID caseId, String planItemId, String bindingName, PlanItemStatus status, Instant createdAt);

  /** Update the stored status for an existing PlanItem. */
  void updateStatus(String planItemId, PlanItemStatus status);

  /** Return all PlanItemRecords for the given case. */
  List<PlanItemRecord> findByCaseId(UUID caseId);
}
