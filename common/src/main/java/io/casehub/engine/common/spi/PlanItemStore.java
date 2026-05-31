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
 * <p>tenancyId is explicit on save and findByCaseId. Three methods intentionally omit it:
 *
 * <ul>
 *   <li>{@code updateStatus}: planItemId is a UUID string (globally unique) — no cross-tenant
 *       collision possible
 *   <li>{@code findDelegated(UUID)}: caseId is a UUID (globally unique); used by BlackboardRegistry
 *       hydration which self-bootstraps tenancyId from the returned PlanItemRecord
 *   <li>{@code findAllDelegated()}: cross-tenant by design — startup recovery only
 *       (HumanTaskRecoveryService)
 * </ul>
 */
public interface PlanItemStore {

  /** Record a new PlanItem scoped to tenancyId. */
  void save(PlanItemSaveRequest request, String tenancyId);

  /** Update status by planItemId (UUID string — globally unique; no tenancyId needed in WHERE). */
  void updateStatus(String planItemId, PlanItemStatus status);

  /** Return all PlanItemRecords for the given case within the tenant. */
  List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId);

  /**
   * Return DELEGATED PlanItemRecords for the specific case. caseId is UUID (globally unique) — no
   * tenancyId filter needed. Used by BlackboardRegistry lazy hydration; tenancyId self-bootstrapped
   * from returned records.
   */
  List<PlanItemRecord> findDelegated(UUID caseId);

  /**
   * Return ALL DELEGATED PlanItemRecords across all tenants. Cross-tenant by design — startup
   * recovery only (HumanTaskRecoveryService).
   */
  List<PlanItemRecord> findAllDelegated();
}
