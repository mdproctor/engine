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
package io.casehub.engine.common.spi;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import java.util.List;
import java.util.UUID;

/**
 * Blocking SPI for durable PlanItem status persistence.
 *
 * <p>Used by HumanTaskScheduleHandler (blocking context) to write PlanItem status in the same JTA
 * transaction as WorkItem creation. The default no-op implementation (NoOpPlanItemStore) is active
 * when no real store is on the classpath.
 */
public interface PlanItemStore {

  /** Record a new PlanItem. */
  void save(PlanItemSaveRequest request);

  /** Update the stored status for an existing PlanItem. */
  void updateStatus(String planItemId, PlanItemStatus status);

  /** Return all PlanItemRecords for the given case. */
  List<PlanItemRecord> findByCaseId(UUID caseId);

  /**
   * Return all DELEGATED PlanItemRecords for the given case. Used by BlackboardRegistry lazy
   * hydration.
   */
  List<PlanItemRecord> findDelegated(UUID caseId);

  /**
   * Return all DELEGATED PlanItemRecords across all cases. Used by HumanTaskRecoveryService at
   * startup.
   */
  List<PlanItemRecord> findAllDelegated();
}
