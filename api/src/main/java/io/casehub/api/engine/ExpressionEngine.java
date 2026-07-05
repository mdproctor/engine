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
import java.util.List;
import java.util.Optional;

/**
 * SPI for pluggable expression evaluation engines.
 *
 * <p>Each engine declares the {@link ExpressionEvaluator#type()} it handles and evaluates
 * expressions of that type against a {@link CaseContext}. Register additional engines as CDI beans
 * to support new expression languages (e.g. Drools, SpEL) without modifying the runtime.
 *
 * @see io.casehub.api.model.evaluator.JQExpressionEvaluator
 * @see io.casehub.api.model.evaluator.LambdaExpressionEvaluator
 */
public interface ExpressionEngine {

  /**
   * Returns the evaluator type this engine handles, matching {@link ExpressionEvaluator#type()}.
   */
  String type();

  /**
   * Evaluates the expression against the given context.
   *
   * @param evaluator the expression to evaluate — guaranteed to match {@link #type()}
   * @param context the current case state
   * @return {@code true} if the expression matches, {@code false} otherwise
   */
  boolean evaluate(ExpressionEvaluator evaluator, CaseContext context);

  /**
   * Validates the expression syntax without evaluating it against any context.
   *
   * @param evaluator the expression to validate — guaranteed to match {@link #type()}
   * @throws IllegalArgumentException if the expression is syntactically invalid
   */
  void validate(ExpressionEvaluator evaluator);

  /**
   * Extracts a string value from the given context using this evaluator.
   *
   * <p>Default implementation throws {@link UnsupportedOperationException}. Expression engines that
   * support value extraction (not just boolean evaluation) must override this method.
   *
   * <p>The {@link io.casehub.engine.common.spi.ExpressionEngineRegistry} catches {@code
   * UnsupportedOperationException} from this method and returns {@code Optional.empty()} + WARN —
   * so callers never see the exception propagate unless they invoke this method directly on an
   * engine that doesn't support it.
   *
   * @param evaluator the expression to evaluate — guaranteed to match {@link #type()}
   * @param context the current case state; implementations evaluate against the WORKING layer
   * @return the string value extracted from context, or empty if absent or evaluation fails
   */
  default Optional<String> extractString(ExpressionEvaluator evaluator, CaseContext context) {
    throw new UnsupportedOperationException(
        "ExpressionEngine '"
            + type()
            + "' does not support string extraction. "
            + "Override extractString() to enable this capability.");
  }

  /**
   * Creates an {@link ExpressionEvaluator} from a raw expression string.
   *
   * <p>Called by {@link io.casehub.engine.common.spi.ExpressionEngineRegistry#create} during YAML
   * case definition loading. Only engines that override this method can be used in YAML definitions
   * via {@code expressionLang: <type>}. Lambda-type evaluators are Java-DSL-only and intentionally
   * do not override this method.
   *
   * <p>Contract: the returned evaluator's {@code type()} MUST equal this engine's {@code type()}.
   *
   * @param expression the raw expression string
   * @return a new evaluator for the given expression
   * @throws UnsupportedOperationException if this engine does not support string-based creation
   */
  default ExpressionEvaluator create(final String expression) {
    throw new UnsupportedOperationException(
        "ExpressionEngine '"
            + type()
            + "' does not support creation from string expressions. "
            + "Use the Java DSL to construct evaluators of this type.");
  }

  /**
   * Returns {@code true} if this engine overrides {@link #create(String)} and supports creation of
   * evaluators from string expressions.
   *
   * <p>Used by {@link
   * io.casehub.engine.common.spi.ExpressionEngineRegistry#assertLanguageSupported} to distinguish
   * "no engine registered" from "engine registered but Java-DSL-only".
   */
  default boolean supportsStringCreation() {
    return false;
  }

  /**
   * Transforms the input JSON by applying the expression and returning the result(s).
   *
   * <p>Unlike {@link #evaluate}, which returns a boolean condition result, this method returns the
   * actual transformed output — used for output/input schema evaluation where the expression
   * reshapes data rather than testing a condition.
   *
   * @param evaluator the expression to apply — guaranteed to match {@link #type()}
   * @param input the JSON to transform
   * @return the transformation result(s); never {@code null}
   * @throws UnsupportedOperationException if this engine does not support transformation
   * @throws IllegalArgumentException if evaluation fails
   */
  default List<JsonNode> transform(final ExpressionEvaluator evaluator, final JsonNode input) {
    throw new UnsupportedOperationException(
        "ExpressionEngine '" + type() + "' does not support transform operations.");
  }
}
