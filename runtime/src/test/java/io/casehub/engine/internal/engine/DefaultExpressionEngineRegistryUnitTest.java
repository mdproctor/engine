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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultExpressionEngineRegistry — unit tests")
class DefaultExpressionEngineRegistryUnitTest {

  @Test
  @DisplayName(
      "create() — throws IllegalStateException when evaluator type does not match engine type")
  void create_typeMismatch_throwsIllegalState() {
    var buggyEngine =
        new ExpressionEngine() {
          @Override
          public String type() {
            return "buggy";
          }

          @Override
          public boolean evaluate(ExpressionEvaluator e, CaseContext c) {
            return false;
          }

          @Override
          public void validate(ExpressionEvaluator e) {}

          @Override
          public ExpressionEvaluator create(String expr) {
            return () -> "wrong-type";
          }

          @Override
          public boolean supportsStringCreation() {
            return true;
          }
        };

    var registry = new DefaultExpressionEngineRegistry(Map.of("buggy", buggyEngine));

    assertThatThrownBy(() -> registry.create(".x", "buggy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("buggy")
        .hasMessageContaining("wrong-type");
  }

  @Test
  @DisplayName("evaluate(JsonNode) — throws IllegalArgumentException when asNode is null")
  void evaluate_nullJsonNode_throwsIllegalArgument() {
    var registry = new DefaultExpressionEngineRegistry(Map.of());
    var evaluator = new JQExpressionEvaluator(".x");

    assertThatThrownBy(() -> registry.evaluate(evaluator, (JsonNode) null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
