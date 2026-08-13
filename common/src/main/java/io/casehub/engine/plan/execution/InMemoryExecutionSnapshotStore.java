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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@DefaultBean
@ApplicationScoped
public class InMemoryExecutionSnapshotStore implements ExecutionSnapshotStore {

  static final Duration DEFAULT_TTL = Duration.ofMinutes(60);
  static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofSeconds(60);

  private static final class CaseSnapshots {
    final AtomicReference<DecompositionSnapshot> decomposition = new AtomicReference<>();
    final AtomicReference<DagPlanSnapshot> dagPlan = new AtomicReference<>();
    final AtomicReference<DagResultSnapshot> dagResult = new AtomicReference<>();
    volatile Instant lastAccess = Instant.now();

    void touch() {
      lastAccess = Instant.now();
    }
  }

  private final ConcurrentHashMap<UUID, CaseSnapshots> entries = new ConcurrentHashMap<>();
  private volatile Instant lastSweep = Instant.now();
  private final Duration ttl;
  private final Duration sweepInterval;

  public InMemoryExecutionSnapshotStore() {
    this(DEFAULT_TTL, DEFAULT_SWEEP_INTERVAL);
  }

  InMemoryExecutionSnapshotStore(Duration ttl, Duration sweepInterval) {
    this.ttl = ttl;
    this.sweepInterval = sweepInterval;
  }

  @Override
  public void storeDecomposition(UUID caseId, DecompositionSnapshot snapshot) {
    maybeSweep();
    CaseSnapshots cs = entries.computeIfAbsent(caseId, k -> new CaseSnapshots());
    cs.decomposition.set(snapshot);
    cs.touch();
  }

  @Override
  public Optional<DecompositionSnapshot> getDecomposition(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    if (e != null) {
      e.touch();
    }
    return e != null ? Optional.ofNullable(e.decomposition.get()) : Optional.empty();
  }

  @Override
  public void storeDagPlan(UUID caseId, DagPlanSnapshot snapshot) {
    maybeSweep();
    CaseSnapshots cs = entries.computeIfAbsent(caseId, k -> new CaseSnapshots());
    cs.dagPlan.set(snapshot);
    cs.touch();
  }

  @Override
  public Optional<DagPlanSnapshot> getDagPlan(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    if (e != null) {
      e.touch();
    }
    return e != null ? Optional.ofNullable(e.dagPlan.get()) : Optional.empty();
  }

  @Override
  public void storeDagResult(UUID caseId, DagResultSnapshot snapshot) {
    maybeSweep();
    CaseSnapshots cs = entries.computeIfAbsent(caseId, k -> new CaseSnapshots());
    cs.dagResult.set(snapshot);
    cs.touch();
  }

  @Override
  public Optional<DagResultSnapshot> getDagResult(UUID caseId, String tenancyId) {
    CaseSnapshots e = entries.get(caseId);
    if (e != null) {
      e.touch();
    }
    return e != null ? Optional.ofNullable(e.dagResult.get()) : Optional.empty();
  }

  @Override
  public void evict(UUID caseId) {
    entries.remove(caseId);
  }

  int size() {
    return entries.size();
  }

  private void maybeSweep() {
    Instant now = Instant.now();
    if (Duration.between(lastSweep, now).compareTo(sweepInterval) < 0) {
      return;
    }
    lastSweep = now;
    Instant cutoff = now.minus(ttl);
    entries.entrySet().removeIf(e -> e.getValue().lastAccess.isBefore(cutoff));
  }
}
