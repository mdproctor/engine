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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("ExpressionEngineRegistry")
class ExpressionEngineRegistryTest {

  @Inject ExpressionEngineRegistry registry;

  @Nested
  @DisplayName("evaluate()")
  class Evaluate {

    @Test
    @DisplayName("returns true for null evaluator")
    void nullEvaluator_returnsTrue() {
      final var context = new CaseContextImpl(Map.of());
      assertTrue(registry.evaluate(null, context));
    }

    @Test
    @DisplayName("JQ — returns true when expression matches context")
    void jq_returnsTrueOnMatch() {
      final var context = new CaseContextImpl(Map.of("status", "ready"));
      final var evaluator = new JQExpressionEvaluator(".status == \"ready\"");
      assertTrue(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("JQ — returns false when expression does not match context")
    void jq_returnsFalseOnNoMatch() {
      final var context = new CaseContextImpl(Map.of("status", "pending"));
      final var evaluator = new JQExpressionEvaluator(".status == \"ready\"");
      assertFalse(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("JQ — returns true for null expression (treat as always-match)")
    void jq_nullExpression_returnsTrue() {
      final var context = new CaseContextImpl(Map.of());
      final var evaluator = new JQExpressionEvaluator(null);
      assertTrue(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("JQ — returns true for blank expression (treat as always-match)")
    void jq_blankExpression_returnsTrue() {
      final var context = new CaseContextImpl(Map.of());
      final var evaluator = new JQExpressionEvaluator("   ");
      assertTrue(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("Lambda — returns true when predicate matches")
    void lambda_returnsTrueOnMatch() {
      final var context = new CaseContextImpl(Map.of("score", 10));
      final var evaluator = new LambdaExpressionEvaluator(ctx -> ctx.get("score") != null);
      assertTrue(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("Lambda — returns false when predicate does not match")
    void lambda_returnsFalseOnNoMatch() {
      final var context = new CaseContextImpl(Map.of());
      final var evaluator = new LambdaExpressionEvaluator(ctx -> ctx.get("score") != null);
      assertFalse(registry.evaluate(evaluator, context));
    }

    @Test
    @DisplayName("throws IllegalArgumentException for unregistered evaluator type")
    void unknownType_throws() {
      final var context = new CaseContextImpl(Map.of());
      final var unknown =
          new io.casehub.platform.api.expression.ExpressionEvaluator() {
            @Override
            public String type() {
              return "drools";
            }
          };
      final var ex =
          assertThrows(IllegalArgumentException.class, () -> registry.evaluate(unknown, context));
      assertTrue(ex.getMessage().contains("drools"));
    }
  }

  @Nested
  @DisplayName("validate()")
  class Validate {

    @Test
    @DisplayName("no-op for null evaluator")
    void nullEvaluator_doesNotThrow() {
      assertDoesNotThrow(() -> registry.validate(null));
    }

    @Test
    @DisplayName("JQ — passes for valid expression")
    void jq_validExpression_doesNotThrow() {
      assertDoesNotThrow(
          () -> registry.validate(new JQExpressionEvaluator(".status == \"ready\"")));
    }

    @Test
    @DisplayName("JQ — passes for null expression")
    void jq_nullExpression_doesNotThrow() {
      assertDoesNotThrow(() -> registry.validate(new JQExpressionEvaluator(null)));
    }

    @Test
    @DisplayName("JQ — throws IllegalArgumentException for invalid syntax")
    void jq_invalidExpression_throws() {
      final var ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> registry.validate(new JQExpressionEvaluator(".foo ??? broken")));
      assertTrue(ex.getMessage().contains("Invalid JQ expression"));
    }

    @Test
    @DisplayName("Lambda — no-op regardless of predicate")
    void lambda_doesNotThrow() {
      assertDoesNotThrow(() -> registry.validate(new LambdaExpressionEvaluator(ctx -> true)));
    }
  }

  @Nested
  @DisplayName("create()")
  class Create {

    @Test
    @DisplayName("jq — returns JQExpressionEvaluator with correct expression")
    void jq_returnsJQExpressionEvaluator() {
      final ExpressionEvaluator result = registry.create(".status == \"ready\"", "jq");
      assertThat(result).isInstanceOf(JQExpressionEvaluator.class);
      assertThat(((JQExpressionEvaluator) result).expression()).isEqualTo(".status == \"ready\"");
    }

    @Test
    @DisplayName("jq — evaluator type() equals 'jq' (invariant)")
    void jq_evaluatorTypeMatchesLang() {
      final ExpressionEvaluator result = registry.create(".x", "jq");
      assertThat(result.type()).isEqualTo("jq");
    }

    @Test
    @DisplayName("unknown lang — throws IllegalArgumentException naming the lang")
    void unknownLang_throwsIllegalArgument() {
      assertThatThrownBy(() -> registry.create(".x", "drools"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("drools");
    }

    @Test
    @DisplayName("lambda lang — throws UnsupportedOperationException (Java-DSL-only)")
    void lambdaLang_throwsUnsupportedOperation() {
      assertThatThrownBy(() -> registry.create(".x", "lambda"))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("assertLanguageSupported()")
  class AssertLanguageSupported {

    @Test
    @DisplayName("jq — does not throw")
    void jq_doesNotThrow() {
      assertThatCode(() -> registry.assertLanguageSupported("jq")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unknown lang — throws IllegalArgumentException naming the lang")
    void unknownLang_throwsIllegalArgument() {
      assertThatThrownBy(() -> registry.assertLanguageSupported("drools"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("drools");
    }

    @Test
    @DisplayName("lambda lang — throws UnsupportedOperationException with actionable message")
    void lambdaLang_throwsUnsupportedOperationWithActionableMessage() {
      assertThatThrownBy(() -> registry.assertLanguageSupported("lambda"))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("Java-DSL-only")
          .hasMessageContaining("expressionLang: jq");
    }
  }

  @Nested
  @DisplayName("transform()")
  class Transform {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("returns input as-is for null evaluator")
    void nullEvaluator_returnsInput() {
      final JsonNode input = MAPPER.valueToTree(Map.of("a", 1, "b", 2));
      final List<JsonNode> result = registry.transform(null, input);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).isEqualTo(input);
    }

    @Test
    @DisplayName("JQ — extracts a field from input")
    void jq_extractsField() {
      final JsonNode input = MAPPER.valueToTree(Map.of("name", "Alice", "age", 30));
      final var evaluator = new JQExpressionEvaluator("{name: .name}");
      final List<JsonNode> result = registry.transform(evaluator, input);
      assertThat(result).hasSize(1);
      assertThat(result.get(0).get("name").asText()).isEqualTo("Alice");
      assertThat(result.get(0).has("age")).isFalse();
    }

    @Test
    @DisplayName("JQ — identity expression returns input unchanged")
    void jq_identity_returnsInput() {
      final JsonNode input = MAPPER.valueToTree(Map.of("x", 42));
      final var evaluator = new JQExpressionEvaluator(".");
      final List<JsonNode> result = registry.transform(evaluator, input);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).isEqualTo(input);
    }

    @Test
    @DisplayName("JQ — null expression returns input as-is")
    void jq_nullExpression_returnsInput() {
      final JsonNode input = MAPPER.valueToTree(Map.of("x", 1));
      final var evaluator = new JQExpressionEvaluator(null);
      final List<JsonNode> result = registry.transform(evaluator, input);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).isEqualTo(input);
    }

    @Test
    @DisplayName("JQ — blank expression returns input as-is")
    void jq_blankExpression_returnsInput() {
      final JsonNode input = MAPPER.valueToTree(Map.of("x", 1));
      final var evaluator = new JQExpressionEvaluator("   ");
      final List<JsonNode> result = registry.transform(evaluator, input);
      assertThat(result).hasSize(1);
      assertThat(result.get(0)).isEqualTo(input);
    }

    @Test
    @DisplayName("JQ — invalid expression throws IllegalArgumentException")
    void jq_invalidExpression_throws() {
      final JsonNode input = MAPPER.valueToTree(Map.of("x", 1));
      final var evaluator = new JQExpressionEvaluator(".foo ??? broken");
      assertThatThrownBy(() -> registry.transform(evaluator, input))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Lambda — throws UnsupportedOperationException")
    void lambda_throwsUnsupported() {
      final JsonNode input = MAPPER.valueToTree(Map.of("x", 1));
      final var evaluator = new LambdaExpressionEvaluator(ctx -> true);
      assertThatThrownBy(() -> registry.transform(evaluator, input))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("extractString()")
  class ExtractString {

    @Test
    @DisplayName("JQ — extracts string field from WORKING layer context")
    void jq_extractsStringField() {
      final var context =
          new CaseContextImpl(Map.of("indReportingDeadline", "2026-07-16T10:00:00Z"));
      final var evaluator = new JQExpressionEvaluator(".indReportingDeadline");
      final Optional<String> result = registry.extractString(evaluator, context);
      assertThat(result).contains("2026-07-16T10:00:00Z");
    }

    @Test
    @DisplayName("JQ — missing field returns empty (JQ null output, isTextual guard)")
    void jq_missingField_returnsEmpty() {
      final var context = new CaseContextImpl(Map.of("otherField", "value"));
      final var evaluator = new JQExpressionEvaluator(".indReportingDeadline");
      final Optional<String> result = registry.extractString(evaluator, context);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("JQ — expression produces no output returns empty (isEmpty guard)")
    void jq_emptyOutput_returnsEmpty() {
      final var context = new CaseContextImpl(Map.of("foo", "bar"));
      final var evaluator = new JQExpressionEvaluator("empty");
      final Optional<String> result = registry.extractString(evaluator, context);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("JQ — non-text output (number) returns empty")
    void jq_numericOutput_returnsEmpty() {
      final var context = new CaseContextImpl(Map.of("count", 42));
      final var evaluator = new JQExpressionEvaluator(".count");
      final Optional<String> result = registry.extractString(evaluator, context);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("unsupported engine type returns empty with no exception")
    void unsupportedEngine_returnsEmpty() {
      // LambdaExpressionEvaluator type is registered but does not override extractString()
      final var context = new CaseContextImpl(Map.of());
      final var evaluator = new LambdaExpressionEvaluator(ctx -> true);
      // Must not throw — registry catches UnsupportedOperationException
      assertThatCode(
              () -> {
                Optional<String> r = registry.extractString(evaluator, context);
                assertThat(r).isEmpty();
              })
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null evaluator returns empty")
    void nullEvaluator_returnsEmpty() {
      final var context = new CaseContextImpl(Map.of());
      assertThat(registry.extractString(null, context)).isEmpty();
    }
  }
}
