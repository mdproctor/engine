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
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextPanel;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Versions;
import org.jboss.logging.Logger;

/** {@link ExpressionEngine} for JQ expressions. */
@ApplicationScoped
public class JQExpressionEngine implements ExpressionEngine {

  private static final Logger LOG = Logger.getLogger(JQExpressionEngine.class);

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
    final ValidationResult result =
        jqEvaluator.eval(expr, context.panel(ContextPanel.WORKING).asJsonNode());
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

  @Override
  public ExpressionEvaluator create(final String expression) {
    return new JQExpressionEvaluator(expression);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Evaluates the JQ expression against the WORKING panel. When JQ produces multiple output
   * elements, only the first is inspected — callers should use scalar expressions. Non-textual
   * first output (null, number, boolean, array, object) returns {@link Optional#empty()} silently.
   */
  @Override
  public Optional<String> extractString(
      final ExpressionEvaluator evaluator, final CaseContext context) {
    final String expr = ((JQExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return Optional.empty();
    }
    final ValidationResult vr =
        jqEvaluator.eval(expr, context.panel(ContextPanel.WORKING).asJsonNode());
    if (!vr.ok() || vr.output() == null || vr.output().isEmpty()) {
      if (!vr.ok()) {
        LOG.warnf("extractString JQ evaluation failed: %s", vr.error());
      }
      return Optional.empty();
    }
    final JsonNode result = vr.output().get(0);
    if (!result.isTextual()) {
      return Optional.empty();
    }
    return Optional.of(result.asText());
  }

  @Override
  public boolean supportsStringCreation() {
    return true;
  }

  @Override
  public List<JsonNode> transform(final ExpressionEvaluator evaluator, final JsonNode input) {
    final String expr = ((JQExpressionEvaluator) evaluator).expression();
    if (expr == null || expr.isBlank()) {
      return List.of(input);
    }
    final ValidationResult result = jqEvaluator.eval(expr, input);
    if (!result.ok()) {
      throw new IllegalArgumentException(
          "JQ transform failed for '" + expr + "': " + result.error());
    }
    return result.output() != null ? result.output() : List.of();
  }
}
