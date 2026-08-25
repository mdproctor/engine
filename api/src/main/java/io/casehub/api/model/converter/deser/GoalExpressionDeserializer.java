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
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.SingleGoalExpression;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GoalExpressionDeserializer extends StdDeserializer<GoalExpression> {

  public GoalExpressionDeserializer() {
    super(GoalExpression.class);
  }

  @Override
  public GoalExpression deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    JsonNode node = p.readValueAsTree();
    return parseNode(node, ctxt);
  }

  static GoalExpression parseNode(JsonNode node, DeserializationContext ctxt) throws IOException {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return new SingleGoalExpression(node.asText());
    }
    if (node.isArray()) {
      List<GoalExpression> children = new ArrayList<>();
      for (JsonNode element : node) {
        children.add(parseNode(element, ctxt));
      }
      return new AllOfGoalExpression(children);
    }
    if (node.isObject()) {
      JsonNode allOfNode = node.get("allOf");
      if (allOfNode != null && allOfNode.isArray()) {
        if (allOfNode.isEmpty()) {
          throw ctxt.weirdStringException(
              node.toString(), GoalExpression.class, "allOf array must not be empty");
        }
        List<GoalExpression> children = new ArrayList<>();
        for (JsonNode element : allOfNode) {
          children.add(parseNode(element, ctxt));
        }
        return new AllOfGoalExpression(children);
      }
      JsonNode anyOfNode = node.get("anyOf");
      if (anyOfNode != null && anyOfNode.isArray()) {
        if (anyOfNode.isEmpty()) {
          throw ctxt.weirdStringException(
              node.toString(), GoalExpression.class, "anyOf array must not be empty");
        }
        List<GoalExpression> children = new ArrayList<>();
        for (JsonNode element : anyOfNode) {
          children.add(parseNode(element, ctxt));
        }
        return new AnyOfGoalExpression(children);
      }
    }
    throw ctxt.weirdStringException(
        node.toString(),
        GoalExpression.class,
        "GoalExpression must be a string, array of strings, or {allOf/anyOf: [...]}");
  }

  @Override
  public GoalExpression getNullValue(DeserializationContext ctxt) {
    return null;
  }
}
