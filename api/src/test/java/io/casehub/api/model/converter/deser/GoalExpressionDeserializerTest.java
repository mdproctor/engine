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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.model.converter.CaseDefinitionModule;
import org.junit.jupiter.api.Test;

class GoalExpressionDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void string_deserializesToSingleGoal() throws Exception {
    GoalExpression result = mapper.readValue("\"done\"", GoalExpression.class);
    assertInstanceOf(SingleGoalExpression.class, result);
    assertEquals("done", ((SingleGoalExpression) result).goalName());
  }

  @Test
  void stringArray_deserializesToAllOf() throws Exception {
    GoalExpression result = mapper.readValue("[\"a\", \"b\"]", GoalExpression.class);
    assertInstanceOf(AllOfGoalExpression.class, result);
    assertEquals(2, ((AllOfGoalExpression) result).children().size());
  }

  @Test
  void nestedAllOf_deserializesRecursively() throws Exception {
    String json = "{\"allOf\": [\"a\", {\"anyOf\": [\"b\", \"c\"]}]}";
    GoalExpression result = mapper.readValue(json, GoalExpression.class);
    assertInstanceOf(AllOfGoalExpression.class, result);
    var children = ((AllOfGoalExpression) result).children();
    assertEquals(2, children.size());
    assertInstanceOf(SingleGoalExpression.class, children.get(0));
    assertInstanceOf(AnyOfGoalExpression.class, children.get(1));
  }

  @Test
  void nestedAnyOf_deserializesRecursively() throws Exception {
    String json = "{\"anyOf\": [\"x\", \"y\"]}";
    GoalExpression result = mapper.readValue(json, GoalExpression.class);
    assertInstanceOf(AnyOfGoalExpression.class, result);
    assertEquals(2, ((AnyOfGoalExpression) result).children().size());
  }

  @Test
  void nullValue_deserializesToNull() throws Exception {
    GoalExpression result = mapper.readValue("null", GoalExpression.class);
    assertNull(result);
  }
}
