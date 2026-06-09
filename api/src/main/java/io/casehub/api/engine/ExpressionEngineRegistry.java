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
package io.casehub.api.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.ExpressionEvaluator;

/**
 * Registry for expression engines.
 *
 * <p>Dispatches expression evaluation to the appropriate {@link
 * io.casehub.api.engine.ExpressionEngine} by evaluator type. All CDI beans implementing {@link
 * io.casehub.api.engine.ExpressionEngine} are discovered automatically.
 *
 * @see io.casehub.api.engine.ExpressionEngine
 * @see ExpressionEvaluator
 */
public interface ExpressionEngineRegistry {

  /**
   * Evaluates the expression against the given context.
   *
   * @param evaluator the expression to evaluate; returns {@code true} if {@code null}
   * @param context the current case state
   * @return {@code true} if the expression matches or is absent
   * @throws IllegalArgumentException if no engine is registered for the evaluator type
   */
  boolean evaluate(ExpressionEvaluator evaluator, CaseContext context);

  /**
   * Evaluates the expression against a JSON node.
   *
   * @param evaluator the expression to evaluate
   * @param asNode the JSON node to evaluate against
   * @return {@code true} if the expression matches
   * @throws IllegalArgumentException if no engine is registered for the evaluator type
   */
  boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode);

  /**
   * Validates the expression syntax without evaluating it against any context.
   *
   * <p>Blocks case definition registration if the expression is invalid.
   *
   * @param evaluator the expression to validate; no-op if {@code null}
   * @throws IllegalArgumentException if the expression is syntactically invalid or no engine is
   *     registered for the evaluator type
   */
  void validate(ExpressionEvaluator evaluator);

  /**
   * Creates an {@link ExpressionEvaluator} for the given expression language by dispatching to the
   * {@link io.casehub.api.engine.ExpressionEngine} whose {@code type()} equals {@code
   * expressionLang}.
   *
   * <p>The returned evaluator's {@code type()} is asserted to equal {@code expressionLang} — a
   * contract violation by the engine's {@code create()} is caught immediately.
   *
   * @param expression the raw expression string
   * @param expressionLang the language identifier (e.g. {@code "jq"})
   * @return a new evaluator whose {@code type()} equals {@code expressionLang}
   * @throws IllegalArgumentException if no engine is registered for {@code expressionLang}
   * @throws UnsupportedOperationException if the matching engine does not override {@code create()}
   */
  ExpressionEvaluator create(String expression, String expressionLang);

  /**
   * Asserts that a registered {@link io.casehub.api.engine.ExpressionEngine} exists for {@code
   * expressionLang} and that it supports creation from string expressions.
   *
   * <p>Does NOT call {@link io.casehub.api.engine.ExpressionEngine#create} — no domain objects are
   * constructed as a side effect. Use this for fail-fast validation before parsing expressions.
   *
   * @param expressionLang the language identifier to check
   * @throws IllegalArgumentException if no engine is registered for {@code expressionLang}
   * @throws UnsupportedOperationException if the engine is registered but Java-DSL-only (does not
   *     override {@code create()})
   */
  void assertLanguageSupported(String expressionLang);
}
