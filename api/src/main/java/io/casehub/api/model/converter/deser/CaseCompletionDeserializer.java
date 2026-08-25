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
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class CaseCompletionDeserializer extends StdDeserializer<CaseCompletion> {

  public CaseCompletionDeserializer() {
    super(CaseCompletion.class);
  }

  @Override
  public CaseCompletion deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode node = p.readValueAsTree();
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw ctxt.weirdStringException(
          node.toString(), CaseCompletion.class, "completion must be an object");
    }

    JsonNode doneWhenNode = node.get("doneWhen");
    boolean hasDoneWhen = doneWhenNode != null && !doneWhenNode.isNull();
    boolean hasGoalKinds = false;
    Iterator<String> fieldNames = node.fieldNames();
    while (fieldNames.hasNext()) {
      String name = fieldNames.next();
      if (!"doneWhen".equals(name)) {
        hasGoalKinds = true;
        break;
      }
    }

    if (hasDoneWhen && hasGoalKinds) {
      throw ctxt.weirdStringException(
          node.toString(),
          CaseCompletion.class,
          "completion cannot have both 'doneWhen' and goal-kind entries");
    }

    if (hasDoneWhen) {
      ExpressionEvaluator evaluator = resolveExpression(doneWhenNode, ctxt);
      return new PredicateBasedCompletion(evaluator);
    }

    GoalBasedCompletion.Builder<GoalKind> builder = GoalBasedCompletion.builder();
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      GoalKind kind = resolveGoalKind(entry.getKey(), entry.getValue(), ctxt);
      GoalExpression expression = GoalExpressionDeserializer.parseNode(entry.getValue(), ctxt);
      builder.goal(kind, expression);
    }
    return builder.build();
  }

  private ExpressionEvaluator resolveExpression(JsonNode node, DeserializationContext ctxt)
      throws IOException {
    if (node.isTextual()) {
      String defaultLang =
          (String) ctxt.getAttribute(ExpressionEvaluatorDeserializer.EXPRESSION_LANG_KEY);
      if (defaultLang == null || JQExpressionEvaluator.TYPE.equals(defaultLang)) {
        return new JQExpressionEvaluator(node.asText());
      }
    }
    JsonParser nested = node.traverse(ctxt.getParser().getCodec());
    nested.nextToken();
    return ctxt.readValue(nested, ExpressionEvaluator.class);
  }

  private GoalKind resolveGoalKind(String kindValue, JsonNode exprNode, DeserializationContext ctxt)
      throws IOException {
    if ("doneWhen".equals(kindValue)) {
      throw ctxt.weirdStringException(
          kindValue,
          GoalKind.class,
          "'doneWhen' is a reserved name and cannot be used as a goal kind");
    }
    if ("success".equals(kindValue) || "failure".equals(kindValue)) {
      return StandardGoalKind.fromValue(kindValue);
    }
    if (!exprNode.has("status")) {
      throw ctxt.weirdStringException(
          kindValue,
          GoalKind.class,
          "Custom goal kind '"
              + kindValue
              + "' requires an explicit 'status' field (COMPLETED or FAULTED)");
    }
    return GoalKind.of(kindValue, CaseStatus.valueOf(exprNode.get("status").asText()));
  }

  @Override
  public CaseCompletion getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
