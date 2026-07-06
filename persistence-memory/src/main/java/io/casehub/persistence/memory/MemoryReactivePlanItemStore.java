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
package io.casehub.persistence.memory;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.ReactivePlanItemStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Reactive wrapper around {@link MemoryPlanItemStore} for engine handlers on Vert.x IO threads.
 * Activated via {@code quarkus.arc.selected-alternatives} — never active in production.
 *
 * <p>Delegate is injected by SPI interface (not concrete class) to avoid Quarkus ARC
 * {@code @Alternative} resolution issues.
 */
@Alternative
@ApplicationScoped
public class MemoryReactivePlanItemStore implements ReactivePlanItemStore {

  @Inject PlanItemStore delegate;

  @Override
  public Uni<Void> save(PlanItemSaveRequest request, String tenancyId) {
    delegate.save(request, tenancyId);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, PlanItemStatus status) {
    delegate.updateStatus(planItemId, status);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<List<PlanItemRecord>> findByCaseId(UUID caseId, String tenancyId) {
    return Uni.createFrom().item(delegate.findByCaseId(caseId, tenancyId));
  }

  @Override
  public Uni<List<PlanItemRecord>> findDelegated(UUID caseId) {
    return Uni.createFrom().item(delegate.findDelegated(caseId));
  }

  @Override
  public Uni<List<PlanItemRecord>> findAllDelegated() {
    return Uni.createFrom().item(delegate.findAllDelegated());
  }
}
