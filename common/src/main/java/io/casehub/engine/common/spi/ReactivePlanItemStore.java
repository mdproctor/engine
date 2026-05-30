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
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.UUID;

/**
 * Reactive mirror of PlanItemStore — method signatures identical, return types wrapped in Uni. For
 * engine runtime handlers on Vert.x IO threads.
 */
public interface ReactivePlanItemStore {

  Uni<Void> save(PlanItemSaveRequest request);

  Uni<Void> updateStatus(String planItemId, PlanItemStatus status);

  Uni<List<PlanItemRecord>> findByCaseId(UUID caseId);

  Uni<List<PlanItemRecord>> findDelegated(UUID caseId);

  Uni<List<PlanItemRecord>> findAllDelegated();
}
