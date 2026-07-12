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

import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import java.util.List;
import java.util.UUID;

/**
 * Blocking SPI for durable PlanItem status persistence.
 *
 * <p>tenancyId is explicit on all tenant-scoped methods. Cross-tenant methods are explicitly named
 * ({@code findDelegatedCrossTenant}, {@code findAllDelegated}).
 */
public interface PlanItemStore {

  void save(PlanItemSaveRequest request, String tenancyId);

  void updateStatus(String planItemId, TaskStatus status);

  /** Update status with explicit tenancyId for RLS enforcement. */
  default void updateStatus(String planItemId, TaskStatus status, String tenancyId) {
    updateStatus(planItemId, status);
  }

  List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId);

  /** Tenant-scoped overload for callers that have tenancyId. */
  default List<PlanItemRecord> findDelegated(UUID caseId, String tenancyId) {
    return findDelegatedCrossTenant(caseId);
  }

  List<PlanItemRecord> findDelegatedCrossTenant(UUID caseId);

  List<PlanItemRecord> findAllDelegated();
}
