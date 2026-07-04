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
package io.casehub.api.spi.routing;

import io.smallrye.mutiny.Uni;
import java.util.LinkedHashSet;
import java.util.Set;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

/**
 * Self-contained JQ-based {@link CandidateSetStrategy} value object for the fluent builder API.
 *
 * <p>Compiles and evaluates the JQ expression using jackson-jq directly, without needing {@code
 * ExpressionEngineRegistry}. Suitable for programmatic case definitions where no CDI context is
 * available. For YAML-loaded definitions, {@code ExpressionSetStrategy} (in the runtime module) is
 * preferred because it delegates to the pluggable expression engine SPI.
 */
public final class JqCandidateSetStrategy implements CandidateSetStrategy {

  private static final Scope ROOT_SCOPE;

  static {
    ROOT_SCOPE = Scope.newEmptyScope();
    BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, ROOT_SCOPE);
  }

  private final String expression;
  private final JsonQuery compiledQuery;

  public JqCandidateSetStrategy(String expression) {
    this.expression = expression;
    try {
      this.compiledQuery = JsonQuery.compile(expression, Versions.JQ_1_6);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JQ expression: " + expression, e);
    }
  }

  @Override
  public String id() {
    return "jq";
  }

  @Override
  public Uni<Set<String>> evaluate(CandidateSetContext context) {
    return Uni.createFrom()
        .item(
            () -> {
              try {
                Set<String> values = new LinkedHashSet<>();
                Scope childScope = Scope.newChildScope(ROOT_SCOPE);
                compiledQuery.apply(
                    childScope,
                    context.caseContext(),
                    node -> {
                      if (node.isArray()) {
                        node.forEach(
                            element -> {
                              if (element.isTextual()) values.add(element.asText());
                            });
                      } else if (node.isTextual()) {
                        values.add(node.asText());
                      }
                    });
                return Set.copyOf(values);
              } catch (Exception e) {
                throw new RuntimeException("JQ evaluation failed for expression: " + expression, e);
              }
            });
  }

  public String expression() {
    return expression;
  }
}
