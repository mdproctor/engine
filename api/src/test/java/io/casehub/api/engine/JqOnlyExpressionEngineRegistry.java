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
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.util.List;
import java.util.Optional;

final class JqOnlyExpressionEngineRegistry implements ExpressionEngineRegistry {

  @Override
  public ExpressionEvaluator create(String expression, String expressionLang) {
    assertJq(expressionLang);
    return new JQExpressionEvaluator(expression);
  }

  @Override
  public void assertLanguageSupported(String expressionLang) {
    assertJq(expressionLang);
  }

  @Override
  public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
    throw new UnsupportedOperationException("test-only loading registry");
  }

  @Override
  public boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode) {
    throw new UnsupportedOperationException("test-only loading registry");
  }

  @Override
  public void validate(ExpressionEvaluator evaluator) {}

  @Override
  public List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input) {
    throw new UnsupportedOperationException("test-only loading registry");
  }

  @Override
  public Optional<String> extractString(ExpressionEvaluator evaluator, CaseContext context) {
    throw new UnsupportedOperationException("test-only loading registry");
  }

  private static void assertJq(String lang) {
    if (!JQExpressionEvaluator.TYPE.equals(lang)) {
      throw new IllegalArgumentException("Only 'jq' supported in tests. Got: " + lang);
    }
  }
}
