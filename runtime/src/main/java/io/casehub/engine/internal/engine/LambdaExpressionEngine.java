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
package io.casehub.engine.internal.engine;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;

/** {@link ExpressionEngine} for Java lambda expressions. */
@ApplicationScoped
public class LambdaExpressionEngine implements ExpressionEngine {

  @Override
  public String type() {
    return LambdaExpressionEvaluator.TYPE;
  }

  @Override
  public boolean evaluate(final ExpressionEvaluator evaluator, final CaseContext context) {
    return ((LambdaExpressionEvaluator) evaluator).test(context);
  }

  @Override
  public void validate(final ExpressionEvaluator evaluator) {
    // Lambda predicates are validated by the Java compiler at the call site;
    // nothing further to check at registration time.
  }
}
