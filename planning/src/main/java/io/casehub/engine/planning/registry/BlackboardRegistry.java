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
package io.casehub.engine.planning.registry;

import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Shared registry of per-case {@link CasePlanModel} instances and the worker-name-to-PlanItemId
 * completion index.
 *
 * <p>All per-case state is co-located in a single {@link CaseEntry}, making eviction atomic — one
 * map removal instead of three. See casehubio/engine#292.
 *
 * <p>Map key is UUID (globally unique — no cross-tenant collision possible). {@link CaseEntry}
 * stores tenancyId at creation time for defense-in-depth checks in {@link #get(UUID, String)}.
 *
 * <p>On first {@link #get} miss after a JVM restart, DELEGATED PlanItems are lazily restored from
 * PlanItemStore so completion handlers can find their PlanItems without any startup ordering
 * constraint. RUNNING items and completionIndex are not persisted — Quartz-only case recovery is a
 * separate concern. See casehubio/engine#274.
 */
@ApplicationScoped
public class BlackboardRegistry implements io.casehub.engine.common.spi.Resettable {

  private static final Logger LOG = Logger.getLogger(BlackboardRegistry.class);

  private static final class CaseEntry {
    final String tenancyId;
    final CasePlanModel planModel;
    final ConcurrentHashMap<String, String> completionIndex = new ConcurrentHashMap<>();
    final AtomicBoolean configured = new AtomicBoolean(false);

    CaseEntry(UUID caseId, String tenancyId) {
      this.tenancyId = tenancyId;
      this.planModel = new DefaultCasePlanModel(caseId);
    }
  }

  private final ConcurrentHashMap<UUID, CaseEntry> entries = new ConcurrentHashMap<>();
  private final PlanItemRestorer restorer = new PlanItemRestorer();

  @Inject PlanItemStore planItemStore;

  /**
   * Returns the {@link CasePlanModel} for the given case, creating it if absent. Only {@link
   * io.casehub.engine.planning.control.PlanningStrategyLoopControl} should call this method — all
   * other components should use {@link #get(UUID, String)} or {@link #get(UUID)}.
   *
   * @param tenancyId the tenant that owns this case — stored in {@link CaseEntry} for
   *     defense-in-depth checks
   */
  public CasePlanModel getOrCreate(UUID caseId, String tenancyId) {
    return entries.computeIfAbsent(caseId, id -> new CaseEntry(id, tenancyId)).planModel;
  }

  /**
   * Returns the {@link CasePlanModel} for the given case with tenancy defense-in-depth.
   *
   * <p>Returns {@link Optional#empty()} if the stored tenancyId does not match — logs a warning for
   * visibility. Lazy hydration uses tenancyId for the store call.
   */
  public Optional<CasePlanModel> get(UUID caseId, String tenancyId) {
    CaseEntry e = entries.get(caseId);
    if (e != null) {
      if (!e.tenancyId.equals(tenancyId)) {
        LOG.warnf(
            "Tenant mismatch for caseId=%s (stored=%s, requested=%s)",
            caseId, e.tenancyId, tenancyId);
        return Optional.empty();
      }
      return Optional.of(e.planModel);
    }

    if (planItemStore == null) return Optional.empty();

    List<PlanItemRecord> records = planItemStore.findDelegated(caseId, tenancyId);
    if (records.isEmpty()) return Optional.empty();

    CaseEntry hydrated = entries.computeIfAbsent(caseId, id -> new CaseEntry(id, tenancyId));
    records.forEach(r -> hydrated.planModel.restorePlanItem(restorer.restore(r)));
    return Optional.of(hydrated.planModel);
  }

  /**
   * UUID-only get for callers without tenancyId (e.g. WorkItemLifecycleAdapter). UUID global
   * uniqueness prevents cross-tenant collision. Self-bootstraps tenancyId from the first
   * PlanItemRecord on lazy hydration.
   */
  public Optional<CasePlanModel> get(UUID caseId) {
    CaseEntry e = entries.get(caseId);
    if (e != null) return Optional.of(e.planModel);

    if (planItemStore == null) return Optional.empty();

    List<PlanItemRecord> records = planItemStore.findDelegatedCrossTenant(caseId);
    if (records.isEmpty()) return Optional.empty();

    String inferredTenancyId =
        records.stream()
            .map(PlanItemRecord::tenancyId)
            .filter(t -> t != null && !t.isBlank())
            .findFirst()
            .orElse("unknown");

    CaseEntry hydrated =
        entries.computeIfAbsent(caseId, id -> new CaseEntry(id, inferredTenancyId));
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

  /** Atomically marks a case as configured. Returns {@code true} only the first time per case. */
  public boolean markConfigured(UUID caseId) {
    CaseEntry e = entries.get(caseId);
    return e != null && e.configured.compareAndSet(false, true);
  }

  /**
   * O(1) eviction by UUID key. UUID global uniqueness means no cross-tenant key collision. Does not
   * read any principal — robust regardless of what execution context is active at eviction time.
   */
  public void evict(UUID caseId) {
    entries.remove(caseId);
  }

  @Override
  public void reset() {
    entries.clear();
    LOG.info("BlackboardRegistry reset — all case plan models cleared");
  }
}
