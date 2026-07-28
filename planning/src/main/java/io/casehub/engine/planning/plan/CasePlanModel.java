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
package io.casehub.engine.planning.plan;

import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SubCase;
import io.casehub.engine.planning.stage.Stage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The control blackboard — Hayes-Roth's BB1 "control board" — paired 1:1 with each running case.
 * Holds the scheduling agenda, focus of attention, resource budget, stage tracking, milestone
 * lifecycle state, and extensible key-value state. Written to by {@link
 * io.casehub.engine.planning.control.PlanningStrategy}; read by {@link
 * io.casehub.engine.planning.control.PlanningStrategyLoopControl}.
 *
 * <p>Milestone lifecycle tracks PENDING → ACTIVE → COMPLETED via {@link MilestoneLifecycleStatus}.
 * See MilestoneLifecycleManager for the event-driven state machine.
 */
public interface CasePlanModel {

  UUID getCaseId();

  // Scheduling agenda
  void addPlanItem(PlanItem planItem);

  /**
   * Restores a PlanItem from persistent store into the live plan after a JVM restart. Adds the item
   * to the lookup indexes but NOT to the scheduling agenda — restored items are not pending
   * dispatch. See casehubio/engine#274.
   */
  void restorePlanItem(PlanItem item);

  /** Removes the plan item with the given planItemId. No-op if not found. */
  void removePlanItem(String planItemId);

  Optional<PlanItem> getPlanItem(String planItemId);

  /**
   * Returns the active PlanItem (PENDING, RUNNING, or DELEGATED) for the given binding name, or
   * empty if none exists. Used by handlers to locate the PlanItem before a state transition.
   */
  Optional<PlanItem> getPlanItemByBindingName(String bindingName);

  /**
   * Returns the most recently registered PlanItem for the given binding name regardless of status.
   * Used by planning strategies that need to see terminal states (e.g. COMPLETED) to make
   * sequencing decisions. Refs engine#621.
   */
  default Optional<PlanItem> findPlanItemByBindingName(String bindingName) {
    return Optional.empty();
  }

  /**
   * Returns true if there is already an active PlanItem (PENDING, RUNNING, or DELEGATED) for the
   * given binding name. Used to prevent duplicate scheduling.
   */
  boolean hasActivePlanItem(String bindingName);

  /**
   * Atomically adds the given PlanItem only if no PENDING or RUNNING item exists for the same
   * binding name. The check and insert are a single atomic operation via {@code
   * ConcurrentHashMap.compute()} — no TOCTOU window.
   *
   * @return true if the item was added; false if a duplicate was detected
   */
  boolean addPlanItemIfAbsent(PlanItem planItem);

  /** Returns only PENDING items, sorted highest-priority first. */
  List<PlanItem> getAgenda();

  List<PlanItem> getTopPlanItems(int maxCount);

  /** Returns all PlanItems regardless of status. Used by planning strategies. */
  List<PlanItem> getAllPlanItems();

  // Stage management
  void addStage(Stage stage);

  Optional<Stage> getStage(String stageId);

  List<Stage> getPendingStages();

  List<Stage> getActiveStages();

  List<Stage> getAllStages();

  // Milestone lifecycle (PENDING → ACTIVE → COMPLETED). See casehubio/engine#84.
  void trackMilestone(String milestoneName);

  void trackMilestone(String milestoneName, String parentStageId);

  void activateMilestone(String milestoneName);

  void completeMilestone(String milestoneName);

  Optional<MilestoneLifecycleStatus> getMilestoneStatus(String milestoneName);

  @Deprecated(forRemoval = true)
  void achieveMilestone(String milestoneName);

  boolean isMilestoneAchieved(String milestoneName);

  // Focus of attention (written by PlanningStrategy)
  void setFocus(String focusArea);

  Optional<String> getFocus();

  void setFocusRationale(String rationale);

  /** Returns the rationale for the current focus of attention, if set. */
  Optional<String> getFocusRationale();

  // Resource budget (written by PlanningStrategy)
  void setResourceBudget(Map<String, Object> budget);

  Map<String, Object> getResourceBudget();

  // Extensible key-value (custom PlanningStrategy state)
  void put(String key, Object value);

  <T> Optional<T> get(String key, Class<T> type);

  /** Registers a sub-case to be launched as part of this case's work. See casehubio/engine#195. */
  void addSubCase(SubCase subCase);

  List<SubCase> getSubCases();
}
