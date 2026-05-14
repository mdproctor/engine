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

import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Binding target that routes to a human task in casehub-work.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #template(String)} — references a reusable {@code WorkItemTemplate} by ID
 *   <li>{@link #inline()} — self-contained one-off task definition
 * </ul>
 *
 * <p>Both modes support {@code inputMapping} (context → task payload) and {@code outputMapping}
 * (task resolution → context update). Mapping strings are treated as JQ expressions.
 */
public final class HumanTaskTarget implements BindingTarget {

  private final String templateRef;
  private final String title;
  private final Set<String> candidateGroups;
  private final Set<String> candidateUsers;
  private final Duration expiresIn;
  private final String priority;
  private final ExpressionEvaluator inputMapping;
  private final ExpressionEvaluator outputMapping;

  private HumanTaskTarget(Builder builder) {
    this.templateRef = builder.templateRef;
    this.title = builder.title;
    this.candidateGroups = builder.candidateGroups;
    this.candidateUsers = builder.candidateUsers;
    this.expiresIn = builder.expiresIn;
    this.priority = builder.priority;
    this.inputMapping = builder.inputMapping;
    this.outputMapping = builder.outputMapping;
  }

  /**
   * Entry point for template mode — references a {@code WorkItemTemplate} in casehub-work by ref
   * (UUID or name, resolved by {@code HumanTaskScheduleHandler}).
   */
  public static Builder template(String templateRef) {
    Objects.requireNonNull(templateRef, "templateRef must not be null");
    if (templateRef.isBlank()) throw new IllegalArgumentException("templateRef must not be blank");
    return new Builder(templateRef);
  }

  /** Entry point for inline mode — task is fully self-contained, no template lookup required. */
  public static Builder inline() {
    return new Builder(null);
  }

  public boolean isTemplateMode() {
    return templateRef != null;
  }

  public String templateRef() {
    return templateRef;
  }

  public String title() {
    return title;
  }

  public Set<String> candidateGroups() {
    return candidateGroups;
  }

  public Set<String> candidateUsers() {
    return candidateUsers;
  }

  public Duration expiresIn() {
    return expiresIn;
  }

  public String priority() {
    return priority;
  }

  public ExpressionEvaluator inputMapping() {
    return inputMapping;
  }

  public ExpressionEvaluator outputMapping() {
    return outputMapping;
  }

  public static final class Builder {

    private final String templateRef;
    private String title;
    private Set<String> candidateGroups;
    private Set<String> candidateUsers;
    private Duration expiresIn;
    private String priority;
    private ExpressionEvaluator inputMapping;
    private ExpressionEvaluator outputMapping;

    private Builder(String templateRef) {
      this.templateRef = templateRef;
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder candidateGroups(Set<String> candidateGroups) {
      this.candidateGroups = candidateGroups;
      return this;
    }

    public Builder candidateUsers(Set<String> candidateUsers) {
      this.candidateUsers = candidateUsers;
      return this;
    }

    public Builder expiresIn(Duration expiresIn) {
      this.expiresIn = expiresIn;
      return this;
    }

    public Builder priority(String priority) {
      this.priority = priority;
      return this;
    }

    public Builder inputMapping(String jqExpression) {
      this.inputMapping = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder inputMapping(ExpressionEvaluator evaluator) {
      this.inputMapping = evaluator;
      return this;
    }

    public Builder outputMapping(String jqExpression) {
      this.outputMapping = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder outputMapping(ExpressionEvaluator evaluator) {
      this.outputMapping = evaluator;
      return this;
    }

    public HumanTaskTarget build() {
      if (templateRef == null && (title == null || title.isBlank())) {
        throw new IllegalStateException("Inline HumanTaskTarget requires a non-blank title");
      }
      return new HumanTaskTarget(this);
    }
  }
}
