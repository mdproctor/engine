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
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultExpressionEngineRegistry — unit tests")
class DefaultExpressionEngineRegistryUnitTest {

  private static final ExpressionEngineRegistry NO_OP_PLATFORM_REGISTRY =
      new ExpressionEngineRegistry() {
        @Override
        public void register(io.casehub.platform.api.expression.ExpressionEngine engine) {}

        @Override
        public Optional<io.casehub.platform.api.expression.ExpressionEngine> resolve(String type) {
          return Optional.empty();
        }

        @Override
        public <C, R> CompiledExpression<C, R> compile(
            String type, String expression, Class<C> contextType, Class<R> resultType) {
          throw new UnsupportedOperationException();
        }

        @Override
        public <C, R> CompiledExpression<C, R> compile(
            String type,
            String expression,
            Class<C> contextType,
            Class<R> resultType,
            Map<String, Object> variables) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void validate(String type, String expression) {}
      };

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

    var registry =
        new DefaultExpressionEngineRegistry(Map.of("buggy", buggyEngine), NO_OP_PLATFORM_REGISTRY);

    assertThatThrownBy(() -> registry.create(".x", "buggy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("buggy")
        .hasMessageContaining("wrong-type");
  }

  @Test
  @DisplayName("evaluate(JsonNode) — throws IllegalArgumentException when asNode is null")
  void evaluate_nullJsonNode_throwsIllegalArgument() {
    var registry = new DefaultExpressionEngineRegistry(Map.of(), NO_OP_PLATFORM_REGISTRY);
    var evaluator = new JQExpressionEvaluator(".x");

    assertThatThrownBy(() -> registry.evaluate(evaluator, (JsonNode) null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
