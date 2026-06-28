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
package io.casehub.api.model;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A named waypoint that a case passes through on its way to a {@link Goal}.
 *
 * <p>Milestones and goals answer different questions:
 *
 * <ul>
 *   <li><b>Milestones</b> — where are we? A milestone marks a point of progress. It has no
 *       success/failure polarity; it is a neutral checkpoint that the case either has or has not
 *       reached. You <em>pass</em> milestones.
 *   <li><b>Goals</b> — what outcome are we trying to achieve? A goal carries {@link GoalKind}
 *       (SUCCESS or FAILURE) and drives case completion. You <em>achieve</em> goals.
 * </ul>
 *
 * <p>Example — loan application case:
 *
 * <pre>{@code
 * // Milestones: intermediate waypoints
 * Milestone.builder().name("documents-received").completionCriteria(".docsUploaded == true").build()
 * Milestone.builder().name("credit-check-complete").completionCriteria(".creditScore != null").build()
 * Milestone.builder().name("underwriting-done").completionCriteria(".underwritingStatus == \"complete\"").build()
 *
 * // Goals: terminal outcomes
 * Goal.builder().name("loan-approved").condition(".decision == \"approved\"").kind(GoalKind.SUCCESS).build()
 * Goal.builder().name("loan-rejected").condition(".decision == \"rejected\"").kind(GoalKind.FAILURE).build()
 * }</pre>
 *
 * <h3>Lifecycle States</h3>
 *
 * <p>Milestones progress through lifecycle states tracked via {@link MilestoneLifecycleStatus}:
 *
 * <ul>
 *   <li><b>PENDING</b> — waiting for {@code entryCriteria} to become true
 *   <li><b>ACTIVE</b> — {@code entryCriteria} met, working toward {@code completionCriteria}
 *   <li><b>COMPLETED</b> — {@code completionCriteria} met
 * </ul>
 *
 * <h3>SLA Tracking</h3>
 *
 * <p>Optional {@code slaDuration} enables SLA deadline tracking via {@link SlaStatus}:
 *
 * <ul>
 *   <li><b>NOT_STARTED</b> — milestone not yet activated (PENDING)
 *   <li><b>ON_TRACK</b> — activated, within SLA deadline
 *   <li><b>BREACHED</b> — SLA deadline passed
 * </ul>
 *
 * <p>SLA status is orthogonal to lifecycle: a milestone can be COMPLETED + BREACHED (late
 * completion).
 *
 * <h3>Lightweight use</h3>
 *
 * <p>By default, when the completionCriteria becomes true, a {@code MilestoneReachedEvent} is
 * published on the event bus and recorded in the {@link
 * io.casehub.engine.internal.history.EventLog} as {@code MILESTONE_REACHED}. No further tracking
 * occurs. This is sufficient for observability, dashboards, and audit trails.
 *
 * <h3>Lifecycle use (Phase 2 — with {@code CasePlanModel})</h3>
 *
 * <p>When a {@code CasePlanModel} is present (the CMMN/Blackboard layer), milestones are promoted
 * to lifecycle-tracked achievement markers with PENDING → ACHIEVED states. The {@code
 * MilestoneReachedEvent} triggers the PENDING → ACHIEVED transition, and the achieved state can be
 * referenced in stage exit criteria and case completion logic. The {@code Milestone} class itself
 * does not change — the lifecycle tracking is a {@code CasePlanModel} concern.
 */
public class Milestone {

  private static final ExpressionEvaluator DEFAULT_ENTRY_CRITERIA =
      new LambdaExpressionEvaluator(ctx -> true);

  private final String name;
  private final ExpressionEvaluator entryCriteria;
  private final ExpressionEvaluator completionCriteria;
  private final Duration slaDuration;
  private final SlaStartFrom slaStartFrom;
  private final String parentStageId;
  private String description;

  public Milestone(
      String name,
      ExpressionEvaluator entryCriteria,
      ExpressionEvaluator completionCriteria,
      Duration slaDuration,
      SlaStartFrom slaStartFrom,
      String parentStageId) {
    this.name = name;
    this.entryCriteria = entryCriteria;
    this.completionCriteria = completionCriteria;
    this.slaDuration = slaDuration;
    this.slaStartFrom = slaStartFrom;
    this.parentStageId = parentStageId;
  }

  public String getName() {
    return name;
  }

  public ExpressionEvaluator getEntryCriteria() {
    return entryCriteria;
  }

  public ExpressionEvaluator getCompletionCriteria() {
    return completionCriteria;
  }

  public Duration getSlaDuration() {
    return slaDuration;
  }

  public SlaStartFrom getSlaStartFrom() {
    return slaStartFrom;
  }

  public String getParentStageId() {
    return parentStageId;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String name;
    private ExpressionEvaluator entryCriteria;
    private ExpressionEvaluator completionCriteria;
    private Duration slaDuration;
    private SlaStartFrom slaStartFrom = SlaStartFrom.MILESTONE_ACTIVATED;
    private String parentStageId;
    private String description;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder entryCriteria(ExpressionEvaluator entryCriteria) {
      this.entryCriteria = entryCriteria;
      return this;
    }

    public Builder entryCriteria(String entryCriteria) {
      this.entryCriteria =
          new JQExpressionEvaluator(
              Objects.requireNonNull(entryCriteria, "entryCriteria must not be null"));
      return this;
    }

    public Builder entryCriteria(Predicate<CaseContext> predicate) {
      this.entryCriteria =
          new LambdaExpressionEvaluator(
              Objects.requireNonNull(predicate, "entryCriteria must not be null"));
      return this;
    }

    public Builder completionCriteria(ExpressionEvaluator completionCriteria) {
      this.completionCriteria = completionCriteria;
      return this;
    }

    public Builder completionCriteria(String completionCriteria) {
      this.completionCriteria =
          new JQExpressionEvaluator(
              Objects.requireNonNull(completionCriteria, "completionCriteria must not be null"));
      return this;
    }

    public Builder completionCriteria(Predicate<CaseContext> predicate) {
      this.completionCriteria =
          new LambdaExpressionEvaluator(
              Objects.requireNonNull(predicate, "completionCriteria must not be null"));
      return this;
    }

    public Builder slaDuration(Duration slaDuration) {
      if (slaDuration != null && (slaDuration.isNegative() || slaDuration.isZero())) {
        throw new IllegalArgumentException("slaDuration must be positive");
      }
      this.slaDuration = slaDuration;
      return this;
    }

    public Builder slaStartFrom(SlaStartFrom slaStartFrom) {
      this.slaStartFrom = slaStartFrom;
      return this;
    }

    public Builder parentStageId(String parentStageId) {
      this.parentStageId = parentStageId;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Milestone build() {
      // Default entryCriteria to always-true if not set
      ExpressionEvaluator finalEntryCriteria = entryCriteria;
      if (finalEntryCriteria == null) {
        finalEntryCriteria = DEFAULT_ENTRY_CRITERIA;
      }

      Milestone milestone =
          new Milestone(
              Objects.requireNonNull(name, "name must not be null"),
              finalEntryCriteria,
              Objects.requireNonNull(completionCriteria, "completionCriteria must not be null"),
              slaDuration,
              slaStartFrom,
              parentStageId);
      milestone.setDescription(description);
      return milestone;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Milestone milestone)) return false;
    return Objects.equals(name, milestone.name)
        && Objects.equals(entryCriteria, milestone.entryCriteria)
        && Objects.equals(completionCriteria, milestone.completionCriteria)
        && Objects.equals(slaDuration, milestone.slaDuration)
        && slaStartFrom == milestone.slaStartFrom
        && Objects.equals(parentStageId, milestone.parentStageId)
        && Objects.equals(description, milestone.description);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        name,
        entryCriteria,
        completionCriteria,
        slaDuration,
        slaStartFrom,
        parentStageId,
        description);
  }
}
