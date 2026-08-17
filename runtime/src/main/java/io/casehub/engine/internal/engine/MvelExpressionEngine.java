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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MvelExpressionEngine implements ExpressionEngine {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final io.casehub.platform.expression.MvelExpressionEngine platformMvel;

  @Inject
  MvelExpressionEngine(io.casehub.platform.expression.MvelExpressionEngine platformMvel) {
    this.platformMvel = platformMvel;
  }

  MvelExpressionEngine() {
    this.platformMvel = new io.casehub.platform.expression.MvelExpressionEngine();
  }

  @Override
  public String type() {
    return "mvel";
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean evaluate(final ExpressionEvaluator evaluator, final CaseContext context) {
    if (evaluator == null) {
      return true;
    }
    final String expr = ((MvelExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return true;
    }

    final JsonNode workingJson = context.layer(ContextLayer.WORKING).asJsonNode();
    final Map<String, Object> contextMap = MAPPER.convertValue(workingJson, Map.class);

    final CompiledExpression<Map<String, Object>, Boolean> compiled =
        platformMvel.compile(
            expr, (Class<Map<String, Object>>) (Class<?>) Map.class, Boolean.class);
    final Boolean result = compiled.eval(contextMap);
    return result != null && result;
  }

  @Override
  public void validate(final ExpressionEvaluator evaluator) {
    if (evaluator == null) {
      return;
    }
    final String expr = ((MvelExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return;
    }
    platformMvel.validate(expr);
  }

  @Override
  public ExpressionEvaluator create(final String expression) {
    return new MvelExpressionEvaluator(expression);
  }

  @Override
  public boolean supportsStringCreation() {
    return true;
  }

  @Override
  public Optional<String> extractString(
      final ExpressionEvaluator evaluator, final CaseContext context) {
    if (evaluator == null) {
      return Optional.empty();
    }
    final String expr = ((MvelExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return Optional.empty();
    }

    final JsonNode workingJson = context.layer(ContextLayer.WORKING).asJsonNode();
    @SuppressWarnings("unchecked")
    final Map<String, Object> contextMap = MAPPER.convertValue(workingJson, Map.class);

    final CompiledExpression<Map<String, Object>, Object> compiled =
        platformMvel.compile(expr, (Class<Map<String, Object>>) (Class<?>) Map.class, Object.class);
    final Object result = compiled.eval(contextMap);
    return result != null ? Optional.of(result.toString()) : Optional.empty();
  }

  @Override
  public <C, R> CompiledExpression<C, R> compile(
      final String expression, final Class<C> contextType, final Class<R> resultType) {
    return platformMvel.compile(expression, contextType, resultType);
  }

  @Override
  public <C, R> CompiledExpression<C, R> compile(
      final String expression,
      final Class<C> contextType,
      final Class<R> resultType,
      final Map<String, Object> variables) {
    return platformMvel.compile(expression, contextType, resultType, variables);
  }

  @Override
  public void validate(final String expression) {
    platformMvel.validate(expression);
  }
}
