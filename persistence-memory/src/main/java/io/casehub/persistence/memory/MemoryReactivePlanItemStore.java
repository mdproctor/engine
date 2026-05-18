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

import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.spi.ReactivePlanItemStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reactive wrapper around {@link MemoryPlanItemStore} for engine handlers on Vert.x IO threads.
 * Activated via {@code quarkus.arc.selected-alternatives} — never active in production.
 */
@Alternative
@ApplicationScoped
public class MemoryReactivePlanItemStore implements ReactivePlanItemStore {

  @Inject MemoryPlanItemStore delegate;

  @Override
  public Uni<Void> save(
      UUID caseId,
      String planItemId,
      String bindingName,
      PlanItemStatus status,
      Instant createdAt) {
    delegate.save(caseId, planItemId, bindingName, status, createdAt);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, PlanItemStatus status) {
    delegate.updateStatus(planItemId, status);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<List<PlanItemRecord>> findByCaseId(UUID caseId) {
    return Uni.createFrom().item(delegate.findByCaseId(caseId));
  }
}
