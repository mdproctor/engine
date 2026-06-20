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
package io.casehub.blackboard.plan;

import io.casehub.api.model.SubCase;
import io.casehub.blackboard.stage.Stage;
import io.casehub.blackboard.stage.StageStatus;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory {@link CasePlanModel} implementation. Plan state is transient — rebuilt
 * from EventLog on engine recovery. See casehubio/engine#76. Persistence SPI deferred — see
 * casehubio/engine#84.
 */
public class DefaultCasePlanModel implements CasePlanModel {

  private final UUID caseId;
  private final PriorityBlockingQueue<PlanItem> agenda = new PriorityBlockingQueue<>();
  private final ConcurrentHashMap<String, PlanItem> itemsById = new ConcurrentHashMap<>();
  // bindingName → PlanItem (only PENDING or RUNNING items) — fast O(1) duplicate-prevention lookup
  private final ConcurrentHashMap<String, PlanItem> activeByBinding = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Stage> stages = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> milestones = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<SubCase> subCases = new CopyOnWriteArrayList<>();
  private volatile Map<String, Object> resourceBudget = Map.of();
  private volatile String focus;
  private volatile String focusRationale;

  public DefaultCasePlanModel(UUID caseId) {
    this.caseId = caseId;
  }

  @Override
  public UUID getCaseId() {
    return caseId;
  }

  @Override
  public void addPlanItem(PlanItem item) {
    agenda.add(item);
    itemsById.put(item.getPlanItemId(), item);
    activeByBinding.put(item.getBindingName(), item);
  }

  @Override
  public boolean addPlanItemIfAbsent(PlanItem item) {
    boolean[] added = {false};
    activeByBinding.compute(
        item.getBindingName(),
        (k, existing) -> {
          if (existing != null && existing.getStatus().isActive()) {
            return existing; // active item present — reject
          }
          agenda.add(item);
          itemsById.put(item.getPlanItemId(), item);
          added[0] = true;
          return item;
        });
    return added[0];
  }

  /**
   * Restores a PlanItem from persistent store into the live plan after a JVM restart.
   *
   * <p>Adds the item to itemsById and activeByBinding so completion handlers can find it, but does
   * NOT add it to the agenda — restored items are not pending dispatch.
   */
  @Override
  public void restorePlanItem(PlanItem item) {
    itemsById.put(item.getPlanItemId(), item);
    activeByBinding.put(item.getBindingName(), item);
  }

  @Override
  public void removePlanItem(String planItemId) {
    PlanItem item = itemsById.remove(planItemId);
    if (item != null) {
      agenda.remove(item);
      activeByBinding.remove(item.getBindingName(), item); // CAS: only removes if still this item
    }
  }

  @Override
  public Optional<PlanItem> getPlanItem(String planItemId) {
    return Optional.ofNullable(itemsById.get(planItemId));
  }

  @Override
  public Optional<PlanItem> getPlanItemByBindingName(String bindingName) {
    PlanItem item = activeByBinding.get(bindingName);
    if (item == null) return Optional.empty();
    return item.getStatus().isActive() ? Optional.of(item) : Optional.empty();
  }

  @Override
  public boolean hasActivePlanItem(String bindingName) {
    PlanItem item = activeByBinding.get(bindingName);
    if (item == null) return false;
    if (item.getStatus().isActive()) return true;
    // Item exists but is terminal — clean up the stale entry
    activeByBinding.remove(bindingName, item);
    return false;
  }

  @Override
  public List<PlanItem> getAgenda() {
    // PriorityBlockingQueue.stream() is NOT guaranteed to return elements in priority order —
    // the explicit sort is required. RUNNING items remain in the queue (for observability via
    // itemsById) but are filtered here so only PENDING items appear on the returned agenda.
    return agenda.stream()
        .filter(p -> p.getStatus() == PlanItemStatus.PENDING)
        .sorted()
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<PlanItem> getTopPlanItems(int maxCount) {
    List<PlanItem> all = getAgenda();
    return Collections.unmodifiableList(all.size() <= maxCount ? all : all.subList(0, maxCount));
  }

  @Override
  public void addStage(Stage stage) {
    stages.put(stage.getStageId(), stage);
  }

  @Override
  public Optional<Stage> getStage(String stageId) {
    return Optional.ofNullable(stages.get(stageId));
  }

  @Override
  public List<Stage> getPendingStages() {
    return stages.values().stream()
        .filter(s -> s.getStatus() == StageStatus.PENDING)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<Stage> getActiveStages() {
    return stages.values().stream()
        .filter(s -> s.getStatus() == StageStatus.ACTIVE)
        .collect(Collectors.toUnmodifiableList());
  }

  @Override
  public List<Stage> getAllStages() {
    return List.copyOf(stages.values());
  }

  @Override
  public void trackMilestone(String name) {
    milestones.putIfAbsent(name, Boolean.FALSE);
  }

  @Override
  public void achieveMilestone(String name) {
    milestones.put(name, Boolean.TRUE);
  }

  @Override
  public boolean isMilestoneAchieved(String name) {
    return Boolean.TRUE.equals(milestones.get(name));
  }

  @Override
  public void setFocus(String f) {
    this.focus = f;
  }

  @Override
  public Optional<String> getFocus() {
    return Optional.ofNullable(focus);
  }

  @Override
  public void setFocusRationale(String r) {
    this.focusRationale = r;
  }

  @Override
  public Optional<String> getFocusRationale() {
    return Optional.ofNullable(focusRationale);
  }

  @Override
  public void setResourceBudget(Map<String, Object> budget) {
    this.resourceBudget = Map.copyOf(budget);
  }

  @Override
  public Map<String, Object> getResourceBudget() {
    return resourceBudget;
  }

  @Override
  public void put(String key, Object value) {
    state.put(key, value);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Optional<T> get(String key, Class<T> type) {
    Object v = state.get(key);
    return (v != null && type.isInstance(v)) ? Optional.of((T) v) : Optional.empty();
  }

  @Override
  public void addSubCase(SubCase s) {
    subCases.add(java.util.Objects.requireNonNull(s));
  }

  @Override
  public List<SubCase> getSubCases() {
    return List.copyOf(subCases);
  }
}
