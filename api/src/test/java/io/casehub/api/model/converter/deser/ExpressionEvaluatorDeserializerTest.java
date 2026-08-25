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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.converter.CaseDefinitionModule;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import org.junit.jupiter.api.Test;

class ExpressionEvaluatorDeserializerTest {

  private final ObjectMapper mapper =
      new ObjectMapper().registerModule(new CaseDefinitionModule(null));

  @Test
  void string_deserializesToJQExpression() throws Exception {
    ExpressionEvaluator result =
        mapper.readValue("\"$.transaction.amount > 10000\"", ExpressionEvaluator.class);
    assertInstanceOf(JQExpressionEvaluator.class, result);
    assertEquals("$.transaction.amount > 10000", ((JQExpressionEvaluator) result).expression());
  }

  @Test
  void singleKeyMap_jq_deserializesToJQ() throws Exception {
    ExpressionEvaluator result =
        mapper.readValue("{\"jq\": \".amount > 100\"}", ExpressionEvaluator.class);
    assertInstanceOf(JQExpressionEvaluator.class, result);
    assertEquals(".amount > 100", ((JQExpressionEvaluator) result).expression());
  }

  @Test
  void singleKeyMap_nonJqWithoutRegistry_throws() {
    assertThrows(
        Exception.class,
        () -> mapper.readValue("{\"mvel\": \"amount > 100\"}", ExpressionEvaluator.class));
  }

  @Test
  void multiKeyMap_throws() {
    assertThrows(
        Exception.class,
        () -> mapper.readValue("{\"jq\": \"a\", \"mvel\": \"b\"}", ExpressionEvaluator.class));
  }

  @Test
  void nullValue_deserializesToNull() throws Exception {
    ExpressionEvaluator result = mapper.readValue("null", ExpressionEvaluator.class);
    assertNull(result);
  }
}
