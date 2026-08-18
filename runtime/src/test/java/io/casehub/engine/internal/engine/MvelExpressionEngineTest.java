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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.TypedMvelExpressionEvaluator;
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

  // ── evaluate (untyped — Map context) ──────────────────────────────────────

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

  // ── evaluate (typed — POJO context via TypedMvelExpressionEvaluator) ──────

  @Test
  void evaluate_typed_nestedPropertyAccess() throws Exception {
    JsonNode node = mapper.readTree("{\"transaction\": {\"amount\": 200, \"currency\": \"USD\"}}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(
            engine.evaluate(
                new TypedMvelExpressionEvaluator(
                    "transaction.amount > 100", TestTransactionCase.class),
                context))
        .isTrue();
  }

  @Test
  void evaluate_typed_nestedPropertyFalse() throws Exception {
    JsonNode node = mapper.readTree("{\"transaction\": {\"amount\": 50}}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(
            engine.evaluate(
                new TypedMvelExpressionEvaluator(
                    "transaction.amount > 100", TestTransactionCase.class),
                context))
        .isFalse();
  }

  @Test
  void evaluate_typed_nestedStringAccess() throws Exception {
    JsonNode node =
        mapper.readTree(
            "{\"transaction\": {\"amount\": 200, \"currency\": \"USD\"}, \"status\": \"active\"}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(
            engine.evaluate(
                new TypedMvelExpressionEvaluator(
                    "transaction.currency == \"USD\" && status == \"active\"",
                    TestTransactionCase.class),
                context))
        .isTrue();
  }

  // ── error resilience ───────────────────────────────────────────────────────

  @Test
  void evaluate_typed_deserializationFailure_returnsFalse() throws Exception {
    JsonNode node = mapper.readTree("{\"incompatible\": \"data\"}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(
            engine.evaluate(
                new TypedMvelExpressionEvaluator(
                    "transaction.amount > 100", TestTransactionCase.class),
                context))
        .isFalse();
  }

  @Test
  void evaluate_untyped_invalidExpression_returnsFalse() throws Exception {
    JsonNode node = mapper.readTree("{\"amount\": 200}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.evaluate(new MvelExpressionEvaluator("nonexistent.deep.path > 100"), context))
        .isFalse();
  }

  // ── validate ──────────────────────────────────────────────────────────────

  @Test
  void validate_nullEvaluator_noException() {
    engine.validate((io.casehub.platform.api.expression.ExpressionEvaluator) null);
  }

  @Test
  void validate_blankExpression_noException() {
    engine.validate(new MvelExpressionEvaluator("   "));
  }

  @Test
  void validate_validExpression_noException() {
    engine.validate(new MvelExpressionEvaluator("amount > 100"));
  }

  @Test
  void validate_emptyString_delegatesToPlatform() {
    assertThatThrownBy(() -> engine.validate("")).isInstanceOf(Exception.class);
  }

  // ── extractString ─────────────────────────────────────────────────────────

  @Test
  void extractString_nullEvaluator_returnsEmpty() {
    CaseContext context = new CaseContextImpl(mapper.createObjectNode());

    assertThat(engine.extractString(null, context)).isEmpty();
  }

  @Test
  void extractString_blankExpression_returnsEmpty() {
    CaseContext context = new CaseContextImpl(mapper.createObjectNode());

    assertThat(engine.extractString(new MvelExpressionEvaluator("   "), context)).isEmpty();
  }

  @Test
  void extractString_returnsStringValue() throws Exception {
    JsonNode node = mapper.readTree("{\"name\": \"Alice\"}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.extractString(new MvelExpressionEvaluator("name"), context))
        .hasValue("Alice");
  }

  @Test
  void extractString_returnsNumericAsString() throws Exception {
    JsonNode node = mapper.readTree("{\"count\": 42}");
    CaseContext context = new CaseContextImpl(node);

    assertThat(engine.extractString(new MvelExpressionEvaluator("count"), context)).hasValue("42");
  }

  // ── compile delegation ────────────────────────────────────────────────────

  // ── POJO cache (#926) ─────────────────────────────────────────────────────

  @Test
  void evaluate_typed_sameContextVersion_cachesPojoAcrossCalls() throws Exception {
    JsonNode node = mapper.readTree("{\"transaction\": {\"amount\": 200}}");
    CaseContext context = new CaseContextImpl(node);
    TypedMvelExpressionEvaluator eval =
        new TypedMvelExpressionEvaluator("transaction.amount > 100", TestTransactionCase.class);

    engine.evaluate(eval, context);
    Object firstPojo = engine.cachedPojoForTesting();
    assertThat(firstPojo).isNotNull();

    engine.evaluate(eval, context);
    Object secondPojo = engine.cachedPojoForTesting();
    assertThat(secondPojo).isSameAs(firstPojo);
  }

  @Test
  void evaluate_typed_versionChange_invalidatesCache() throws Exception {
    JsonNode node = mapper.readTree("{\"transaction\": {\"amount\": 200}}");
    CaseContext context = new CaseContextImpl(node);
    TypedMvelExpressionEvaluator eval =
        new TypedMvelExpressionEvaluator("transaction.amount > 100", TestTransactionCase.class);

    engine.evaluate(eval, context);
    Object firstPojo = engine.cachedPojoForTesting();

    context.set("status", "changed");

    engine.evaluate(eval, context);
    Object secondPojo = engine.cachedPojoForTesting();
    assertThat(secondPojo).isNotSameAs(firstPojo);
  }

  @Test
  void evaluate_typed_differentContext_invalidatesCache() throws Exception {
    JsonNode node1 = mapper.readTree("{\"transaction\": {\"amount\": 200}}");
    CaseContext ctx1 = new CaseContextImpl(node1);
    JsonNode node2 = mapper.readTree("{\"transaction\": {\"amount\": 300}}");
    CaseContext ctx2 = new CaseContextImpl(node2);
    TypedMvelExpressionEvaluator eval =
        new TypedMvelExpressionEvaluator("transaction.amount > 100", TestTransactionCase.class);

    engine.evaluate(eval, ctx1);
    Object firstPojo = engine.cachedPojoForTesting();

    engine.evaluate(eval, ctx2);
    Object secondPojo = engine.cachedPojoForTesting();
    assertThat(secondPojo).isNotSameAs(firstPojo);
  }

  @Test
  void evaluate_untyped_doesNotPopulateCache() throws Exception {
    JsonNode node = mapper.readTree("{\"amount\": 200}");
    CaseContext context = new CaseContextImpl(node);

    engine.evaluate(new MvelExpressionEvaluator("amount > 100"), context);
    assertThat(engine.cachedPojoForTesting()).isNull();
  }

  @Test
  void extractString_typed_usesCache() throws Exception {
    JsonNode node = mapper.readTree("{\"transaction\": {\"amount\": 200, \"currency\": \"USD\"}}");
    CaseContext context = new CaseContextImpl(node);
    TypedMvelExpressionEvaluator boolEval =
        new TypedMvelExpressionEvaluator("transaction.amount > 100", TestTransactionCase.class);
    TypedMvelExpressionEvaluator strEval =
        new TypedMvelExpressionEvaluator("transaction.currency", TestTransactionCase.class);

    engine.evaluate(boolEval, context);
    Object pojoAfterEval = engine.cachedPojoForTesting();

    engine.extractString(strEval, context);
    Object pojoAfterExtract = engine.cachedPojoForTesting();
    assertThat(pojoAfterExtract).isSameAs(pojoAfterEval);
  }

  @Test
  void compile_delegatesToPlatform() {
    var compiled = engine.compile("amount > 100", java.util.Map.class, Boolean.class);
    assertThat(compiled).isNotNull();
    assertThat(compiled.type()).isEqualTo("mvel");
  }
}
