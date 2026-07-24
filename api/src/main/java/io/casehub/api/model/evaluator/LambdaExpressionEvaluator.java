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

import io.casehub.api.context.CaseContext;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.LambdaExpression;
import java.util.function.Predicate;

/**
 * An {@link ExpressionEvaluator} backed by a Java lambda. Thin subclass of platform's {@link
 * LambdaExpression} that preserves the {@link Predicate}-based constructor for Java DSL users.
 *
 * <p>Not serialisable — use {@link JQExpressionEvaluator} for YAML-defined cases.
 */
public final class LambdaExpressionEvaluator extends LambdaExpression<CaseContext, Boolean>
    implements ExpressionEvaluator {

  public static final String TYPE = "lambda";

  public LambdaExpressionEvaluator(final Predicate<CaseContext> predicate) {
    super(predicate::test);
  }

  @Override
  public String type() {
    return TYPE;
  }

  public boolean test(final CaseContext context) {
    return eval(context);
  }
}
