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
import io.casehub.engine.spi.PlanItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared registry of per-case {@link CasePlanModel} instances and the worker-name-to-PlanItemId
 * completion index.
 *
 * <p>Injected by both {@link io.casehub.blackboard.control.PlanningStrategyLoopControl} (which
 * writes entries on Binding selection) and {@link
 * io.casehub.blackboard.handler.PlanItemCompletionHandler} (which reads entries on worker
 * completion). See casehubio/engine#76.
 *
 * <p>State is in-memory and transient — rebuilt from EventLog on engine recovery. Persistence SPI
 * deferred to casehubio/engine#84.
 */
@ApplicationScoped
public class BlackboardRegistry {

  @Inject PlanItemStore planItemStore;

  // caseId → CasePlanModel
  private final ConcurrentHashMap<UUID, CasePlanModel> planModels = new ConcurrentHashMap<>();

  // caseId → (workerName → planItemId)
  private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>> completionIndex =
      new ConcurrentHashMap<>();

  // caseIds that have already been configured by BlackboardPlanConfigurer(s)
  private final Set<UUID> configured = ConcurrentHashMap.newKeySet();

  /**
   * Returns the {@link CasePlanModel} for the given case, creating it if absent. Only {@link
   * io.casehub.blackboard.control.PlanningStrategyLoopControl} should call this method — all other
   * components should use {@link #get(UUID)}.
   */
  public CasePlanModel getOrCreate(UUID caseId) {
    PlanItemStore effective =
        planItemStore != null ? planItemStore : new io.casehub.blackboard.store.NoOpPlanItemStore();
    return planModels.computeIfAbsent(caseId, id -> new DefaultCasePlanModel(id, effective));
  }

  public Optional<CasePlanModel> get(UUID caseId) {
    return Optional.ofNullable(planModels.get(caseId));
  }

  public void indexWorkerForCompletion(UUID caseId, String workerName, String planItemId) {
    completionIndex
        .computeIfAbsent(caseId, k -> new ConcurrentHashMap<>())
        .put(workerName, planItemId);
  }

  public Optional<String> getPlanItemId(UUID caseId, String workerName) {
    ConcurrentHashMap<String, String> index = completionIndex.get(caseId);
    return index == null ? Optional.empty() : Optional.ofNullable(index.get(workerName));
  }

  /**
   * Atomically marks a case as configured by {@link
   * io.casehub.blackboard.control.BlackboardPlanConfigurer}(s). Returns {@code true} only the first
   * time this method is called for the given case — subsequent calls return {@code false}. This
   * guarantees configurers are invoked exactly once per case instance.
   */
  public boolean markConfigured(UUID caseId) {
    return configured.add(caseId);
  }

  /**
   * Evicts the plan model, completion index, and configured marker for a completed or terminated
   * case. Call when a case reaches a terminal state to prevent unbounded memory growth. See
   * casehubio/engine#84 for the persistence SPI that will eventually replace this in-memory
   * registry.
   */
  public void evict(UUID caseId) {
    planModels.remove(caseId);
    completionIndex.remove(caseId);
    configured.remove(caseId);
  }
}
