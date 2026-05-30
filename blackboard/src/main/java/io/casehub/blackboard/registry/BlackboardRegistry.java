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
package io.casehub.blackboard.registry;

import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared registry of per-case {@link CasePlanModel} instances and the worker-name-to-PlanItemId
 * completion index.
 *
 * <p>All per-case state is co-located in a single {@link CaseEntry}, making eviction atomic — one
 * map removal instead of three. See casehubio/engine#292.
 *
 * <p>On first {@link #get} miss after a JVM restart, DELEGATED PlanItems are lazily restored from
 * PlanItemStore so completion handlers can find their PlanItems without any startup ordering
 * constraint. RUNNING items and completionIndex are not persisted — Quartz-only case recovery is a
 * separate concern. See casehubio/engine#274.
 */
@ApplicationScoped
public class BlackboardRegistry {

  private static final class CaseEntry {
    final CasePlanModel planModel;
    final ConcurrentHashMap<String, String> completionIndex = new ConcurrentHashMap<>();
    final AtomicBoolean configured = new AtomicBoolean(false);

    CaseEntry(UUID caseId) {
      this.planModel = new DefaultCasePlanModel(caseId);
    }
  }

  private final ConcurrentHashMap<UUID, CaseEntry> entries = new ConcurrentHashMap<>();
  private final PlanItemRestorer restorer = new PlanItemRestorer();

  @Inject PlanItemStore planItemStore;

  /**
   * Returns the {@link CasePlanModel} for the given case, creating it if absent. Only {@link
   * io.casehub.blackboard.control.PlanningStrategyLoopControl} should call this method — all other
   * components should use {@link #get(UUID)}.
   */
  public CasePlanModel getOrCreate(UUID caseId) {
    return entries.computeIfAbsent(caseId, CaseEntry::new).planModel;
  }

  /**
   * Returns the {@link CasePlanModel} for the given case, or empty if absent.
   *
   * <p>On miss: queries PlanItemStore for DELEGATED records and hydrates the registry before
   * returning. Two concurrent misses for the same case each query the store; the loser finds the
   * entry already populated and re-applies restorePlanItem() (idempotent — same planItemId, same
   * data). The cost is at most one duplicate DB call per concurrent miss, acceptable post-restart.
   *
   * <p>If planItemStore is null (unit tests that construct BlackboardRegistry directly without
   * CDI), returns empty immediately without attempting hydration.
   */
  public Optional<CasePlanModel> get(UUID caseId) {
    CaseEntry e = entries.get(caseId);
    if (e != null) return Optional.of(e.planModel);

    if (planItemStore == null) return Optional.empty();

    List<PlanItemRecord> records = planItemStore.findDelegated(caseId);
    if (records.isEmpty()) return Optional.empty();

    CaseEntry hydrated = entries.computeIfAbsent(caseId, CaseEntry::new);
    records.forEach(r -> hydrated.planModel.restorePlanItem(restorer.restore(r)));
    return Optional.of(hydrated.planModel);
  }

  public void indexForCompletion(UUID caseId, String workerName, String planItemId) {
    CaseEntry e = entries.get(caseId);
    if (e != null) {
      e.completionIndex.put(workerName, planItemId);
    }
  }

  public Optional<String> getPlanItemId(UUID caseId, String workerName) {
    CaseEntry e = entries.get(caseId);
    return e == null ? Optional.empty() : Optional.ofNullable(e.completionIndex.get(workerName));
  }

  /**
   * Atomically marks a case as configured by {@link
   * io.casehub.blackboard.control.BlackboardPlanConfigurer}(s). Returns {@code true} only the first
   * time this method is called for the given case — subsequent calls return {@code false}. This
   * guarantees configurers are invoked exactly once per case instance.
   *
   * <p>Contract: BlackboardPlanConfigurer implementations must be idempotent with respect to
   * pre-populated plan items — addPlanItemIfAbsent() correctly rejects re-dispatch of DELEGATED
   * bindings already in activeByBinding.
   */
  public boolean markConfigured(UUID caseId) {
    CaseEntry e = entries.get(caseId);
    return e != null && e.configured.compareAndSet(false, true);
  }

  /**
   * Atomically evicts the plan model, completion index, and configured marker for a completed or
   * terminated case. Single map removal — no race window between partial removes. Call when a case
   * reaches a terminal state to prevent unbounded memory growth. See casehubio/engine#84.
   */
  public void evict(UUID caseId) {
    entries.remove(caseId);
  }
}
