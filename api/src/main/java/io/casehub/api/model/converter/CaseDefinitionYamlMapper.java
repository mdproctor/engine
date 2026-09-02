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
package io.casehub.api.model.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import java.io.IOException;
import java.io.InputStream;
import org.jboss.logging.Logger;

/**
 * Centralized YAML marshaller for CaseDefinition.
 *
 * <p>Reads YAML CaseDefinition files and deserializes directly to API models via {@link
 * CaseDefinitionModule}. Post-processing of worker functions and GOAP shorthands is handled by
 * {@link YamlCaseDefinitionConverter}.
 *
 * <p>Use {@link #load(InputStream, ObjectMapper, ExpressionEngineRegistry,
 * WorkerFunctionProviderRegistry)} in CDI contexts. Use {@link #load(InputStream)} for non-CDI
 * contexts (tests, tooling) — JQ only.
 */
public final class CaseDefinitionYamlMapper {

  private static final Logger LOG = Logger.getLogger(CaseDefinitionYamlMapper.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** JQ-only registry for non-CDI contexts. Does not support custom expression languages. */
  private static final ExpressionEngineRegistry JQ_ONLY =
      new ExpressionEngineRegistry() {
        @Override
        public ExpressionEvaluator create(final String expression, final String expressionLang) {
          if (!JQExpressionEvaluator.TYPE.equals(expressionLang)) {
            throw new IllegalArgumentException(
                "No CDI registry available; only '"
                    + JQExpressionEvaluator.TYPE
                    + "' is supported without injection. Got: "
                    + expressionLang);
          }
          return new JQExpressionEvaluator(expression);
        }

        @Override
        public void assertLanguageSupported(final String expressionLang) {
          if (!JQExpressionEvaluator.TYPE.equals(expressionLang)) {
            throw new IllegalArgumentException(
                "No CDI registry available; only '"
                    + JQExpressionEvaluator.TYPE
                    + "' is supported without injection. Got: "
                    + expressionLang);
          }
        }

        @Override
        public boolean evaluate(final ExpressionEvaluator evaluator, final CaseContext context) {
          throw new UnsupportedOperationException(
              "JQ_ONLY loading registry does not support evaluation");
        }

        @Override
        public boolean evaluate(final ExpressionEvaluator evaluator, final JsonNode asNode) {
          throw new UnsupportedOperationException(
              "JQ_ONLY loading registry does not support evaluation");
        }

        @Override
        public void validate(final ExpressionEvaluator evaluator) {
          // no-op: loading-only registry; validation occurs through the CDI path
          // during case definition registration in DefaultCaseDefinitionRegistry
        }

        @Override
        public java.util.List<JsonNode> transform(
            final ExpressionEvaluator evaluator, final JsonNode input) {
          throw new UnsupportedOperationException(
              "JQ_ONLY loading registry does not support transformation");
        }

        @Override
        public java.util.Optional<String> extractString(
            final ExpressionEvaluator evaluator, final CaseContext context) {
          throw new UnsupportedOperationException(
              "JQ_ONLY loading registry does not support string extraction");
        }
      };

  /**
   * Empty WorkerFunctionProviderRegistry for non-CDI contexts. Returns null for all worker nodes,
   * causing mapper to use API-local construction (agent, sync).
   */
  private static final WorkerFunctionProviderRegistry EMPTY_PROVIDERS = rawWorkerNode -> null;

  private CaseDefinitionYamlMapper() {}

  /**
   * Loads a CaseDefinition from a YAML InputStream using the CDI-managed ObjectMapper and
   * ExpressionEngineRegistry. Supports all registered expression languages.
   *
   * @param yamlStream InputStream containing YAML CaseDefinition
   * @param objectMapper ObjectMapper configured for YAML (with config/secret placeholder support)
   * @param registry ExpressionEngineRegistry for creating evaluators from YAML expression strings
   * @param providerRegistry WorkerFunctionProviderRegistry for SDK-dependent worker construction
   * @return API model CaseDefinition
   * @throws IOException if reading or parsing fails
   */
  public static CaseDefinition load(
      final InputStream yamlStream,
      final ObjectMapper objectMapper,
      final ExpressionEngineRegistry registry,
      final WorkerFunctionProviderRegistry providerRegistry)
      throws IOException {
    if (yamlStream == null) {
      throw new IllegalArgumentException("InputStream cannot be null");
    }
    final byte[] bytes = yamlStream.readAllBytes();
    final JsonNode rawNode = objectMapper.readTree(bytes);
    final JsonNode processedNode = flattenExpressionOverrides(rawNode, objectMapper);
    final JsonNode expandedNode = expandForEach(processedNode, objectMapper);
    final com.fasterxml.jackson.databind.ObjectMapper moduleMapper =
        objectMapper
            .copy()
            .registerModule(new CaseDefinitionModule(registry != null ? registry : JQ_ONLY))
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    moduleMapper.addHandler(UnknownPropertyWarningHandler.INSTANCE);
    final io.casehub.api.model.converter.yaml.YamlCaseDefinition yaml =
        deserializeYaml(expandedNode, moduleMapper);
    return YamlCaseDefinitionConverter.convert(
        yaml,
        registry != null ? registry : JQ_ONLY,
        providerRegistry != null ? providerRegistry : EMPTY_PROVIDERS);
  }

  /**
   * Loads a CaseDefinition with variable resolution. Variables like {@code ${env.X}} and {@code
   * ${config.X}} are resolved before Jackson deserialization. The {@code each} prefix is deferred —
   * it is resolved during forEach expansion.
   *
   * @param yamlStream InputStream containing YAML CaseDefinition
   * @param objectMapper ObjectMapper configured for YAML
   * @param registry ExpressionEngineRegistry for creating evaluators
   * @param providerRegistry WorkerFunctionProviderRegistry for SDK-dependent worker construction
   * @param variableSources prefix-keyed variable sources (e.g., "env" → System::getenv)
   * @return API model CaseDefinition
   * @throws IOException if reading or parsing fails
   */
  public static CaseDefinition load(
      final InputStream yamlStream,
      final ObjectMapper objectMapper,
      final ExpressionEngineRegistry registry,
      final WorkerFunctionProviderRegistry providerRegistry,
      final java.util.Map<String, io.casehub.yaml.core.resolver.VariableSource> variableSources)
      throws IOException {
    if (yamlStream == null) {
      throw new IllegalArgumentException("InputStream cannot be null");
    }
    final byte[] bytes = yamlStream.readAllBytes();
    final JsonNode rawNode = objectMapper.readTree(bytes);
    final JsonNode processedNode = flattenExpressionOverrides(rawNode, objectMapper);
    final JsonNode resolvedNode = resolveVariables(processedNode, objectMapper, variableSources);
    final JsonNode expandedNode = expandForEach(resolvedNode, objectMapper);
    final com.fasterxml.jackson.databind.ObjectMapper moduleMapper =
        objectMapper
            .copy()
            .registerModule(new CaseDefinitionModule(registry != null ? registry : JQ_ONLY))
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    moduleMapper.addHandler(UnknownPropertyWarningHandler.INSTANCE);
    final io.casehub.api.model.converter.yaml.YamlCaseDefinition yaml =
        deserializeYaml(expandedNode, moduleMapper);
    return YamlCaseDefinitionConverter.convert(
        yaml,
        registry != null ? registry : JQ_ONLY,
        providerRegistry != null ? providerRegistry : EMPTY_PROVIDERS);
  }

  /**
   * Loads a CaseDefinition from a pre-merged JsonNode. For use with the YAML overlay/merge pipeline
   * where base and overlay documents have already been merged via YamlMerger.
   *
   * @param mergedNode pre-merged JsonNode containing the complete case definition
   * @param objectMapper ObjectMapper for type conversion
   * @param registry ExpressionEngineRegistry (nullable — falls back to JQ-only)
   * @param providerRegistry WorkerFunctionProviderRegistry (nullable — falls back to no-op)
   * @return API model CaseDefinition
   */
  public static CaseDefinition load(
      final JsonNode mergedNode,
      final ObjectMapper objectMapper,
      final ExpressionEngineRegistry registry,
      final WorkerFunctionProviderRegistry providerRegistry) {
    if (mergedNode == null) {
      throw new IllegalArgumentException("JsonNode cannot be null");
    }
    final JsonNode processedNode = flattenExpressionOverrides(mergedNode, objectMapper);
    final JsonNode expandedNode = expandForEach(processedNode, objectMapper);
    final com.fasterxml.jackson.databind.ObjectMapper moduleMapper =
        objectMapper
            .copy()
            .registerModule(new CaseDefinitionModule(registry != null ? registry : JQ_ONLY))
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    moduleMapper.addHandler(UnknownPropertyWarningHandler.INSTANCE);
    final io.casehub.api.model.converter.yaml.YamlCaseDefinition yaml =
        deserializeYaml(expandedNode, moduleMapper);
    return YamlCaseDefinitionConverter.convert(
        yaml,
        registry != null ? registry : JQ_ONLY,
        providerRegistry != null ? providerRegistry : EMPTY_PROVIDERS);
  }

  /**
   * Resolves a YAML expression node to an {@link ExpressionEvaluator}.
   *
   * <p>Accepts two forms:
   *
   * <ul>
   *   <li>String: {@code ".amount > 1000"} — uses {@code defaultLang}
   *   <li>Single-key map: {@code {mvel: "transaction.amount > 1000"}} — language is the map key
   * </ul>
   *
   * @param node raw YAML node (null or NullNode returns null)
   * @param registry registry for creating evaluators
   * @param defaultLang language to use when {@code node} is a plain string
   * @return ExpressionEvaluator, or null if node is absent/null
   */
  public static ExpressionEvaluator resolveExpression(
      final JsonNode node, final ExpressionEngineRegistry registry, final String defaultLang) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return registry.create(node.asText(), defaultLang);
    }
    if (node.isObject()) {
      if (node.size() == 0) {
        throw new IllegalArgumentException(
            "Expression must be a single-key map {lang: expr}, got empty map");
      }
      if (node.size() > 1) {
        throw new IllegalArgumentException(
            "Expression must be a single-key map {lang: expr}, got " + node.size() + " keys");
      }
      java.util.Map.Entry<String, JsonNode> entry = node.fields().next();
      return registry.create(entry.getValue().asText(), entry.getKey());
    }
    throw new IllegalArgumentException(
        "Expression must be a string or single-key map {lang: expr}, got "
            + node.getNodeType().name());
  }

  private static io.casehub.api.model.converter.yaml.YamlCaseDefinition deserializeYaml(
      JsonNode node, ObjectMapper moduleMapper) {
    String expressionLang = resolveExpressionLang(node);
    com.fasterxml.jackson.databind.ObjectReader reader =
        moduleMapper.readerFor(io.casehub.api.model.converter.yaml.YamlCaseDefinition.class);
    if (expressionLang != null) {
      reader =
          reader.withAttribute(
              io.casehub.api.model.converter.deser.ExpressionEvaluatorDeserializer
                  .EXPRESSION_LANG_KEY,
              expressionLang);
    }
    try {
      return reader.readValue(node);
    } catch (java.io.IOException e) {
      if (e.getCause() instanceof IllegalArgumentException iae) {
        throw iae;
      }
      throw new IllegalArgumentException("Failed to deserialize YAML CaseDefinition", e);
    }
  }

  private static String resolveExpressionLang(JsonNode node) {
    if (node.has("expressionLang") && !node.get("expressionLang").isNull()) {
      return node.get("expressionLang").asText();
    }
    if (node.has("contextType") && !node.get("contextType").isNull()) {
      return "mvel";
    }
    return null;
  }

  private static JsonNode flattenExpressionOverrides(JsonNode node, ObjectMapper mapper) {
    if (!node.has("labelRules")) {
      return node;
    }
    JsonNode rules = node.get("labelRules");
    boolean needsFlatten = false;
    for (JsonNode rule : rules) {
      if (rule.has("when") && rule.get("when").isObject()) {
        needsFlatten = true;
        break;
      }
    }
    if (!needsFlatten) {
      return node;
    }
    com.fasterxml.jackson.databind.node.ObjectNode copy = mapper.valueToTree(node);
    com.fasterxml.jackson.databind.node.ArrayNode rulesArr =
        (com.fasterxml.jackson.databind.node.ArrayNode) copy.get("labelRules");
    for (int i = 0; i < rulesArr.size(); i++) {
      com.fasterxml.jackson.databind.node.ObjectNode rule =
          (com.fasterxml.jackson.databind.node.ObjectNode) rulesArr.get(i);
      JsonNode when = rule.get("when");
      if (when != null && when.isObject() && when.size() == 1) {
        rule.put("when", when.fields().next().getValue().asText());
      }
    }
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static JsonNode resolveVariables(
      JsonNode node,
      ObjectMapper mapper,
      java.util.Map<String, io.casehub.yaml.core.resolver.VariableSource> sources) {
    if (sources == null || sources.isEmpty()) {
      return node;
    }
    io.casehub.yaml.core.resolver.VariableResolver resolver =
        new io.casehub.yaml.core.resolver.VariableResolver(sources, java.util.Set.of("each"));
    Object raw = mapper.convertValue(node, Object.class);
    Object resolved = resolver.resolve(raw);
    return mapper.valueToTree(resolved);
  }

  private static JsonNode expandForEach(JsonNode node, ObjectMapper mapper) {
    JsonNode iterationsNode = node.get("iterations");
    if (iterationsNode == null || !iterationsNode.isObject() || iterationsNode.isEmpty()) {
      return node;
    }

    java.util.Map<String, io.casehub.yaml.core.foreach.IterationGroup> groups =
        new java.util.LinkedHashMap<>();
    iterationsNode
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode group = entry.getValue();
              java.util.List<String> in = new java.util.ArrayList<>();
              if (group.has("in") && group.get("in").isArray()) {
                group.get("in").forEach(v -> in.add(v.asText()));
              }
              String as = group.has("as") ? group.get("as").asText() : entry.getKey();
              groups.put(entry.getKey(), new io.casehub.yaml.core.foreach.IterationGroup(as, in));
            });

    io.casehub.yaml.core.resolver.VariableResolver resolver =
        new io.casehub.yaml.core.resolver.VariableResolver(java.util.Map.of(), java.util.Set.of());

    com.fasterxml.jackson.databind.node.ObjectNode result = mapper.valueToTree(node);

    io.casehub.api.model.converter.yaml.JsonNodeForEachAdapter workerAdapter =
        new io.casehub.api.model.converter.yaml.JsonNodeForEachAdapter(mapper, "forEach", null);
    io.casehub.api.model.converter.yaml.JsonNodeForEachAdapter bindingAdapter =
        new io.casehub.api.model.converter.yaml.JsonNodeForEachAdapter(mapper, "forEach", null);

    expandArray(result, "workers", groups, resolver, workerAdapter, mapper);
    expandArray(result, "bindings", groups, resolver, bindingAdapter, mapper);

    if (result.has("spec") && result.get("spec").isObject()) {
      com.fasterxml.jackson.databind.node.ObjectNode spec =
          (com.fasterxml.jackson.databind.node.ObjectNode) result.get("spec");
      expandArray(spec, "workers", groups, resolver, workerAdapter, mapper);
      expandArray(spec, "bindings", groups, resolver, bindingAdapter, mapper);
    }

    return result;
  }

  private static void expandArray(
      com.fasterxml.jackson.databind.node.ObjectNode parent,
      String fieldName,
      java.util.Map<String, io.casehub.yaml.core.foreach.IterationGroup> groups,
      io.casehub.yaml.core.resolver.VariableResolver resolver,
      io.casehub.api.model.converter.yaml.JsonNodeForEachAdapter adapter,
      ObjectMapper mapper) {
    JsonNode arrayNode = parent.get(fieldName);
    if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
      return;
    }

    boolean hasForEach = false;
    for (JsonNode element : arrayNode) {
      if (element.has("forEach")) {
        hasForEach = true;
        break;
      }
    }
    if (!hasForEach) {
      return;
    }

    java.util.LinkedHashMap<String, JsonNode> elements = new java.util.LinkedHashMap<>();
    for (JsonNode element : arrayNode) {
      String id = adapter.getId(element);
      if (id == null) {
        throw new IllegalArgumentException(
            "Element in '" + fieldName + "' array missing 'name' field");
      }
      elements.put(id, element);
    }

    io.casehub.yaml.core.foreach.ExpansionResult<JsonNode> result =
        io.casehub.yaml.core.foreach.ForEachExpander.expand(
            elements, groups, resolver, adapter, 1000);

    com.fasterxml.jackson.databind.node.ArrayNode expanded = mapper.createArrayNode();
    result.elements().values().forEach(expanded::add);
    parent.set(fieldName, expanded);
  }

  /**
   * Loads a CaseDefinition from a YAML InputStream using a plain ObjectMapper and JQ-only
   * expression support.
   *
   * <p>For non-CDI contexts (tests, tooling). Does not support custom expression languages — use
   * {@link #load(InputStream, ObjectMapper, ExpressionEngineRegistry,
   * WorkerFunctionProviderRegistry)} in CDI deployments.
   *
   * @param yamlStream InputStream containing YAML CaseDefinition
   * @return API model CaseDefinition
   * @throws IOException if reading or parsing fails
   */
  public static CaseDefinition load(final InputStream yamlStream) throws IOException {
    return load(yamlStream, new ObjectMapper(new YAMLFactory()), JQ_ONLY, EMPTY_PROVIDERS);
  }
}
