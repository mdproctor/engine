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
package io.casehub.api.model.evaluator;

import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.Objects;

/**
 * MVEL evaluator that carries the POJO context class for typed evaluation.
 *
 * <p>When a case definition declares {@code contextType}, MVEL expressions are created with the
 * resolved class so the engine can deserialize the working layer to the actual POJO before
 * evaluation — enabling nested property access ({@code transaction.amount > 1000}).
 */
public record TypedMvelExpressionEvaluator(String expression, Class<?> contextClass)
    implements ExpressionEvaluator {

  public TypedMvelExpressionEvaluator {
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(contextClass, "contextClass");
  }

  @Override
  public String type() {
    return "mvel";
  }
}
