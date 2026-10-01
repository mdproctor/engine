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
package io.casehub.engine.internal.jq;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.casehub.engine.common.internal.jq.ValidationResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ValidationResult}.
 *
 * <p>These focus on the {@link ValidationResult#isTrue()} contract, which is the primary decision
 * gate used by the engine: a result is "true" only if {@code ok == true} AND the output list
 * contains at least one boolean {@code true} node.
 */
@DisplayName("ValidationResult")
class ValidationResultTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ------------------------------------------------------------------ //
  //  Error results                                                       //
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("error results")
  class ErrorResults {

    @Test
    @DisplayName("error result is not ok")
    void error_notOk() {
      final var result = ValidationResult.error("some error");
      assertFalse(result.ok());
    }

    @Test
    @DisplayName("error result output is null")
    void error_outputIsNull() {
      final var result = ValidationResult.error("some error");
      assertNull(result.output());
    }

    @Test
    @DisplayName("error result isTrue returns false")
    void error_isFalse() {
      final var result = ValidationResult.error("compilation error");
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("error result with null message does not throw")
    void error_nullMessageDoesNotThrow() {
      final var result = ValidationResult.error(null);
      assertFalse(result.ok());
      assertFalse(result.isTrue());
    }
  }

  // ------------------------------------------------------------------ //
  //  ok results — empty output                                           //
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("ok results — empty output")
  class EmptyOutput {

    @Test
    @DisplayName("empty output list isTrue returns false")
    void emptyOutput_isFalse() {
      final var result = ValidationResult.ok(List.of());
      assertTrue(result.ok());
      assertFalse(result.isTrue(), "empty output must not be considered true");
    }
  }

  // ------------------------------------------------------------------ //
  //  ok results — non-boolean output                                     //
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("ok results — non-boolean output")
  class NonBooleanOutput {

    @Test
    @DisplayName("string node in output isTrue returns false")
    void stringOutput_isFalse() throws Exception {
      final var node = MAPPER.readTree("\"hello\"");
      final var result = ValidationResult.ok(List.of(node));
      assertFalse(result.isTrue(), "string output must not be considered true");
    }

    @Test
    @DisplayName("numeric node (1) isTrue returns false — only boolean true counts")
    void numberOutput_isFalse() throws Exception {
      final var node = MAPPER.readTree("1");
      final var result = ValidationResult.ok(List.of(node));
      assertFalse(result.isTrue(), "numeric 1 must not be considered boolean true");
    }

    @Test
    @DisplayName("null JSON node isTrue returns false")
    void nullNode_isFalse() throws Exception {
      final var node = MAPPER.readTree("null");
      final var result = ValidationResult.ok(List.of(node));
      assertFalse(result.isTrue(), "JSON null node must not be considered true");
    }

    @Test
    @DisplayName("object node isTrue returns false")
    void objectNode_isFalse() throws Exception {
      final var node = MAPPER.readTree("{\"status\":\"ok\"}");
      final var result = ValidationResult.ok(List.of(node));
      assertFalse(result.isTrue(), "object node must not be considered true");
    }

    @Test
    @DisplayName("array node isTrue returns false")
    void arrayNode_isFalse() throws Exception {
      final var node = MAPPER.readTree("[true, true]");
      final var result = ValidationResult.ok(List.of(node));
      assertFalse(
          result.isTrue(),
          "array node (even containing trues) must not be considered true — isTrue checks nodes"
              + " directly");
    }
  }

  // ------------------------------------------------------------------ //
  //  ok results — boolean output                                         //
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("ok results — boolean output")
  class BooleanOutput {

    @Test
    @DisplayName("single boolean true node isTrue returns true")
    void singleTrue_isTrue() {
      final var result = ValidationResult.ok(List.of(BooleanNode.TRUE));
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("single boolean false node isTrue returns false")
    void singleFalse_isFalse() {
      final var result = ValidationResult.ok(List.of(BooleanNode.FALSE));
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("multiple boolean nodes — any true means isTrue returns true")
    void multipleMixed_anyTrueIsTrue() {
      final var result =
          ValidationResult.ok(List.of(BooleanNode.FALSE, BooleanNode.TRUE, BooleanNode.FALSE));
      assertTrue(result.isTrue(), "at least one true in multiple results is sufficient");
    }

    @Test
    @DisplayName("multiple boolean false nodes — all false means isTrue returns false")
    void multipleAllFalse_isFalse() {
      final var result =
          ValidationResult.ok(List.of(BooleanNode.FALSE, BooleanNode.FALSE, BooleanNode.FALSE));
      assertFalse(result.isTrue(), "all false means isTrue must be false");
    }

    @Test
    @DisplayName("mixed boolean and non-boolean — true boolean presence makes isTrue true")
    void mixedBooleanAndNonBoolean_trueBooleanWins() throws Exception {
      final var stringNode = MAPPER.readTree("\"hello\"");
      final var result = ValidationResult.ok(List.of(stringNode, BooleanNode.TRUE));
      assertTrue(result.isTrue(), "boolean true among mixed nodes should still return true");
    }

    @Test
    @DisplayName("non-boolean followed by false — isTrue returns false")
    void nonBooleanFollowedByFalse_isFalse() throws Exception {
      final var stringNode = MAPPER.readTree("\"hello\"");
      final var result = ValidationResult.ok(List.of(stringNode, BooleanNode.FALSE));
      assertFalse(result.isTrue());
    }
  }

  // ------------------------------------------------------------------ //
  //  error results behave correctly even with ok=false                  //
  // ------------------------------------------------------------------ //

  @Nested
  @DisplayName("ok=false but non-null output is ignored")
  class OkFalseWithOutput {

    @Test
    @DisplayName("if ok=false, isTrue is false regardless of output content")
    void okFalse_outputHasTrueNode_isFalse() {
      // Construct directly — factory methods don't allow this combination, but the record does
      final var result = new ValidationResult(false, "error", List.of(BooleanNode.TRUE));
      assertFalse(result.isTrue(), "ok=false must short-circuit to false");
    }
  }
}
