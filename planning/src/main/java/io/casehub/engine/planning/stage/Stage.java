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
package io.casehub.engine.planning.stage;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * CMMN Stage — a container for PlanItems, Milestones, and nested Stages that activates and
 * completes as a unit. Entry/exit conditions use {@link ExpressionEvaluator} (JQ or Lambda). See
 * casehubio/engine#76.
 *
 * <p>Milestone containment ({@code containedMilestoneIds}) is present but Stage exit criteria
 * referencing milestone achievement requires the full alignment in casehubio/engine#84. For PR4,
 * exit conditions use expression evaluation against {@code CaseContext} only.
 *
 * <p>See CMMN 1.1 §5.4.4. Full audit: casehubio/engine#84.
 */
public class Stage {

  private final String stageId;
  private final String name;
  private final AtomicReference<StageStatus> status = new AtomicReference<>(StageStatus.PENDING);
  private final Instant createdAt;
  private volatile Instant activatedAt;
  private volatile Instant completedAt;
  private String parentStageId; // null means root stage

  // Conditions — null means "no condition"
  private ExpressionEvaluator entryCondition;
  private ExpressionEvaluator exitCondition;

  // Containment — ConcurrentHashMap.newKeySet() for atomic add-if-absent deduplication
  private final Set<String> containedPlanItemIds = ConcurrentHashMap.newKeySet();
  private final Set<String> containedMilestoneIds =
      ConcurrentHashMap.newKeySet(); // casehubio/engine#84
  private final Set<String> containedStageIds = ConcurrentHashMap.newKeySet();
  private final Set<String> requiredItemIds = ConcurrentHashMap.newKeySet();

  // Design-time binding declarations — see ADR-0002 and casehubio/engine#76.
  // Presence of a binding name here means this Stage "owns" that binding: the binding will only
  // be passed to PlanningStrategy when this Stage is ACTIVE.  Stages with no declarations are
  // lifecycle-only and impose no gating.
  private final Set<String> containedBindingNames = ConcurrentHashMap.newKeySet();

  // Behaviour
  private boolean autocomplete = true;
  private boolean manualActivation = false;
  private final boolean repeatable;
  private final AtomicInteger instanceIndex = new AtomicInteger(0);

  private Stage(String name, boolean repeatable) {
    this.stageId = UUID.randomUUID().toString();
    this.name = name;
    this.createdAt = Instant.now();
    this.repeatable = repeatable;
  }

  private Stage(String name) {
    this(name, false);
  }

  /**
   * Creates a Stage with null entry condition (activates on every cycle).
   *
   * @deprecated Prefer {@link #alwaysActivate(String)} for explicit intent, or {@link
   *     #builder(String)} when an entry condition is required.
   */
  @Deprecated(since = "1.0", forRemoval = false)
  public static Stage create(String name) {
    return new Stage(name);
  }

  /**
   * Creates a Stage that activates on every evaluation cycle (null entry condition). Prefer this
   * over {@link #create(String)} — the name makes the intent explicit.
   */
  public static Stage alwaysActivate(String name) {
    return new Stage(name);
  }

  /**
   * Returns a Builder requiring an explicit entry condition. Use {@link #alwaysActivate(String)}
   * for always-on stages.
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /** Builder requiring an explicit entry condition. */
  public static final class Builder {
    private final String name;
    private ExpressionEvaluator entryCondition;
    private ExpressionEvaluator exitCondition;
    private boolean manualActivation = false;
    private boolean autocomplete = true;
    private boolean repeatable = false;
    private String parentStageId;
    private final java.util.Set<String> bindingNames = new java.util.HashSet<>();

    private Builder(String name) {
      this.name = java.util.Objects.requireNonNull(name, "name must not be null");
    }

    public Builder entryCondition(ExpressionEvaluator condition) {
      this.entryCondition = condition;
      return this;
    }

    public Builder entryCondition(Predicate<CaseContext> predicate) {
      this.entryCondition = new LambdaExpressionEvaluator(predicate);
      return this;
    }

    public Builder exitCondition(ExpressionEvaluator condition) {
      this.exitCondition = condition;
      return this;
    }

    public Builder exitCondition(Predicate<CaseContext> predicate) {
      this.exitCondition = new LambdaExpressionEvaluator(predicate);
      return this;
    }

    public Builder withManualActivation(boolean v) {
      this.manualActivation = v;
      return this;
    }

    public Builder withAutocomplete(boolean v) {
      this.autocomplete = v;
      return this;
    }

    public Builder withParentStage(String parentStageId) {
      this.parentStageId = parentStageId;
      return this;
    }

    /**
     * Declares a design-time binding name for this Stage (ADR-0002). The binding will only pass
     * through loop control when this Stage is ACTIVE. Multiple calls accumulate; no duplicates.
     */
    public Builder binding(String bindingName) {
      java.util.Objects.requireNonNull(bindingName, "bindingName must not be null");
      this.bindingNames.add(bindingName);
      return this;
    }

    public Builder repeatable(boolean repeatable) {
      this.repeatable = repeatable;
      return this;
    }

    public Stage build() {
      if (entryCondition == null) {
        throw new IllegalStateException(
            "Stage.builder(\""
                + name
                + "\") requires entryCondition to be set. "
                + "Use Stage.alwaysActivate(\""
                + name
                + "\") if the stage should activate on every cycle.");
      }
      Stage stage = new Stage(name, this.repeatable);
      stage.entryCondition = this.entryCondition;
      stage.exitCondition = this.exitCondition;
      stage.manualActivation = this.manualActivation;
      stage.autocomplete = this.autocomplete;
      if (this.parentStageId != null) stage.parentStageId = this.parentStageId;
      stage.containedBindingNames.addAll(this.bindingNames);
      return stage;
    }
  }

  // Fluent builder-style configuration (called before adding to CasePlanModel)
  public Stage withEntryCondition(ExpressionEvaluator condition) {
    this.entryCondition = condition;
    return this;
  }

  /**
   * Convenience overload — wraps a Java lambda in {@link LambdaExpressionEvaluator}. Use this for
   * Java DSL stage definitions where type safety is preferred over JQ string expressions.
   */
  public Stage withEntryCondition(Predicate<CaseContext> predicate) {
    this.entryCondition = new LambdaExpressionEvaluator(predicate);
    return this;
  }

  public Stage withExitCondition(ExpressionEvaluator condition) {
    this.exitCondition = condition;
    return this;
  }

  /**
   * Convenience overload — wraps a Java lambda in {@link LambdaExpressionEvaluator}. Use this for
   * Java DSL stage definitions where type safety is preferred over JQ string expressions.
   */
  public Stage withExitCondition(Predicate<CaseContext> predicate) {
    this.exitCondition = new LambdaExpressionEvaluator(predicate);
    return this;
  }

  public Stage withManualActivation(boolean manual) {
    this.manualActivation = manual;
    return this;
  }

  public Stage withAutocomplete(boolean autocomplete) {
    this.autocomplete = autocomplete;
    return this;
  }

  public Stage withParentStage(String parentStageId) {
    this.parentStageId = parentStageId;
    return this;
  }

  // Lifecycle transitions — all use CAS so concurrent callers race atomically; exactly one wins
  public void activate() {
    if (status.compareAndSet(StageStatus.PENDING, StageStatus.ACTIVE)) {
      activatedAt = Instant.now();
    }
  }

  public void complete() {
    StageStatus current;
    do {
      current = status.get();
      if (current != StageStatus.ACTIVE && current != StageStatus.SUSPENDED) return;
    } while (!status.compareAndSet(current, StageStatus.COMPLETED));
    completedAt = Instant.now();
  }

  public void terminate() {
    StageStatus current;
    do {
      current = status.get();
      if (current != StageStatus.ACTIVE && current != StageStatus.SUSPENDED) return;
    } while (!status.compareAndSet(current, StageStatus.TERMINATED));
    completedAt = Instant.now();
  }

  public void suspend() {
    status.compareAndSet(StageStatus.ACTIVE, StageStatus.SUSPENDED);
  }

  public void resume() {
    status.compareAndSet(StageStatus.SUSPENDED, StageStatus.ACTIVE);
  }

  /**
   * Transitions this stage to FAULTED due to an unrecoverable error. No-op if the stage is already
   * in a terminal state (COMPLETED, TERMINATED, FAULTED). Unlike hardware faults which can
   * interrupt any state, this guard prevents overwriting a cleanly completed stage with a fault
   * that arrived out of order.
   */
  public void fault() {
    StageStatus current;
    do {
      current = status.get();
      if (isTerminalStatus(current)) return;
    } while (!status.compareAndSet(current, StageStatus.FAULTED));
    completedAt = Instant.now();
  }

  public boolean isTerminal() {
    return isTerminalStatus(status.get());
  }

  public boolean isActive() {
    return status.get() == StageStatus.ACTIVE;
  }

  private static boolean isTerminalStatus(StageStatus s) {
    return s == StageStatus.COMPLETED || s == StageStatus.TERMINATED || s == StageStatus.FAULTED;
  }

  // Containment mutations — sets handle deduplication atomically; no contains-before-add guard
  // needed
  public void addPlanItem(String planItemId) {
    containedPlanItemIds.add(planItemId);
  }

  public void addMilestone(String milestoneName) {
    containedMilestoneIds.add(milestoneName);
  }

  public void addNestedStage(String stageId) {
    containedStageIds.add(stageId);
  }

  /**
   * Marks {@code itemId} as required for Stage autocomplete evaluation.
   *
   * <p><strong>Constraint:</strong> All required items must be registered BEFORE their
   * corresponding bindings fire. If a binding fires and its worker completes before this method is
   * called, the autocomplete event will be missed and the Stage will remain ACTIVE indefinitely.
   * Register required items during case setup, before calling {@code CaseHubRuntime.startCase()}.
   */
  public void addRequiredItem(String itemId) {
    requiredItemIds.add(itemId);
  }

  /**
   * Declares that this Stage "owns" the given binding name at design time (ADR-0002). The binding
   * will only pass through {@link io.casehub.engine.planning.control.PlanningStrategyLoopControl}
   * when this Stage is ACTIVE. Stages with no binding declarations impose no gating
   * (lifecycle-only).
   *
   * @param bindingName the binding name to gate; must not be null
   */
  public void addBinding(String bindingName) {
    java.util.Objects.requireNonNull(bindingName, "bindingName must not be null");
    containedBindingNames.add(bindingName);
  }

  /**
   * Fluent alias for {@link #addBinding(String)} — returns {@code this} for chaining.
   *
   * <pre>{@code
   * Stage.alwaysActivate("intake")
   *     .withBinding("trigger-on-docs")
   *     .withBinding("trigger-on-go");
   * }</pre>
   */
  public Stage withBinding(String bindingName) {
    addBinding(bindingName);
    return this;
  }

  // Getters
  public String getStageId() {
    return stageId;
  }

  public String getName() {
    return name;
  }

  public StageStatus getStatus() {
    return status.get();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getActivatedAt() {
    return activatedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Optional<String> getParentStageId() {
    return Optional.ofNullable(parentStageId);
  }

  public ExpressionEvaluator getEntryCondition() {
    return entryCondition;
  }

  public ExpressionEvaluator getExitCondition() {
    return exitCondition;
  }

  public List<String> getContainedPlanItemIds() {
    return List.copyOf(containedPlanItemIds); // snapshot of the concurrent set
  }

  public List<String> getContainedMilestoneIds() {
    return List.copyOf(containedMilestoneIds); // snapshot of the concurrent set
  }

  public List<String> getContainedStageIds() {
    return List.copyOf(containedStageIds); // snapshot of the concurrent set
  }

  public List<String> getRequiredItemIds() {
    return List.copyOf(requiredItemIds); // snapshot of the concurrent set
  }

  /**
   * Returns an immutable snapshot of the design-time binding names declared for this Stage. Empty
   * means the Stage is lifecycle-only (no binding gating). See ADR-0002.
   */
  public Set<String> getContainedBindingNames() {
    return Set.copyOf(containedBindingNames);
  }

  public boolean isAutocomplete() {
    return autocomplete;
  }

  public boolean isManualActivation() {
    return manualActivation;
  }

  public boolean isRepeatable() {
    return repeatable;
  }

  public int getInstanceIndex() {
    return instanceIndex.get();
  }

  /**
   * Resets a repeatable Stage from COMPLETED to PENDING for the next instance. Clears all
   * containment sets (PlanItems, milestones) so the next instance starts fresh. Binding names are
   * preserved — they are design-time declarations, not instance state.
   *
   * <p>Refs casehubio/engine#482.
   *
   * @return true if reset succeeded; false if the stage is not repeatable or not COMPLETED
   */
  public boolean resetForRepetition() {
    if (!repeatable) return false;
    if (status.compareAndSet(StageStatus.COMPLETED, StageStatus.PENDING)) {
      instanceIndex.incrementAndGet();
      containedPlanItemIds.clear();
      requiredItemIds.clear();
      containedMilestoneIds.clear();
      activatedAt = null;
      completedAt = null;
      return true;
    }
    return false;
  }
}
