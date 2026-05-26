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
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Versions;

/** {@link ExpressionEngine} for JQ expressions. */
@ApplicationScoped
public class JQExpressionEngine implements ExpressionEngine {

  @Inject JQEvaluator jqEvaluator;

  @Override
  public String type() {
    return JQExpressionEvaluator.TYPE;
  }

  @Override
  public boolean evaluate(final ExpressionEvaluator evaluator, final CaseContext context) {
    final String expr = ((JQExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return true;
    }
    final ValidationResult result = jqEvaluator.eval(expr, context.asJsonNode());
    return result.ok() && result.isTrue();
  }

  @Override
  public void validate(final ExpressionEvaluator evaluator) {
    final String expr = ((JQExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return;
    }
    try {
      JsonQuery.compile(expr, Versions.JQ_1_6);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Invalid JQ expression '" + expr + "': " + e.getMessage(), e);
    }
  }
}
