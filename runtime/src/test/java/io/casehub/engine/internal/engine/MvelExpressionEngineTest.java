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
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MvelExpressionEngineTest {

  private MvelExpressionEngine engine;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    engine = new MvelExpressionEngine(new io.casehub.platform.expression.MvelExpressionEngine());
  }

  @Test
  void type_returnsMvel() {
    assertThat(engine.type()).isEqualTo("mvel");
  }

  @Test
  void supportsStringCreation_returnsTrue() {
    assertThat(engine.supportsStringCreation()).isTrue();
  }

  @Test
  void create_returnsMvelEvaluator() {
    var evaluator = engine.create("amount > 100");
    assertThat(evaluator).isInstanceOf(MvelExpressionEvaluator.class);
    assertThat(evaluator.type()).isEqualTo("mvel");
  }

  @Test
  void evaluate_simpleComparison_returnsTrue() throws Exception {
    JsonNode node = mapper.readTree("{\"amount\": 200}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.evaluate(new MvelExpressionEvaluator("amount > 100"), context)).isTrue();
  }

  @Test
  void evaluate_simpleComparison_returnsFalse() throws Exception {
    JsonNode node = mapper.readTree("{\"amount\": 50}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.evaluate(new MvelExpressionEvaluator("amount > 100"), context)).isFalse();
  }

  @Test
  void evaluate_multipleTopLevelProperties_combinedCondition() throws Exception {
    JsonNode node = mapper.readTree("{\"amount\": 200, \"status\": \"active\"}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(
            engine.evaluate(
                new MvelExpressionEvaluator("amount > 100 && status == \"active\""), context))
        .isTrue();
  }

  @Test
  void evaluate_nullEvaluator_returnsTrue() {
    CaseContext context = new CaseContextImpl(mapper.createObjectNode());

    assertThat(engine.evaluate(null, context)).isTrue();
  }

  @Test
  void evaluate_blankExpression_returnsTrue() {
    CaseContext context = new CaseContextImpl(mapper.createObjectNode());

    assertThat(engine.evaluate(new MvelExpressionEvaluator("   "), context)).isTrue();
  }

  @Test
  void evaluate_equalityCheck_returnsTrue() throws Exception {
    JsonNode node = mapper.readTree("{\"status\": \"active\"}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.evaluate(new MvelExpressionEvaluator("status == \"active\""), context))
        .isTrue();
  }
}
