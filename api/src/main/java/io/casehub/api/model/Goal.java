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
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A named outcome that a case is trying to achieve, with success or failure polarity.
 *
 * <p>Milestones and goals answer different questions:
 *
 * <ul>
 *   <li><b>Goals</b> — what outcome are we trying to achieve? A goal carries a kind label (e.g.
 *       "success", "failure", or domain-specific) and drives case completion via {@link
 *       io.casehub.api.model.GoalBasedCompletion}. You <em>achieve</em> goals.
 *   <li><b>Milestones</b> — where are we? A milestone marks a neutral point of progress on the way
 *       to a goal. It has no success/failure polarity. You <em>pass</em> milestones.
 * </ul>
 *
 * <p>Example — loan application case:
 *
 * <pre>{@code
 * // Milestones: intermediate waypoints (no polarity)
 * Milestone.builder().name("documents-received").completionCriteria(".docsUploaded == true").build()
 * Milestone.builder().name("credit-check-complete").completionCriteria(".creditScore != null").build()
 *
 * // Goals: terminal outcomes (SUCCESS or FAILURE)
 * Goal.builder().name("loan-approved").condition(".decision == \"approved\"").kind(GoalKind.SUCCESS).build()
 * Goal.builder().name("loan-rejected").condition(".decision == \"rejected\"").kind(GoalKind.FAILURE).build()
 * }</pre>
 *
 * <p>When a goal's condition becomes true, a {@code GoalReachedEvent} is published and recorded in
 * the {@link io.casehub.engine.internal.history.EventLog}. If the goal is referenced by a {@link
 * io.casehub.api.model.GoalBasedCompletion}, the engine evaluates whether the case should
 * transition to COMPLETED or FAILED. Goals are always terminal — use {@link Milestone} for
 * non-terminal checkpoints.
 */
public class Goal {

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Unique goal name within the definition.")
  private final String name;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "JQ predicate over the CaseContext — goal is reached when this evaluates to true.")
  private final ExpressionEvaluator condition;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription(
      "Goal kind label — built-in: \"success\", \"failure\". Custom kinds require explicit status.")
  private final String kind;

  @com.fasterxml.jackson.annotation.JsonPropertyDescription("Human-readable goal description.")
  private String description;

  public Goal(String name, ExpressionEvaluator condition, String kind) {
    this.name = name;
    this.condition = condition;
    this.kind = kind;
  }

  public String getName() {
    return name;
  }

  public ExpressionEvaluator getCondition() {
    return condition;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getKind() {
    return kind;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String name;
    private ExpressionEvaluator condition;
    private String kind;
    private String description;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder condition(ExpressionEvaluator condition) {
      this.condition = condition;
      return this;
    }

    public Builder condition(String condition) {
      this.condition =
          new JQExpressionEvaluator(
              Objects.requireNonNull(condition, "condition must not be null"));
      return this;
    }

    public Builder condition(Predicate<CaseContext> predicate) {
      this.condition =
          new LambdaExpressionEvaluator(
              Objects.requireNonNull(predicate, "condition must not be null"));
      return this;
    }

    public Builder kind(String kind) {
      this.kind = kind;
      return this;
    }

    public Builder kind(GoalKind kind) {
      return kind(kind.value());
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Goal build() {
      Goal goal =
          new Goal(
              Objects.requireNonNull(name),
              Objects.requireNonNull(condition),
              Objects.requireNonNull(kind));
      goal.setDescription(description);
      return goal;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Goal goal)) return false;
    return Objects.equals(name, goal.name)
        && Objects.equals(condition, goal.condition)
        && Objects.equals(kind, goal.kind)
        && Objects.equals(description, goal.description);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, condition, kind, description);
  }
}
