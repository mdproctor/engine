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
package io.casehub.engine.common.internal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.ExchangeProjectionStrategy.ProjectionContext;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.worker.api.Exchange;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExchangeProjectionStrategyTest {

  private static final ProjectionContext CTX = new ProjectionContext("enricher");

  @Nested
  class DualWriteProjectionTest {

    private final DualWriteProjection strategy = new DualWriteProjection();

    @Test
    void projectsBodyKeysToResult() {
      Exchange<Map<String, Object>> exchange = Exchange.of(Map.of("result", "done", "score", 42));

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).containsEntry("result", "done");
      assertThat(projected).containsEntry("score", 42);
    }

    @Test
    void returnsEmptyMapForNullBody() {
      Exchange<String> exchange = Exchange.of(null);

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).isEmpty();
    }

    @Test
    void convertsPojoBodvToMap() {
      record Result(String status, int count) {}
      Exchange<Result> exchange = Exchange.of(new Result("ok", 5));

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).containsEntry("status", "ok");
      assertThat(projected).containsEntry("count", 5);
    }

    @Test
    void headersNotIncludedInProjection() {
      Exchange<Map<String, Object>> exchange =
          Exchange.of(Map.of("key", "val"), Map.of("correlationId", "abc"));

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).doesNotContainKey("correlationId");
      assertThat(projected).containsEntry("key", "val");
    }

    @Test
    void hasCorrectId() {
      assertThat(strategy.id()).isEqualTo("dual-write");
    }
  }

  @Nested
  class ExchangeOnlyProjectionTest {

    private final ExchangeOnlyProjection strategy = new ExchangeOnlyProjection();

    @Test
    void returnsEmptyMap() {
      Exchange<Map<String, Object>> exchange =
          Exchange.of(Map.of("data", "value"), Map.of("header", "val"));

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).isEmpty();
    }

    @Test
    void hasCorrectId() {
      assertThat(strategy.id()).isEqualTo("exchange-only");
    }
  }

  @Nested
  class FullProjectionTest {

    private final FullProjection strategy = new FullProjection();

    @Test
    void projectsBodyAndHeaders() {
      Exchange<Map<String, Object>> exchange =
          new Exchange<>(Map.of("result", "done"), Map.of("source", "system-a"), Map.of());

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).containsEntry("result", "done");
      assertThat(projected).containsKey("_exchange.enricher.headers");
      @SuppressWarnings("unchecked")
      Map<String, Object> headers =
          (Map<String, Object>) projected.get("_exchange.enricher.headers");
      assertThat(headers).containsEntry("source", "system-a");
    }

    @Test
    void usesBindingNameInHeaderNamespace() {
      Exchange<Map<String, Object>> exchange = new Exchange<>(Map.of(), Map.of("h", "v"), Map.of());

      ProjectionContext ctx = new ProjectionContext("my-binding");
      Map<String, Object> projected = strategy.project(exchange, ctx);

      assertThat(projected).containsKey("_exchange.my-binding.headers");
    }

    @Test
    void omitsHeadersKeyWhenNoHeaders() {
      Exchange<Map<String, Object>> exchange = Exchange.of(Map.of("key", "val"));

      Map<String, Object> projected = strategy.project(exchange, CTX);

      assertThat(projected).doesNotContainKey("_exchange.enricher.headers");
      assertThat(projected).containsEntry("key", "val");
    }

    @Test
    void hasCorrectId() {
      assertThat(strategy.id()).isEqualTo("full");
    }
  }

  @Nested
  class CustomJqProjectionTest {

    private final CustomJqProjection strategy = createJqProjection();

    @Test
    void evaluatesJqExpressionAgainstBody() {
      Exchange<Map<String, Object>> exchange =
          Exchange.of(Map.of("transaction", Map.of("amount", 100, "currency", "USD")));

      ProjectionContext ctx =
          new ProjectionContext("auditor", "{ txAmount: .body.transaction.amount }");
      Map<String, Object> projected = strategy.project(exchange, ctx);

      assertThat(projected).containsEntry("txAmount", 100);
    }

    @Test
    void accessesHeadersViaJq() {
      Exchange<Map<String, Object>> exchange =
          new Exchange<>(Map.of("data", "val"), Map.of("source", "system-a"), Map.of());

      ProjectionContext ctx = new ProjectionContext("auditor", "{ origin: .headers.source }");
      Map<String, Object> projected = strategy.project(exchange, ctx);

      assertThat(projected).containsEntry("origin", "system-a");
    }

    @Test
    void returnsEmptyMapWhenExpressionNull() {
      Exchange<Map<String, Object>> exchange = Exchange.of(Map.of("key", "val"));
      ProjectionContext ctx = new ProjectionContext("binding", null);

      Map<String, Object> projected = strategy.project(exchange, ctx);

      assertThat(projected).isEmpty();
    }

    @Test
    void hasCorrectId() {
      assertThat(strategy.id()).isEqualTo("jq");
    }
  }

  private static CustomJqProjection createJqProjection() {
    try {
      JQEvaluator evaluator = new JQEvaluator();
      java.lang.reflect.Method init = JQEvaluator.class.getDeclaredMethod("init");
      init.setAccessible(true);
      init.invoke(evaluator);
      return new CustomJqProjection(evaluator);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
