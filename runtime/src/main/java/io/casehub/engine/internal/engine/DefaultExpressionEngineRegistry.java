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

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.engine.internal.context.CaseContextImpl;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Default implementation of {@link ExpressionEngineRegistry}.
 *
 * <p>Dispatches expression evaluation to the appropriate {@link ExpressionEngine} by evaluator
 * type. All CDI beans implementing {@link ExpressionEngine} are discovered automatically. Add a new
 * engine bean to support additional expression languages without modifying this class or the
 * runtime.
 */
@ApplicationScoped
public class DefaultExpressionEngineRegistry implements ExpressionEngineRegistry {

  private static final Logger LOG = Logger.getLogger(DefaultExpressionEngineRegistry.class);

  @Inject Instance<ExpressionEngine> engines;

  @Inject io.casehub.platform.api.expression.ExpressionEngineRegistry platformRegistry;

  private Map<String, ExpressionEngine> engineMap;

  DefaultExpressionEngineRegistry() {}

  DefaultExpressionEngineRegistry(
      final Map<String, ExpressionEngine> engineMap,
      final io.casehub.platform.api.expression.ExpressionEngineRegistry platformRegistry) {
    this.engineMap = Map.copyOf(engineMap);
    this.platformRegistry = platformRegistry;
  }

  @PostConstruct
  void init() {
    final var map = new LinkedHashMap<String, ExpressionEngine>();
    for (final ExpressionEngine engine : engines) {
      map.put(engine.type(), engine);
      platformRegistry.register(engine);
    }
    engineMap = Map.copyOf(map);
  }

  private ExpressionEngine resolveEngine(final String type) {
    final ExpressionEngine engine = engineMap.get(type);
    if (engine == null) {
      throw new IllegalArgumentException("No ExpressionEngine registered for type '" + type + "'");
    }
    return engine;
  }

  @Override
  public boolean evaluate(final ExpressionEvaluator evaluator, final CaseContext context) {
    if (evaluator == null) {
      return true;
    }
    return resolveEngine(evaluator.type()).evaluate(evaluator, context);
  }

  @Override
  public boolean evaluate(final ExpressionEvaluator evaluator, final JsonNode asNode) {
    if (asNode == null) {
      throw new IllegalArgumentException("asNode must not be null");
    }
    return evaluate(evaluator, new CaseContextImpl(asNode));
  }

  @Override
  public void validate(final ExpressionEvaluator evaluator) {
    if (evaluator == null) {
      return;
    }
    resolveEngine(evaluator.type()).validate(evaluator);
  }

  @Override
  public ExpressionEvaluator create(final String expression, final String expressionLang) {
    final ExpressionEngine engine = resolveEngine(expressionLang);
    final ExpressionEvaluator evaluator = engine.create(expression);
    if (!evaluator.type().equals(expressionLang)) {
      throw new IllegalStateException(
          "ExpressionEngine '"
              + engine.type()
              + "'.create() returned evaluator of type '"
              + evaluator.type()
              + "' — must equal '"
              + expressionLang
              + "'");
    }
    return evaluator;
  }

  @Override
  public List<JsonNode> transform(final ExpressionEvaluator evaluator, final JsonNode input) {
    if (evaluator == null) {
      return List.of(input);
    }
    return resolveEngine(evaluator.type()).transform(evaluator, input);
  }

  @Override
  public void assertLanguageSupported(final String expressionLang) {
    final ExpressionEngine engine = resolveEngine(expressionLang);
    if (!engine.supportsStringCreation()) {
      throw new UnsupportedOperationException(
          "expressionLang '"
              + expressionLang
              + "' is a Java-DSL-only type and cannot be used in YAML definitions. "
              + "Use expressionLang: jq or register a custom ExpressionEngine "
              + "that overrides supportsStringCreation().");
    }
  }

  @Override
  public Optional<String> extractString(
      final ExpressionEvaluator evaluator, final CaseContext context) {
    if (evaluator == null) {
      return Optional.empty();
    }
    final String type = evaluator.type();
    for (ExpressionEngine engine : engines) {
      if (engine.type().equals(type)) {
        try {
          return engine.extractString(evaluator, context);
        } catch (UnsupportedOperationException e) {
          LOG.warnf(
              "ExpressionEngine '%s' does not support string extraction — returning empty", type);
          return Optional.empty();
        }
      }
    }
    throw new IllegalArgumentException("No ExpressionEngine registered for type '" + type + "'");
  }
}
