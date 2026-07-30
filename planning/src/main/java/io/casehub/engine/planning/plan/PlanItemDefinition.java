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

public sealed interface PlanItemDefinition
    permits PlanItemDefinition.Primitive, PlanItemDefinition.Compound {

  String id();

  String name();

  record Primitive(
      String id,
      String name,
      io.casehub.api.model.ExecutorRef executor,
      io.casehub.platform.api.expression.ExpressionEvaluator entryCondition)
      implements PlanItemDefinition {
    public Primitive {
      java.util.Objects.requireNonNull(id, "id required");
      java.util.Objects.requireNonNull(name, "name required");
      java.util.Objects.requireNonNull(executor, "executor required");
    }
  }

    record Compound(
            String id,
            String name,
            java.util.List<PlanItemDefinition> children,
            String planningStrategy,
            CompletionSemantics completion,
            DispatchMode dispatchMode,
            io.casehub.platform.api.expression.ExpressionEvaluator entryCondition,
            io.casehub.platform.api.expression.ExpressionEvaluator exitCondition,
            boolean repeatable,
            java.util.Map<String, io.casehub.api.model.Participation> scopedBindings)
            implements PlanItemDefinition {
        public Compound {
            java.util.Objects.requireNonNull(id, "id required");
            java.util.Objects.requireNonNull(name, "name required");
            java.util.Objects.requireNonNull(dispatchMode, "dispatchMode required");
            java.util.Objects.requireNonNull(completion, "completion required");
            children       = children != null ? java.util.List.copyOf(children) : java.util.List.of();
            scopedBindings =
                    scopedBindings != null ? java.util.Map.copyOf(scopedBindings) : java.util.Map.of();
        }

        public static Builder builder(String name) {
            return new Builder(name);
        }

        public static final class Builder {
            private       String                                                              id             = java.util.UUID.randomUUID().toString();
            private final String                                                              name;
            private final java.util.ArrayList<PlanItemDefinition>                             children       = new java.util.ArrayList<>();
            private final java.util.LinkedHashMap<String, io.casehub.api.model.Participation> scopedBindings =
                    new java.util.LinkedHashMap<>();
            private       String                                                              planningStrategy;
            private       CompletionSemantics                                                 completion     = CompletionSemantics.all();
            private       DispatchMode                                                        dispatchMode   = DispatchMode.CHOREOGRAPHED;
            private       io.casehub.platform.api.expression.ExpressionEvaluator              entryCondition;
            private       io.casehub.platform.api.expression.ExpressionEvaluator              exitCondition;
            private       boolean                                                             repeatable;

            private Builder(String name) {
                this.name = java.util.Objects.requireNonNull(name, "name required");
            }

            public Builder id(String id) {
                this.id = java.util.Objects.requireNonNull(id, "id required");
                return this;
            }

            public Builder child(PlanItemDefinition child) {
                this.children.add(java.util.Objects.requireNonNull(child, "child required"));
                return this;
            }

            public Builder binding(String bindingName) {
                return binding(bindingName, io.casehub.api.model.Participation.PARTICIPANT);
            }

            public Builder binding(String bindingName, io.casehub.api.model.Participation participation) {
                java.util.Objects.requireNonNull(bindingName, "bindingName required");
                java.util.Objects.requireNonNull(participation, "participation required");
                this.scopedBindings.put(bindingName, participation);
                return this;
            }

            public Builder planningStrategy(String strategy) {
                this.planningStrategy = strategy;
                return this;
            }

            public Builder completion(CompletionSemantics completion) {
                this.completion = java.util.Objects.requireNonNull(completion, "completion required");
                return this;
            }

            public Builder dispatchMode(DispatchMode mode) {
                this.dispatchMode = java.util.Objects.requireNonNull(mode, "dispatchMode required");
                return this;
            }

            public Builder entryCondition(
                    io.casehub.platform.api.expression.ExpressionEvaluator condition) {
                this.entryCondition = condition;
                return this;
            }

            public Builder entryCondition(
                    java.util.function.Predicate<io.casehub.api.context.CaseContext> predicate) {
                this.entryCondition =
                        new io.casehub.api.model.evaluator.LambdaExpressionEvaluator(predicate);
                return this;
            }

            public Builder exitCondition(
                    io.casehub.platform.api.expression.ExpressionEvaluator condition) {
                this.exitCondition = condition;
                return this;
            }

            public Builder exitCondition(
                    java.util.function.Predicate<io.casehub.api.context.CaseContext> predicate) {
                this.exitCondition =
                        new io.casehub.api.model.evaluator.LambdaExpressionEvaluator(predicate);
                return this;
            }

            public Builder repeatable(boolean repeatable) {
                this.repeatable = repeatable;
                return this;
            }

            public Compound build() {
                return new Compound(
                        id,
                        name,
                        children,
                        planningStrategy,
                        completion,
                        dispatchMode,
                        entryCondition,
                        exitCondition,
                        repeatable,
                        scopedBindings);
            }
        }
    }
}
