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

import io.casehub.engine.plan.snapshot.DagPlanSnapshot;
import io.casehub.engine.plan.snapshot.DecompositionSnapshot;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@DefaultBean
@ApplicationScoped
public class InMemoryExecutionSnapshotStore implements ExecutionSnapshotStore {

  private static final class CaseSnapshots {
    final AtomicReference<DecompositionSnapshot> decomposition = new AtomicReference<>();
    final AtomicReference<DagPlanSnapshot> dagPlan = new AtomicReference<>();
    final AtomicReference<DagResultSnapshot> dagResult = new AtomicReference<>();
  }

  private final ConcurrentHashMap<UUID, CaseSnapshots> entries = new ConcurrentHashMap<>();

  @Override
  public void storeDecomposition(UUID caseId, DecompositionSnapshot snapshot) {
    entries.computeIfAbsent(caseId, k -> new CaseSnapshots()).decomposition.set(snapshot);
  }

  @Override
  public Optional<DecompositionSnapshot> getDecomposition(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    return e != null ? Optional.ofNullable(e.decomposition.get()) : Optional.empty();
  }

  @Override
  public void storeDagPlan(UUID caseId, DagPlanSnapshot snapshot) {
    entries.computeIfAbsent(caseId, k -> new CaseSnapshots()).dagPlan.set(snapshot);
  }

  @Override
  public Optional<DagPlanSnapshot> getDagPlan(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    return e != null ? Optional.ofNullable(e.dagPlan.get()) : Optional.empty();
  }

  @Override
  public void storeDagResult(UUID caseId, DagResultSnapshot snapshot) {
    entries.computeIfAbsent(caseId, k -> new CaseSnapshots()).dagResult.set(snapshot);
  }

  @Override
  public Optional<DagResultSnapshot> getDagResult(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    return e != null ? Optional.ofNullable(e.dagResult.get()) : Optional.empty();
  }

  @Override
  public void evict(UUID caseId) {
    entries.remove(caseId);
  }
}
