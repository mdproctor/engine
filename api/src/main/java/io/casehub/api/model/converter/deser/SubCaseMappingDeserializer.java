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
import io.casehub.api.model.SubCaseMapping;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.IOException;

public class SubCaseMappingDeserializer extends StdDeserializer<SubCaseMapping> {

  public SubCaseMappingDeserializer() {
    super(SubCaseMapping.class);
  }

  @Override
  public SubCaseMapping deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return SubCaseMapping.of(node.asText());
    }
    if (node.isObject() && node.size() == 1) {
      var entry = node.fields().next();
      String lang = entry.getKey();
      String expr = entry.getValue().asText();
      ExpressionEvaluator evaluator;
      if (JQExpressionEvaluator.TYPE.equals(lang)) {
        evaluator = new JQExpressionEvaluator(expr);
      } else {
        JsonParser nested = node.traverse(ctxt.getParser().getCodec());
        nested.nextToken();
        evaluator = ctxt.readValue(nested, ExpressionEvaluator.class);
      }
      return new SubCaseMapping.Expression(evaluator);
    }
    throw ctxt.weirdStringException(
        node.toString(),
        SubCaseMapping.class,
        "SubCaseMapping must be a string or single-key map {lang: expr}");
  }

  @Override
  public SubCaseMapping getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
