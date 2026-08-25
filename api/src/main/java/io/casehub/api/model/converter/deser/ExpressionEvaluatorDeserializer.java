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
package io.casehub.api.model.converter.deser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.IOException;

public class ExpressionEvaluatorDeserializer extends StdDeserializer<ExpressionEvaluator> {

  static final String EXPRESSION_LANG_KEY = "casehub.expressionLang";
  private final ExpressionEngineRegistry registry;

  public ExpressionEvaluatorDeserializer(ExpressionEngineRegistry registry) {
    super(ExpressionEvaluator.class);
    this.registry = registry;
  }

  @Override
  public ExpressionEvaluator deserialize(JsonParser p, DeserializationContext ctxt)
      throws IOException {
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }
    String defaultLang = (String) ctxt.getAttribute(EXPRESSION_LANG_KEY);
    if (defaultLang == null) {
      defaultLang = JQExpressionEvaluator.TYPE;
    }
    if (node.isTextual()) {
      return createExpression(node.asText(), defaultLang);
    }
    if (node.isObject()) {
      if (node.size() != 1) {
        throw ctxt.weirdStringException(
            node.toString(),
            ExpressionEvaluator.class,
            "Expression override must be a single-key map {lang: expr}, got "
                + node.size()
                + " keys");
      }
      var entry = node.fields().next();
      return createExpression(entry.getValue().asText(), entry.getKey());
    }
    throw ctxt.weirdStringException(
        node.toString(),
        ExpressionEvaluator.class,
        "Expression must be a string or single-key map {lang: expr}");
  }

  private ExpressionEvaluator createExpression(String expression, String lang) {
    if (JQExpressionEvaluator.TYPE.equals(lang)) {
      return new JQExpressionEvaluator(expression);
    }
    if (registry == null) {
      throw new IllegalArgumentException(
          "ExpressionEngineRegistry required for non-JQ language: " + lang);
    }
    return registry.create(expression, lang);
  }

  @Override
  public ExpressionEvaluator getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
