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
package io.casehub.engine.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@Alternative
@Priority(1)
@ApplicationScoped
public class TestExpressionEngineRegistry implements ExpressionEngineRegistry {

  @Override
  public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
    if (evaluator == null) {
      return true;
    }
    if (evaluator instanceof LambdaExpressionEvaluator lambda) {
      return lambda.test(context);
    }
    throw new IllegalArgumentException("Unsupported evaluator type in test: " + evaluator.type());
  }

  @Override
  public boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode) {
    throw new UnsupportedOperationException("JsonNode evaluation not supported in test");
  }

  @Override
  public void validate(ExpressionEvaluator evaluator) {
    // no-op in tests
  }

  @Override
  public ExpressionEvaluator create(String expression, String expressionLang) {
    throw new UnsupportedOperationException("Expression creation not supported in test");
  }

  @Override
  public void assertLanguageSupported(String expressionLang) {
    if (!"lambda".equals(expressionLang)) {
      throw new IllegalArgumentException("Unsupported language in test: " + expressionLang);
    }
  }

  @Override
  public java.util.List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input) {
    throw new UnsupportedOperationException("Transform not supported in test");
  }

  @Override
  public java.util.Optional<String> extractString(
      ExpressionEvaluator evaluator, CaseContext context) {
    return java.util.Optional.empty();
  }
}
