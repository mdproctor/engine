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
import io.casehub.api.model.TaskStatus;
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

  default void registerDefinition(PlanItemDefinition definition) {
    throw new UnsupportedOperationException("Compound PlanItem support not implemented");
  }

  default TaskStatus getDefinitionStatus(String planItemId) {
    throw new UnsupportedOperationException("Compound PlanItem support not implemented");
  }

  default boolean tryDefinitionTransition(String planItemId, TaskStatus from, TaskStatus to) {
    throw new UnsupportedOperationException("Compound PlanItem support not implemented");
  }

  default java.util.Set<String> getChildrenOf(String compoundId) {
    return java.util.Set.of();
  }

  default Optional<String> getParentOf(String planItemId) {
    return Optional.empty();
  }

  default void addChild(String compoundId, PlanItemDefinition child) {
    throw new UnsupportedOperationException("Compound PlanItem support not implemented");
  }

  default boolean evaluateCompletion(String compoundId) {
    return false;
  }

  default PlanItemDefinition getDefinition(String planItemId) {
    return null;
  }

  default java.util.List<PlanItemDefinition.Compound> getAllCompounds() {
    return java.util.List.of();
  }

  default java.util.List<PlanItemDefinition.Compound> getCompoundsByStatus(
      io.casehub.api.model.TaskStatus status) {
    return java.util.List.of();
  }

  default void replaceCompound(
      String compoundId, PlanItemDefinition.Compound newCompound, int newGeneration) {
    throw new UnsupportedOperationException("replaceCompound not supported");
  }

  default int getAdaptationGeneration(String compoundId) {
    return 0;
  }

  default void promoteToCompound(String bindingName, PlanItemDefinition.Compound newCompound) {
    throw new UnsupportedOperationException(
        "promoteToCompound not supported by this implementation");
  }

  default void faultCompound(String compoundId) {
    throw new UnsupportedOperationException("faultCompound not supported");
  }

  default boolean hasAnyFaultedParticipant(String compoundId) {
    return false;
  }
}
