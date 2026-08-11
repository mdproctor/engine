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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.api.spi.ExchangeProjectionStrategy;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.worker.api.Exchange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class CustomJqProjection implements ExchangeProjectionStrategy {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final JQEvaluator jqEvaluator;

  @Inject
  public CustomJqProjection(JQEvaluator jqEvaluator) {
    this.jqEvaluator = jqEvaluator;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> project(Exchange<?> exchange, ProjectionContext context) {
    if (context.expression() == null) {
      return Map.of();
    }

    ObjectNode input = MAPPER.createObjectNode();
    input.set("body", MAPPER.valueToTree(exchange.body()));
    input.set("headers", MAPPER.valueToTree(exchange.headers()));

    ValidationResult result = jqEvaluator.eval(context.expression(), input);
    if (!result.ok() || result.output() == null || result.output().isEmpty()) {
      return Map.of();
    }

    JsonNode output = result.output().get(0);
    if (!output.isObject()) {
      return Map.of();
    }

    return MAPPER.convertValue(output, Map.class);
  }

  @Override
  public String id() {
    return "jq";
  }
}
