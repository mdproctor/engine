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
package io.casehub.api.model.routing;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public record ContextConstraint(ExpressionEvaluator condition, Effect effect, double weight) {

  public ContextConstraint {
    Objects.requireNonNull(condition, "condition must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    if (weight < 0.0 || weight > 1.0) {
      throw new IllegalArgumentException("weight must be in range [0.0, 1.0], got: " + weight);
    }
  }

  public sealed interface Effect permits Prefer, Exclude {}

  public record Prefer(Set<String> groups, Set<String> users) implements Effect {
    public Prefer {
      groups = groups != null ? Set.copyOf(groups) : Set.of();
      users = users != null ? Set.copyOf(users) : Set.of();
    }
  }

  public record Exclude(Set<String> groups, Set<String> users) implements Effect {
    public Exclude {
      groups = groups != null ? Set.copyOf(groups) : Set.of();
      users = users != null ? Set.copyOf(users) : Set.of();
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private ExpressionEvaluator condition;
    private Effect effect;
    private double weight = 1.0;

    private Builder() {}

    public Builder when(String jqExpression) {
      this.condition = new JQExpressionEvaluator(jqExpression);
      return this;
    }

    public Builder when(Predicate<CaseContext> predicate) {
      this.condition = new LambdaExpressionEvaluator(predicate);
      return this;
    }

    public Builder when(ExpressionEvaluator evaluator) {
      this.condition = evaluator;
      return this;
    }

    public Builder preferUsers(Set<String> users) {
      if (effect instanceof Prefer existing) {
        this.effect = new Prefer(existing.groups(), union(existing.users(), users));
      } else {
        this.effect = new Prefer(Set.of(), users);
      }
      return this;
    }

    public Builder preferGroups(Set<String> groups) {
      if (effect instanceof Prefer existing) {
        this.effect = new Prefer(union(existing.groups(), groups), existing.users());
      } else {
        this.effect = new Prefer(groups, Set.of());
      }
      return this;
    }

    public Builder prefer(Set<String> groups, Set<String> users) {
      this.effect = new Prefer(groups, users);
      return this;
    }

    public Builder excludeUsers(Set<String> users) {
      if (effect instanceof Exclude existing) {
        this.effect = new Exclude(existing.groups(), union(existing.users(), users));
      } else {
        this.effect = new Exclude(Set.of(), users);
      }
      return this;
    }

    public Builder excludeGroups(Set<String> groups) {
      if (effect instanceof Exclude existing) {
        this.effect = new Exclude(union(existing.groups(), groups), existing.users());
      } else {
        this.effect = new Exclude(groups, Set.of());
      }
      return this;
    }

    public Builder exclude(Set<String> groups, Set<String> users) {
      this.effect = new Exclude(groups, users);
      return this;
    }

    public Builder weight(double weight) {
      this.weight = weight;
      return this;
    }

    public ContextConstraint build() {
      if (condition == null) {
        throw new IllegalStateException("condition is required");
      }
      if (effect == null) {
        throw new IllegalStateException(
            "effect is required — call preferUsers(), preferGroups(), excludeUsers(), or excludeGroups()");
      }
      return new ContextConstraint(condition, effect, weight);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
      var result = new java.util.LinkedHashSet<>(a);
      result.addAll(b);
      return Set.copyOf(result);
    }
  }
}
