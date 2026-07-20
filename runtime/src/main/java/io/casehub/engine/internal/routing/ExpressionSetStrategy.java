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
package io.casehub.engine.internal.routing;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.spi.routing.CandidateSetContext;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExpressionSetStrategy implements CandidateSetStrategy {

  private final ExpressionEvaluator evaluator;
  private final ExpressionEngineRegistry registry;

  public ExpressionSetStrategy(ExpressionEvaluator evaluator, ExpressionEngineRegistry registry) {
    this.evaluator = evaluator;
    this.registry = registry;
  }

  public static ExpressionSetStrategy of(
      String expression, String lang, ExpressionEngineRegistry registry) {
    ExpressionEvaluator evaluator = registry.create(expression, lang);
    return new ExpressionSetStrategy(evaluator, registry);
  }

  public static ExpressionSetStrategy jq(String expression, ExpressionEngineRegistry registry) {
    return of(expression, "jq", registry);
  }

  @Override
  public String id() {
    return "expression";
  }

  @Override
  public Set<String> evaluate(CandidateSetContext context) {
    List<JsonNode> results = registry.transform(evaluator, context.caseContext());
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode node : results) {
      if (node.isArray()) {
        node.forEach(
            element -> {
              if (element.isTextual()) {
                values.add(element.asText());
              }
            });
      } else if (node.isTextual()) {
        values.add(node.asText());
      }
    }
    return Set.copyOf(values);
  }

  public ExpressionEvaluator evaluator() {
    return evaluator;
  }
}
