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

import io.casehub.engine.internal.model.PlanItemRecord;
import io.casehub.engine.internal.model.PlanItemStatus;
import io.casehub.engine.spi.PlanItemStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * No-op {@link PlanItemStore} — active when no real store implementation is on the classpath.
 * PlanItem status is tracked in-memory only (via {@link io.casehub.blackboard.plan.PlanItem}).
 */
@DefaultBean
@ApplicationScoped
public class NoOpPlanItemStore implements PlanItemStore {

  @Override
  public void save(
      UUID caseId,
      String planItemId,
      String bindingName,
      PlanItemStatus status,
      Instant createdAt) {}

  @Override
  public void updateStatus(String planItemId, PlanItemStatus status) {}

  @Override
  public List<PlanItemRecord> findByCaseId(UUID caseId) {
    return List.of();
  }
}
