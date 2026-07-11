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
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/** Reactive mirror of PlanItemStore. Same tenancyId rules apply — see PlanItemStore Javadoc. */
public interface ReactivePlanItemStore {

  Uni<Void> save(PlanItemSaveRequest request, String tenancyId);

  /** UUID planItemId — globally unique; no tenancyId needed. */
  Uni<Void> updateStatus(String planItemId, TaskStatus status);

  Uni<List<PlanItemRecord>> findByCaseId(UUID caseId, String tenancyId);

  /** UUID caseId — globally unique; no tenancyId filter needed for hydration. */
  Uni<List<PlanItemRecord>> findDelegated(UUID caseId);

  /** Cross-tenant: startup recovery only. */
  Uni<List<PlanItemRecord>> findAllDelegated();
}
