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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The control blackboard — Hayes-Roth's BB1 "control board" — paired 1:1 with each running case.
 * Holds the scheduling agenda, focus of attention, resource budget, stage tracking, milestone
 * lifecycle state, and extensible key-value state. Written to by {@link
 * io.casehub.blackboard.control.PlanningStrategy}; read by {@link
 * io.casehub.blackboard.control.PlanningStrategyLoopControl}.
 *
 * <p>Milestone lifecycle tracking here is an interim approach. Full alignment of Milestone, Stage,
 * and Goal is tracked in casehubio/engine#84. See casehubio/engine#76.
 */
public interface CasePlanModel {

  UUID getCaseId();

  // Scheduling agenda
  void addPlanItem(PlanItem planItem);

  /** Removes the plan item with the given planItemId. No-op if not found. */
  void removePlanItem(String planItemId);

  Optional<PlanItem> getPlanItem(String planItemId);

  /**
   * Returns the most recently created PENDING or RUNNING PlanItem for the given binding name, or
   * empty if none exists. Used by HumanTaskScheduleHandler to locate the plan item created by the
   * planning loop before marking it RUNNING.
   */
  Optional<PlanItem> getPlanItemByBindingName(String bindingName);

  /**
   * Returns true if there is already a PENDING or RUNNING PlanItem for the given binding name. Used
   * to prevent duplicate scheduling.
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

  // Stage management
  void addStage(Stage stage);

  Optional<Stage> getStage(String stageId);

  List<Stage> getPendingStages();

  List<Stage> getActiveStages();

  List<Stage> getAllStages();

  // Milestone lifecycle (PENDING → ACHIEVED). See casehubio/engine#84.
  void trackMilestone(String milestoneName);

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
