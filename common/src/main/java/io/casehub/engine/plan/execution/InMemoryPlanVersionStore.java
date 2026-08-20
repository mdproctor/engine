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
package io.casehub.engine.plan.execution;

import io.casehub.engine.common.spi.recovery.PlanVersionStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryPlanVersionStore implements PlanVersionStore {
  private final Map<UUID, List<PlanVersion>> history = new ConcurrentHashMap<>();

  @Override
  public void store(PlanVersion version, String tenancyId) {
    history
        .computeIfAbsent(version.caseId(), k -> Collections.synchronizedList(new ArrayList<>()))
        .add(version);
  }

  @Override
  public List<PlanVersion> getHistory(UUID caseId, String tenancyId) {
    return List.copyOf(history.getOrDefault(caseId, List.of()));
  }

  @Override
  public Optional<PlanVersion> getVersion(UUID caseId, int version, String tenancyId) {
    return history.getOrDefault(caseId, List.of()).stream()
        .filter(v -> v.version() == version)
        .findFirst();
  }

  @Override
  public Optional<PlanVersion> getLatest(UUID caseId, String tenancyId) {
    List<PlanVersion> versions = history.getOrDefault(caseId, List.of());
    return versions.isEmpty() ? Optional.empty() : Optional.of(versions.getLast());
  }

  @Override
  public void evict(UUID caseId) {
    history.remove(caseId);
  }
}
