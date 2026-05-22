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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.platform.expression.JQEvaluator;
import io.casehub.platform.expression.ValidationResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for JQEvaluator. Tests cover various JQ expression patterns and edge
 * cases.
 */
@QuarkusTest
@DisplayName("JQEvaluator")
class JQEvaluatorTest {

  @Inject JQEvaluator jqEvaluator;

  @Inject ObjectMapper objectMapper;

  @Nested
  @DisplayName("Simple Boolean Expressions")
  class SimpleBooleanExpressions {

    @Test
    @DisplayName("should return true when status equals 'processing'")
    void testSimpleEquality_True() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status == \"processing\"", input);

      assertTrue(result.ok(), "Expression should execute successfully");
      assertTrue(result.isTrue(), "Expression should evaluate to true");
      assertEquals(1, result.output().size());
    }

    @Test
    @DisplayName("should return false when status does not equal 'processing'")
    void testSimpleEquality_False() throws Exception {
      String json =
          """
                    {
                        "status": "completed"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status == \"processing\"", input);

      assertTrue(result.ok(), "Expression should execute successfully");
      assertFalse(result.isTrue(), "Expression should evaluate to false");
    }

    @Test
    @DisplayName("should return false when field is null")
    void testNullField() throws Exception {
      String json =
          """
                    {
                        "status": null
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status == \"processing\"", input);

      assertTrue(result.ok(), "Expression should execute successfully");
      assertFalse(result.isTrue(), "Null should not equal 'processing'");
    }

    @Test
    @DisplayName("should return false when field is missing")
    void testMissingField() throws Exception {
      String json =
          """
                    {
                        "otherField": "value"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status == \"processing\"", input);

      assertTrue(result.ok(), "Expression should execute successfully");
      assertFalse(result.isTrue(), "Missing field should not equal 'processing'");
    }
  }

  @Nested
  @DisplayName("Logical Operators (OR/AND)")
  class LogicalOperators {

    @Test
    @DisplayName("should return true when OR condition matches first operand")
    void testOr_FirstTrue() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" or .status == \"pending\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "OR should be true when first operand is true");
    }

    @Test
    @DisplayName("should return true when OR condition matches second operand")
    void testOr_SecondTrue() throws Exception {
      String json =
          """
                    {
                        "status": "pending"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" or .status == \"pending\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "OR should be true when second operand is true");
    }

    @Test
    @DisplayName("should return false when OR condition matches neither operand")
    void testOr_BothFalse() throws Exception {
      String json =
          """
                    {
                        "status": "completed"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" or .status == \"pending\"", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue(), "OR should be false when both operands are false");
    }

    @Test
    @DisplayName("should return true when AND condition matches both operands")
    void testAnd_BothTrue() throws Exception {
      String json =
          """
                    {
                        "status": "processing",
                        "priority": "high"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" and .priority == \"high\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "AND should be true when both operands are true");
    }

    @Test
    @DisplayName("should return false when AND condition has one false operand")
    void testAnd_OneFalse() throws Exception {
      String json =
          """
                    {
                        "status": "processing",
                        "priority": "low"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" and .priority == \"high\"", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue(), "AND should be false when one operand is false");
    }
  }

  @Nested
  @DisplayName("Comparison Operators")
  class ComparisonOperators {

    @Test
    @DisplayName("should handle not equal (!=) operator")
    void testNotEqual() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status != \"completed\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle greater than (>) operator")
    void testGreaterThan() throws Exception {
      String json =
          """
                    {
                        "count": 10
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".count > 5", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle less than (<) operator")
    void testLessThan() throws Exception {
      String json =
          """
                    {
                        "count": 3
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".count < 5", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle greater than or equal (>=) operator")
    void testGreaterThanOrEqual() throws Exception {
      String json =
          """
                    {
                        "count": 5
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".count >= 5", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle less than or equal (<=) operator")
    void testLessThanOrEqual() throws Exception {
      String json =
          """
                    {
                        "count": 5
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".count <= 5", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }

  @Nested
  @DisplayName("Nested Field Access")
  class NestedFieldAccess {

    @Test
    @DisplayName("should access nested fields")
    void testNestedField() throws Exception {
      String json =
          """
                    {
                        "document": {
                            "status": "processing"
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".document.status == \"processing\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should access deeply nested fields")
    void testDeeplyNestedField() throws Exception {
      String json =
          """
                    {
                        "data": {
                            "document": {
                                "metadata": {
                                    "status": "processing"
                                }
                            }
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".data.document.metadata.status == \"processing\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle missing nested field gracefully")
    void testMissingNestedField() throws Exception {
      String json =
          """
                    {
                        "document": {}
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".document.status == \"processing\"", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue());
    }
  }

  @Nested
  @DisplayName("Array Operations")
  class ArrayOperations {

    @Test
    @DisplayName("should check if any array element matches (using any)")
    void testArrayAny() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"status": "completed"},
                            {"status": "processing"},
                            {"status": "pending"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval("any(.documents[]; .status == \"processing\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "Should return true when at least one element matches");
    }

    @Test
    @DisplayName("should check if all array elements match (using all)")
    void testArrayAll_True() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"status": "processing"},
                            {"status": "processing"},
                            {"status": "processing"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval("all(.documents[]; .status == \"processing\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "Should return true when all elements match");
    }

    @Test
    @DisplayName("should return false when not all array elements match")
    void testArrayAll_False() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"status": "processing"},
                            {"status": "completed"},
                            {"status": "processing"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval("all(.documents[]; .status == \"processing\")", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue(), "Should return false when not all elements match");
    }

    @Test
    @DisplayName("should iterate over array elements")
    void testArrayIteration() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"status": "completed"},
                            {"status": "processing"},
                            {"status": "pending"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      // Returns multiple results: [false, true, false]
      ValidationResult result = jqEvaluator.eval(".documents[] | .status == \"processing\"", input);

      assertTrue(result.ok());
      assertEquals(3, result.output().size(), "Should return 3 results");
      // With ANY semantics (current implementation), should be true if at least one is true
      assertTrue(result.isTrue(), "Should be true because one element matches");
    }

    @Test
    @DisplayName("should check array length")
    void testArrayLength() throws Exception {
      String json =
          """
                    {
                        "documents": [1, 2, 3, 4, 5]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".documents | length > 3", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle empty arrays")
    void testEmptyArray() throws Exception {
      String json =
          """
                    {
                        "documents": []
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval("any(.documents[]; .status == \"processing\")", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue(), "Should return false for empty array");
    }
  }

  @Nested
  @DisplayName("Type Checking")
  class TypeChecking {

    @Test
    @DisplayName("should check if value is a string")
    void testIsString() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".status | type == \"string\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if value is a number")
    void testIsNumber() throws Exception {
      String json =
          """
                    {
                        "count": 42
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".count | type == \"number\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if value is an object")
    void testIsObject() throws Exception {
      String json =
          """
                    {
                        "document": {
                            "id": "123"
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".document | type == \"object\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if value is an array")
    void testIsArray() throws Exception {
      String json =
          """
                    {
                        "items": [1, 2, 3]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".items | type == \"array\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if value is null")
    void testIsNull() throws Exception {
      String json =
          """
                    {
                        "value": null
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".value | type == \"null\"", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }

  @Nested
  @DisplayName("String Operations")
  class StringOperations {

    @Test
    @DisplayName("should check if string contains substring")
    void testContains() throws Exception {
      String json =
          """
                    {
                        "message": "Processing document 123"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".message | contains(\"Processing\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if string starts with prefix")
    void testStartsWith() throws Exception {
      String json =
          """
                    {
                        "filename": "document.pdf"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".filename | startswith(\"document\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should check if string ends with suffix")
    void testEndsWith() throws Exception {
      String json =
          """
                    {
                        "filename": "document.pdf"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".filename | endswith(\".pdf\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should match string with regex")
    void testRegexMatch() throws Exception {
      String json =
          """
                    {
                        "email": "user@example.com"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".email | test(\"^[a-z]+@[a-z]+\\\\.[a-z]+$\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }

  @Nested
  @DisplayName("Complex Expressions")
  class ComplexExpressions {

    @Test
    @DisplayName("should handle combined conditions with nested objects")
    void testComplexCondition() throws Exception {
      String json =
          """
                    {
                        "document": {
                            "status": "processing",
                            "priority": "high",
                            "metadata": {
                                "urgent": true
                            }
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(
              ".document.status == \"processing\" and "
                  + ".document.priority == \"high\" and "
                  + ".document.metadata.urgent == true",
              input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle has() function to check field existence")
    void testHasField() throws Exception {
      String json =
          """
                    {
                        "document": {
                            "id": "123",
                            "status": "processing"
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".document | has(\"status\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should return false when field does not exist")
    void testHasField_Missing() throws Exception {
      String json =
          """
                    {
                        "document": {
                            "id": "123"
                        }
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".document | has(\"status\")", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("should use select to filter objects")
    void testSelect() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"id": "1", "status": "processing"},
                            {"id": "2", "status": "completed"},
                            {"id": "3", "status": "processing"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(
              "[.documents[] | select(.status == \"processing\")] | length > 0", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandling {

    @Test
    @DisplayName("should handle invalid JQ syntax")
    void testInvalidSyntax() {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = assertDoesNotThrow(() -> objectMapper.readTree(json));

      ValidationResult result = jqEvaluator.eval(".status ==", input);

      assertFalse(result.ok(), "Should fail for invalid syntax");
      assertNotNull(result.error());
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("should handle null input")
    void testNullInput() {
      ValidationResult result = jqEvaluator.eval(".status == \"processing\"", null);

      assertFalse(result.ok(), "Should handle null input gracefully");
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("should handle empty JQ expression")
    void testEmptyExpression() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      // Empty string is not a valid JQ expression
      ValidationResult result = jqEvaluator.eval("", input);

      assertFalse(result.ok(), "Should fail for empty expression");
    }
  }

  @Nested
  @DisplayName("Edge Cases")
  class EdgeCases {

    @Test
    @DisplayName("should handle identity operator (.)")
    void testIdentity() throws Exception {
      String json =
          """
                    {
                        "status": "processing"
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".", input);

      assertTrue(result.ok());
      assertEquals(1, result.output().size());
      // Identity returns the object itself, not a boolean
      assertFalse(result.isTrue(), "Identity does not return boolean");
    }

    @Test
    @DisplayName("should handle boolean true literal")
    void testBooleanTrueLiteral() throws Exception {
      String json =
          """
                    {}
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("true", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should handle boolean false literal")
    void testBooleanFalseLiteral() throws Exception {
      String json =
          """
                    {}
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("false", input);

      assertTrue(result.ok());
      assertFalse(result.isTrue());
    }

    @Test
    @DisplayName("should handle not operator")
    void testNotOperator() throws Exception {
      String json =
          """
                    {
                        "active": false
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval(".active | not", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "not(false) should be true");
    }

    @Test
    @DisplayName("should handle parentheses for precedence")
    void testParentheses() throws Exception {
      String json =
          """
                    {
                        "a": 1,
                        "b": 2,
                        "c": 3
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("(.a == 1 and .b == 2) or .c == 4", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }

  @Nested
  @DisplayName("Real-world Case Hub Scenarios")
  class RealWorldScenarios {

    @Test
    @DisplayName("should trigger worker when document needs processing")
    void testDocumentProcessingTrigger() throws Exception {
      String json =
          """
                    {
                        "documentId": "doc-123",
                        "status": "processing",
                        "assignedTo": null
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result =
          jqEvaluator.eval(".status == \"processing\" and .assignedTo == null", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue(), "Should trigger when document needs assignment");
    }

    @Test
    @DisplayName("should trigger worker when all documents are ready")
    void testAllDocumentsReady() throws Exception {
      String json =
          """
                    {
                        "documents": [
                            {"status": "ready"},
                            {"status": "ready"},
                            {"status": "ready"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("all(.documents[]; .status == \"ready\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should trigger worker when high priority items exist")
    void testHighPriorityItems() throws Exception {
      String json =
          """
                    {
                        "items": [
                            {"priority": "low"},
                            {"priority": "high"},
                            {"priority": "medium"}
                        ]
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("any(.items[]; .priority == \"high\")", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }

    @Test
    @DisplayName("should trigger worker based on time threshold")
    void testTimeThreshold() throws Exception {
      String json =
          """
                    {
                        "createdAt": 1000000,
                        "updatedAt": 2000000
                    }
                    """;
      JsonNode input = objectMapper.readTree(json);

      ValidationResult result = jqEvaluator.eval("(.updatedAt - .createdAt) > 500000", input);

      assertTrue(result.ok());
      assertTrue(result.isTrue());
    }
  }
}
