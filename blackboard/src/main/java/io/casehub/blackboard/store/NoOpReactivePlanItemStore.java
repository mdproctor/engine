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
package io.casehub.blackboard.store;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.spi.ReactivePlanItemStore;
import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** No-op reactive mirror of {@link NoOpPlanItemStore}. */
@DefaultBean
@ApplicationScoped
public class NoOpReactivePlanItemStore implements ReactivePlanItemStore {

  @Override
  public Uni<Void> save(
      UUID caseId,
      String planItemId,
      String bindingName,
      PlanItemStatus status,
      Instant createdAt) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Void> updateStatus(String planItemId, PlanItemStatus status) {
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<List<PlanItemRecord>> findByCaseId(UUID caseId) {
    return Uni.createFrom().item(List.of());
  }
}
