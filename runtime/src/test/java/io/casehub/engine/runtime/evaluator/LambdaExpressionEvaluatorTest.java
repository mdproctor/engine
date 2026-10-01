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
package io.casehub.engine.internal.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.engine.internal.context.CaseContextImpl;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LambdaExpressionEvaluator")
class LambdaExpressionEvaluatorTest {

  @Test
  @DisplayName("type() returns 'lambda'")
  void type_returnsLambda() {
    final var evaluator = new LambdaExpressionEvaluator(ctx -> true);
    assertEquals(LambdaExpressionEvaluator.TYPE, evaluator.type());
    assertEquals("lambda", evaluator.type());
  }

  @Test
  @DisplayName("test() returns true when predicate matches")
  void test_returnsTrueWhenPredicateMatches() {
    final var context = new CaseContextImpl(Map.of("status", "ready"));
    final var evaluator = new LambdaExpressionEvaluator(ctx -> "ready".equals(ctx.get("status")));

    assertTrue(evaluator.test(context));
  }

  @Test
  @DisplayName("test() returns false when predicate does not match")
  void test_returnsFalseWhenPredicateDoesNotMatch() {
    final var context = new CaseContextImpl(Map.of("status", "pending"));
    final var evaluator = new LambdaExpressionEvaluator(ctx -> "ready".equals(ctx.get("status")));

    assertFalse(evaluator.test(context));
  }

  @Test
  @DisplayName("test() receives the exact context passed in")
  void test_receivesExactContext() {
    final var context = new CaseContextImpl(Map.of("count", 42));
    final var evaluator = new LambdaExpressionEvaluator(ctx -> ctx.get("count") != null);

    assertTrue(evaluator.test(context));
  }
}
