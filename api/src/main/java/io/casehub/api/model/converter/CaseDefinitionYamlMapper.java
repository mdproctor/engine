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
import io.casehub.api.context.JacksonPojoBridge;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.CognitiveDemand;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.ExecutionMode;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.InboundSignalMapping;
import io.casehub.api.model.LifecycleScope;
import io.casehub.api.model.MemoryRetrievalConfig;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.OutcomeType;
import io.casehub.api.model.Participation;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.RecoveryLevel;
import io.casehub.api.model.RecoveryOverride;
import io.casehub.api.model.RecoveryPolicy;
import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.api.model.SideEffectClassification;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.Use;
import io.casehub.api.model.WorkerFunctions;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.TypedMvelExpressionEvaluator;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import net.thisptr.jackson.jq.exception.JsonQueryException;
import org.jboss.logging.Logger;

/**
 * Centralized YAML marshaller for CaseDefinition.
 *
 * <p>Reads YAML CaseDefinition files, deserializes to generated schema models (io.casehub.model.*),
 * and converts to API models (io.casehub.api.model.*).
 *
 * <p>Use {@link #load(InputStream, ObjectMapper, ExpressionEngineRegistry)} in CDI contexts. Use
 * {@link #load(InputStream)} for non-CDI contexts (tests, tooling) — JQ only.
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
    final ObjectMapper lenient = createLenientMapper(objectMapper);
    JsonNode schemaNode = flattenExpressionOverrides(rawNode, objectMapper);
    final io.casehub.model.CaseDefinition schema =
        lenient.convertValue(schemaNode, io.casehub.model.CaseDefinition.class);
    return convertToApiModel(schema, rawNode, objectMapper, registry, providerRegistry);
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
    final ObjectMapper lenient = createLenientMapper(objectMapper);
    JsonNode schemaNode = flattenExpressionOverrides(mergedNode, objectMapper);
    final io.casehub.model.CaseDefinition schema =
        lenient.convertValue(schemaNode, io.casehub.model.CaseDefinition.class);
    return convertToApiModel(
        schema,
        mergedNode,
        objectMapper,
        registry != null ? registry : JQ_ONLY,
        providerRegistry != null ? providerRegistry : EMPTY_PROVIDERS);
  }

  private static ObjectMapper createLenientMapper(final ObjectMapper source) {
    final ObjectMapper lenient =
        source
            .copy()
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    lenient.addHandler(UnknownPropertyWarningHandler.INSTANCE);
    return lenient;
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

  /**
   * Converts generated schema model to API model.
   *
   * @param schema generated CaseDefinition from YAML
   * @param rawNode raw YAML parsed as JsonNode (for free-form fields)
   * @param objectMapper ObjectMapper for converting JsonNode to Map
   * @param registry registry for creating ExpressionEvaluator instances from string expressions
   * @param providerRegistry registry for SDK-dependent worker construction (flow, etc.)
   * @return API model CaseDefinition
   */
  private static CaseDefinition convertToApiModel(
      final io.casehub.model.CaseDefinition schema,
      final JsonNode rawNode,
      final ObjectMapper objectMapper,
      final ExpressionEngineRegistry registry,
      final WorkerFunctionProviderRegistry providerRegistry) {
    Class<?> contextClass = null;
    if (schema.getContextType() != null) {
      try {
        contextClass = Class.forName(schema.getContextType());
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "contextType class not found: " + schema.getContextType(), e);
      }
    }

    final String expressionLang;
    if (rawNode.has("expressionLang") && schema.getExpressionLang() != null) {
      expressionLang = schema.getExpressionLang();
    } else if (contextClass != null) {
      expressionLang = "mvel";
    } else {
      expressionLang = JQExpressionEvaluator.TYPE;
    }
    registry.assertLanguageSupported(expressionLang);

    final ExpressionEngineRegistry effectiveRegistry =
        contextClass != null && "mvel".equals(expressionLang)
            ? new TypedMvelRegistry(registry, contextClass)
            : registry;

    final CaseDefinition def =
        new CaseDefinition(schema.getNamespace(), schema.getName(), schema.getVersion());
    def.setDsl(schema.getDsl());
    def.setTitle(schema.getTitle());
    if (schema.getSummary() != null) {
      def.setSummary(schema.getSummary());
    }

    if (schema.getUse() != null) {
      final Use apiUse = new Use();
      if (schema.getUse().getSecrets() != null && !schema.getUse().getSecrets().isEmpty()) {
        apiUse.setSecrets(new LinkedHashSet<>(schema.getUse().getSecrets()));
      }
      if (schema.getUse().getConfigMaps() != null && !schema.getUse().getConfigMaps().isEmpty()) {
        apiUse.setConfigMaps(new LinkedHashSet<>(schema.getUse().getConfigMaps()));
      }
      def.setUse(apiUse);
    }

    if (contextClass != null) {
      def.setDefaultWorkerBridge(new JacksonPojoBridge<>(contextClass));
      def.setContextType(schema.getContextType());
    }
    def.setExpressionLang(expressionLang);

    // semanticData — free-form object; read directly from raw JsonNode to avoid empty generated
    // class
    final JsonNode semanticDataNode = rawNode.get("semanticData");
    if (semanticDataNode != null && !semanticDataNode.isNull() && semanticDataNode.isObject()) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> semData = objectMapper.convertValue(semanticDataNode, Map.class);
      def.setSemanticData(semData);
    }

    // episodic.memory config — typed via generated Episodic/Memory classes
    if (schema.getEpisodic() != null && schema.getEpisodic().getMemory() != null) {
      final io.casehub.model.Memory mem = schema.getEpisodic().getMemory();
      final int recent = mem.getRecent() != null ? mem.getRecent() : 10;
      def.setEpisodicMemoryConfig(
          EpisodicMemoryConfig.of(mem.getDomain(), mem.getEntityId(), recent));
    }

    // layers — user-defined layer names
    if (schema.getLayers() != null && !schema.getLayers().isEmpty()) {
      final List<String> layerNames =
          schema.getLayers().stream()
              .map(io.casehub.model.Layer::getName)
              .filter(java.util.Objects::nonNull)
              .toList();
      def.setLayerNames(layerNames);
    }

    // types — behavioral type classifications
    if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
      def.setTypes(
          schema.getTypes().stream()
              .map(io.casehub.platform.api.path.Path::parse)
              .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

    // labels — operational classification labels
    if (schema.getLabels() != null && !schema.getLabels().isEmpty()) {
      def.setLabels(
          schema.getLabels().stream()
              .map(io.casehub.platform.api.path.Path::parse)
              .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

    // context.storeFactory — read from raw node (not in generated schema)
    final JsonNode contextNode = rawNode.get("context");
    if (contextNode != null && contextNode.has("storeFactory")) {
      def.setContextStoreFactory(contextNode.get("storeFactory").asText());
    }

    // Convert capabilities — accepts both current (inputProjection/outputProjection) and
    // legacy (inputSchema/outputSchema) YAML field names for backward compatibility.
    final Map<String, Capability> capabilityMap = new LinkedHashMap<>();
    final Map<String, CapabilityTarget> capTargetMap = new LinkedHashMap<>();
    final Map<String, CognitiveDemand> cognitiveDemands = new LinkedHashMap<>();
    if (schema.getSpec() != null && schema.getSpec().getCapabilities() != null) {
      final JsonNode rawCaps =
          rawNode.has("spec") && rawNode.get("spec").has("capabilities")
              ? rawNode.get("spec").get("capabilities")
              : null;
      final List<io.casehub.model.Capability> schemaCaps = schema.getSpec().getCapabilities();
      for (int i = 0; i < schemaCaps.size(); i++) {
        final io.casehub.model.Capability sc = schemaCaps.get(i);
        final JsonNode rawCap = rawCaps != null && i < rawCaps.size() ? rawCaps.get(i) : null;

        String inputProj = sc.getInputProjection();
        if (inputProj == null && rawCap != null && rawCap.has("inputSchema")) {
          inputProj = rawCap.get("inputSchema").asText();
          LOG.warnf(
              "Capability '%s': 'inputSchema' is deprecated — use 'inputProjection'", sc.getName());
        }

        String outputProj = sc.getOutputProjection();
        if (outputProj == null && rawCap != null && rawCap.has("outputSchema")) {
          outputProj = rawCap.get("outputSchema").asText();
          LOG.warnf(
              "Capability '%s': 'outputSchema' is deprecated — use 'outputProjection'",
              sc.getName());
        }

        final Capability cap =
            Capability.builder()
                .name(sc.getName())
                .inputSchema(inputProj != null ? inputProj : ".")
                .outputSchema(outputProj != null ? outputProj : ".")
                .description(sc.getDescription())
                .build();
        capabilityMap.put(sc.getName(), cap);
        def.getCapabilities().add(cap);

        JsonNode rawInputNode =
            rawCap != null
                ? (rawCap.has("inputProjection")
                    ? rawCap.get("inputProjection")
                    : rawCap.has("inputSchema") ? rawCap.get("inputSchema") : null)
                : null;
        ExpressionEvaluator inputEval =
            resolveExpression(rawInputNode, effectiveRegistry, expressionLang);
        if (inputEval == null) inputEval = new JQExpressionEvaluator(cap.inputSchema());

        JsonNode rawOutputNode =
            rawCap != null
                ? (rawCap.has("outputProjection")
                    ? rawCap.get("outputProjection")
                    : rawCap.has("outputSchema") ? rawCap.get("outputSchema") : null)
                : null;
        ExpressionEvaluator outputEval =
            resolveExpression(rawOutputNode, effectiveRegistry, expressionLang);
        if (outputEval == null) outputEval = new JQExpressionEvaluator(cap.outputSchema());

        capTargetMap.put(sc.getName(), new CapabilityTarget(cap, inputEval, outputEval));

        if (rawCap != null && rawCap.has("cognitiveDemand")) {
          final JsonNode demandNode = rawCap.get("cognitiveDemand");
          final Map<String, Double> weights = new java.util.LinkedHashMap<>();
          demandNode
              .fields()
              .forEachRemaining(e -> weights.put(e.getKey(), e.getValue().asDouble()));
          cognitiveDemands.put(sc.getName(), new CognitiveDemand(weights));
        }
      }
    }

    // Convert workers — two-pass for sequence resolution
    final Map<String, Worker> builtWorkers = new LinkedHashMap<>();
    if (schema.getSpec() != null && schema.getSpec().getWorkers() != null) {
      final JsonNode rawWorkers = rawNode.get("spec").get("workers");

      // First pass: build workers with explicit functions
      int workerIndex = 0;
      for (io.casehub.model.Worker sw : schema.getSpec().getWorkers()) {
        for (String capName : sw.getCapabilities()) {
          if (!capabilityMap.containsKey(capName)) {
            LOG.warnf(
                "Worker '%s' references capability '%s' not defined in the capabilities section"
                    + " — may be added programmatically via augment()",
                sw.getName(), capName);
          }
        }

        final Worker.Builder workerBuilder =
            Worker.builder().name(sw.getName()).capabilityNames(sw.getCapabilities());

        // Skip sequence in first pass
        if (sw.getSequence() == null || sw.getSequence().isEmpty()) {
          final JsonNode rawWorkerNode = rawWorkers.get(workerIndex);

          // Try discovery first (for multi-worker providers like MCP)
          final java.util.List<io.casehub.api.spi.DiscoveredWorker> discovered =
              providerRegistry.discoverWorkers(rawWorkerNode);
          if (!discovered.isEmpty()) {
            for (final io.casehub.api.spi.DiscoveredWorker dw : discovered) {
              if (!capabilityMap.containsKey(dw.capability().name())) {
                capabilityMap.put(dw.capability().name(), dw.capability());
                def.getCapabilities().add(dw.capability());
              } else {
                LOG.debugf(
                    "Discovered capability '%s' from worker '%s' overridden by explicit YAML declaration",
                    dw.capability().name(), dw.workerName());
              }
              final Worker discoveredWorker =
                  Worker.builder()
                      .name(dw.workerName())
                      .capabilityName(dw.capability().name())
                      .function(dw.function())
                      .build();
              builtWorkers.put(dw.workerName(), discoveredWorker);
            }
            workerIndex++;
            continue;
          }

          // Try providers (for SDK-dependent types like flow)
          WorkerFunction<?, ?> function = providerRegistry.createFunction(rawWorkerNode);
          if (function == null) {
            // API-local construction (no external SDK dependency)
            if (sw.getAgent() != null) {
              JsonNode rawAgentNode = rawWorkerNode != null ? rawWorkerNode.get("agent") : null;
              final io.casehub.api.model.ai.Agent apiAgent =
                  AgentConverter.toApiAgent(
                      sw.getAgent(), rawAgentNode, effectiveRegistry, expressionLang);
              function = new AgentWorkerFunction(apiAgent);
            } else if (sw.getContextType() != null) {
              try {
                Class<?> contextType = Class.forName(sw.getContextType());
                Class<?> outType =
                    sw.getOutputType() != null
                        ? Class.forName(sw.getOutputType())
                        : java.util.Map.class;
                function =
                    new WorkerFunction.Sync<>(
                        contextType,
                        outType,
                        (input, scope) -> {
                          throw new UnsupportedOperationException(
                              "YAML-declared contextType worker '"
                                  + sw.getName()
                                  + "' has no in-process function — dispatch via external backend");
                        });
              } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(
                    "Worker '" + sw.getName() + "' type class not found: " + sw.getContextType(),
                    e);
              }
            } else {
              function = WorkerFunction.NONE;
            }
          }
          workerBuilder.function(function);
        } else {
          // Placeholder for sequence workers
          workerBuilder.function(WorkerFunction.NONE);
        }

        workerBuilder.description(sw.getDescription());
        if (sw.getExecutionPolicy() != null) {
          workerBuilder.executionPolicy(convertExecutionPolicy(sw.getExecutionPolicy()));
        }

        final Worker worker = workerBuilder.build();
        builtWorkers.put(sw.getName(), worker);
        workerIndex++;
      }

      // Second pass: resolve sequence references
      workerIndex = 0;
      for (io.casehub.model.Worker sw : schema.getSpec().getWorkers()) {
        if (sw.getSequence() != null && !sw.getSequence().isEmpty()) {
          final Worker worker = builtWorkers.get(sw.getName());
          final List<WorkerFunction> stepFunctions = new java.util.ArrayList<>();

          for (String stepName : sw.getSequence()) {
            final Worker stepWorker = builtWorkers.get(stepName);
            if (stepWorker == null) {
              throw new IllegalArgumentException(
                  "Worker '"
                      + sw.getName()
                      + "' sequence references unknown worker '"
                      + stepName
                      + "'");
            }
            stepFunctions.add(stepWorker.function());
          }

          // Replace the placeholder function with the sequence
          final WorkerFunction sequenceFunc =
              WorkerFunctions.sequence(stepFunctions.toArray(new WorkerFunction[0]));
          // Workers are immutable — need to rebuild
          final Worker updatedWorker =
              Worker.builder()
                  .name(worker.name())
                  .capabilityNames(worker.capabilityNames())
                  .function(sequenceFunc)
                  .executionPolicy(worker.executionPolicy())
                  .description(worker.description())
                  .build();
          builtWorkers.put(sw.getName(), updatedWorker);
        }
        workerIndex++;
      }

      // Add all to definition
      def.getWorkers().addAll(builtWorkers.values());

      // Per-worker GOAP shorthand: cost, effect, softDependency → GoapAction
      var workerGoapActions = new java.util.ArrayList<io.casehub.engine.plan.goap.GoapAction>();
      for (int wi = 0; rawWorkers != null && wi < rawWorkers.size(); wi++) {
        JsonNode rawW = rawWorkers.get(wi);
        if (rawW == null) continue;
        boolean hasEffect = rawW.has("effect") && rawW.get("effect").isObject();
        boolean hasCost = rawW.has("cost");
        boolean hasSoftDep = rawW.has("softDependency") && rawW.get("softDependency").isArray();
        if (hasEffect || hasCost) {
          String capName =
              schema.getSpec().getWorkers().get(wi).getCapabilities() != null
                      && !schema.getSpec().getWorkers().get(wi).getCapabilities().isEmpty()
                  ? schema.getSpec().getWorkers().get(wi).getCapabilities().get(0)
                  : schema.getSpec().getWorkers().get(wi).getName();
          Map<String, Boolean> effects = parseBooleanMap(rawW.get("effect"));
          double cost = rawW.has("cost") ? rawW.get("cost").asDouble() : 1.0;
          Map<String, Boolean> softPrec = Map.of();
          if (hasSoftDep) {
            var sp = new java.util.LinkedHashMap<String, Boolean>();
            rawW.get("softDependency").forEach(e -> sp.put(e.asText(), true));
            softPrec = Map.copyOf(sp);
          }
          workerGoapActions.add(
              new io.casehub.engine.plan.goap.GoapAction(
                  capName, Map.of(), effects, cost, 0, softPrec));
        }
      }
      if (!workerGoapActions.isEmpty()) {
        var existing =
            def.getGoapActions() != null
                ? new java.util.ArrayList<>(def.getGoapActions())
                : new java.util.ArrayList<io.casehub.engine.plan.goap.GoapAction>();
        existing.addAll(workerGoapActions);
        def.setGoapActions(existing);
      }
    }

    // Convert bindings
    if (schema.getSpec() != null && schema.getSpec().getBindings() != null) {
      JsonNode specNode = rawNode.has("spec") ? rawNode.get("spec") : rawNode;
      JsonNode bindingsNode = specNode.get("bindings");
      List<io.casehub.model.Binding> schemaBindings = schema.getSpec().getBindings();
      for (int i = 0; i < schemaBindings.size(); i++) {
        JsonNode rawBindingNode =
            bindingsNode != null && i < bindingsNode.size() ? bindingsNode.get(i) : null;
        final Binding binding =
            convertBinding(
                schemaBindings.get(i),
                rawBindingNode,
                capabilityMap,
                capTargetMap,
                effectiveRegistry,
                expressionLang);
        def.getBindings().add(binding);
      }
    }

    // Convert milestones
    if (schema.getSpec() != null && schema.getSpec().getMilestones() != null) {
      final JsonNode milestonesNode =
          rawNode.has("spec") && rawNode.get("spec").has("milestones")
              ? rawNode.get("spec").get("milestones")
              : null;
      final List<io.casehub.model.Milestone> schemaMilestones = schema.getSpec().getMilestones();
      for (int mi = 0; mi < schemaMilestones.size(); mi++) {
        final io.casehub.model.Milestone sm = schemaMilestones.get(mi);
        final JsonNode rawMs =
            milestonesNode != null && mi < milestonesNode.size() ? milestonesNode.get(mi) : null;
        final Milestone.Builder milestoneBuilder =
            Milestone.builder()
                .name(sm.getName())
                .completionCriteria(
                    resolveExpression(
                        rawMs != null ? rawMs.get("condition") : null,
                        effectiveRegistry,
                        expressionLang));

        if (rawMs != null && rawMs.has("entryCriteria")) {
          milestoneBuilder.entryCriteria(
              resolveExpression(rawMs.get("entryCriteria"), effectiveRegistry, expressionLang));
        }

        if (sm.getSlaDuration() != null) {
          final Duration duration;
          try {
            duration = Duration.parse(sm.getSlaDuration());
          } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Milestone '"
                    + sm.getName()
                    + "' has invalid slaDuration '"
                    + sm.getSlaDuration()
                    + "' — must be ISO-8601 duration (e.g. PT2H)",
                e);
          }
          if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(
                "Milestone '"
                    + sm.getName()
                    + "' slaDuration must be positive, got '"
                    + sm.getSlaDuration()
                    + "'");
          }
          milestoneBuilder.slaDuration(duration);
        }

        if (sm.getSlaStartFrom() != null) {
          milestoneBuilder.slaStartFrom(SlaStartFrom.valueOf(sm.getSlaStartFrom().value()));
        }

        final Milestone milestone = milestoneBuilder.build();
        milestone.setDescription(sm.getDescription());
        def.getMilestones().add(milestone);
      }
    }

    // Convert goals
    final Map<String, Goal> goalMap = new LinkedHashMap<>();
    final JsonNode goalsNode =
        rawNode.has("spec") && rawNode.get("spec").has("goals")
            ? rawNode.get("spec").get("goals")
            : null;
    if (schema.getSpec() != null && schema.getSpec().getGoals() != null) {
      final List<io.casehub.model.Goal> schemaGoals = schema.getSpec().getGoals();
      for (int gi = 0; gi < schemaGoals.size(); gi++) {
        final io.casehub.model.Goal sg = schemaGoals.get(gi);
        final JsonNode rawGoal =
            goalsNode != null && gi < goalsNode.size() ? goalsNode.get(gi) : null;
        final Goal goal =
            new Goal(
                sg.getName(),
                resolveExpression(
                    rawGoal != null ? rawGoal.get("condition") : null,
                    effectiveRegistry,
                    expressionLang),
                sg.getKind() != null ? sg.getKind() : "success");
        goal.setDescription(sg.getDescription());
        goalMap.put(sg.getName(), goal);
        def.getGoals().add(goal);
      }
    }

    // Convert completion — read from raw JSON node to support open goal kind entries
    final JsonNode specNode = rawNode.get("spec");
    final JsonNode completionNode = specNode != null ? specNode.get("completion") : null;
    if (completionNode != null && completionNode.isObject()) {
      final JsonNode doneWhenNode =
          completionNode.has("doneWhen") ? completionNode.get("doneWhen") : null;
      var gbc = GoalBasedCompletion.builder();
      boolean hasGoalEntries = false;
      var fields = completionNode.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        String kindValue = entry.getKey();
        if ("doneWhen".equals(kindValue)) {
          continue;
        }
        hasGoalEntries = true;
        GoalKind kind = resolveGoalKind(kindValue, entry.getValue());
        GoalExpression expr = parseGoalExpressionFromNode(entry.getValue(), goalMap);
        gbc.goal(kind, expr);
      }
      if (doneWhenNode != null && hasGoalEntries) {
        throw new IllegalArgumentException(
            "Completion block cannot mix 'doneWhen' with goal kind entries"
                + " — use one completion mechanism per definition");
      }
      if (doneWhenNode != null) {
        def.setCompletion(
            new PredicateBasedCompletion(
                resolveExpression(doneWhenNode, effectiveRegistry, JQExpressionEvaluator.TYPE)));
      } else if (hasGoalEntries) {
        def.setCompletion(gbc.build());
      }
    }

    // Convert planningStrategy
    if (schema.getSpec() != null && schema.getSpec().getPlanningStrategy() != null) {
      def.setPlanningStrategy(schema.getSpec().getPlanningStrategy());
    }

    // Convert decompositionStrategy
    if (schema.getSpec() != null && schema.getSpec().getDecompositionStrategy() != null) {
      def.setDecompositionStrategy(schema.getSpec().getDecompositionStrategy());
    }
    if (schema.getSpec() != null && schema.getSpec().getMaxDecompositionDepth() != null) {
      def.setMaxDecompositionDepth(schema.getSpec().getMaxDecompositionDepth());
    }

    // Convert planningConstraints — read from raw spec node (Duration + Integer)
    if (specNode != null && specNode.has("planningConstraints")) {
      JsonNode pcNode = specNode.get("planningConstraints");
      java.time.Duration timeBudget =
          pcNode.has("timeBudget")
              ? java.time.Duration.parse(pcNode.get("timeBudget").asText())
              : null;
      Integer resourceLimit =
          pcNode.has("resourceLimit") ? pcNode.get("resourceLimit").asInt() : null;
      Map<String, Double> weights = new LinkedHashMap<>();
      if (pcNode.has("weights") && pcNode.get("weights").isObject()) {
        pcNode
            .get("weights")
            .fields()
            .forEachRemaining(e -> weights.put(e.getKey(), e.getValue().asDouble()));
      }
      Map<String, Integer> costBudgets = new LinkedHashMap<>();
      if (pcNode.has("costBudgets") && pcNode.get("costBudgets").isObject()) {
        pcNode
            .get("costBudgets")
            .fields()
            .forEachRemaining(e -> costBudgets.put(e.getKey(), e.getValue().asInt()));
      }
      def.setPlanningConstraints(
          new io.casehub.engine.plan.PlanningConstraints(
              timeBudget, resourceLimit, weights, costBudgets));
    }

    // Convert routing strategy IDs — read from raw spec node
    if (specNode != null) {
      if (specNode.has("agentRouting")) {
        def.setAgentRouting(specNode.get("agentRouting").asText());
      }
      if (specNode.has("implementationRouting")) {
        def.setImplementationRouting(specNode.get("implementationRouting").asText());
      }
      if (specNode.has("humanTaskRouting")) {
        def.setHumanTaskRouting(specNode.get("humanTaskRouting").asText());
      }
      if (specNode.has("candidateMatching")) {
        def.setCandidateMatching(specNode.get("candidateMatching").asText());
      }

      // Convert channel declarations
      if (specNode.has("channels")) {
        final List<io.casehub.api.model.ChannelDeclaration> channelDecls =
            new java.util.ArrayList<>();
        for (final JsonNode chNode : specNode.get("channels")) {
          final String chName = chNode.get("name").asText();
          final String recordTypeName = chNode.get("recordType").asText();
          try {
            final Class<?> recordType = Class.forName(recordTypeName);
            final String transport =
                chNode.has("transport") ? chNode.get("transport").asText() : "in-memory";
            final LifecycleScope scope =
                chNode.has("scope")
                    ? LifecycleScope.valueOf(chNode.get("scope").asText())
                    : LifecycleScope.CASE;
            channelDecls.add(
                new io.casehub.api.model.ChannelDeclaration(chName, recordType, transport, scope));
          } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(
                "Channel '" + chName + "' has unknown recordType: " + recordTypeName, e);
          }
        }
        def.setChannels(channelDecls);
      }
    }

    // Convert CBR configuration — features and weights read from raw node (generated classes
    // are empty shells for additionalProperties: maps)
    if (schema.getSpec() != null && schema.getSpec().getCbr() != null) {
      final io.casehub.model.Cbr cbr = schema.getSpec().getCbr();
      final io.casehub.api.model.cbr.CbrConfig.Builder cbrBuilder =
          io.casehub.api.model.cbr.CbrConfig.builder();

      final JsonNode cbrNode = specNode != null ? specNode.get("cbr") : null;

      if (cbrNode != null && cbrNode.has("features")) {
        final JsonNode featuresNode = cbrNode.get("features");
        featuresNode
            .fields()
            .forEachRemaining(e -> cbrBuilder.feature(e.getKey(), e.getValue().asText()));
      }

      if (cbrNode != null && cbrNode.has("weights")) {
        final JsonNode weightsNode = cbrNode.get("weights");
        weightsNode
            .fields()
            .forEachRemaining(e -> cbrBuilder.weight(e.getKey(), e.getValue().asDouble()));
      }

      if (cbr.getTopK() != null) {
        cbrBuilder.topK(cbr.getTopK());
      }
      if (cbr.getMinSimilarity() != null) {
        cbrBuilder.minSimilarity(cbr.getMinSimilarity());
      }
      if (cbr.getVectorWeight() != null) {
        cbrBuilder.vectorWeight(cbr.getVectorWeight());
      }
      if (cbr.getDomain() != null) {
        cbrBuilder.domain(cbr.getDomain());
      }
      if (cbr.getCaseType() != null) {
        cbrBuilder.caseType(cbr.getCaseType());
      }
      if (cbr.getTiming() != null) {
        switch (cbr.getTiming()) {
          case CASE_LIFETIME ->
              cbrBuilder.timing(
                  io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming.CASE_LIFETIME);
          case PER_EVALUATION ->
              cbrBuilder.timing(
                  io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming.PER_EVALUATION);
        }
      }
      if (cbrNode != null && cbrNode.has("cbrType")) {
        cbrBuilder.cbrType(cbrNode.get("cbrType").asText());
      }
      if (cbr.getTemporalDecayHalfLifeDays() != null) {
        cbrBuilder.temporalDecayHalfLifeDays(cbr.getTemporalDecayHalfLifeDays());
      }
      if (cbr.getMinCostSamples() != null) {
        cbrBuilder.minCostSamples(cbr.getMinCostSamples());
      }
      def.setCbrConfig(cbrBuilder.build());
    }

    if (schema.getSignals() != null && !schema.getSignals().isEmpty()) {
      var signalTypes = new java.util.ArrayList<SignalType<?>>();
      for (var sig : schema.getSignals()) {
        try {
          Class<?> payloadClass = Class.forName(sig.getContextType());
          signalTypes.add(SignalType.of(sig.getName(), payloadClass));
        } catch (ClassNotFoundException e) {
          throw new IllegalArgumentException(
              "Signal '" + sig.getName() + "' contextType class not found: " + sig.getContextType(),
              e);
        }
      }
      def.setSignals(signalTypes);
    }

    if (rawNode.has("labelRules")) {
      List<LabelRule> labelRules = new ArrayList<>();
      for (JsonNode ruleNode : rawNode.get("labelRules")) {
        String ruleName = ruleNode.get("name").asText();
        JsonNode whenNode = ruleNode.get("when");
        CompiledExpression<Map<String, Object>, Boolean> condition;
        if (whenNode.isTextual()
            && (expressionLang == null || JQExpressionEvaluator.TYPE.equals(expressionLang))
            && !whenNode.asText().isEmpty()) {
          validateJqSyntax(whenNode.asText(), "labelRules[" + ruleName + "].when");
          condition = compileJqBoolean(whenNode.asText());
        } else {
          ExpressionEvaluator evaluator =
              resolveExpression(whenNode, effectiveRegistry, expressionLang);
          condition = toCompiledBooleanExpression(evaluator, effectiveRegistry);
        }
        List<LabelAction> actions = new ArrayList<>();
        for (JsonNode actionNode : ruleNode.get("actions")) {
          if (actionNode.has("add")) {
            actions.add(new LabelAction.Add(actionNode.get("add").asText()));
          } else if (actionNode.has("remove")) {
            actions.add(new LabelAction.Remove(actionNode.get("remove").asText()));
          }
        }
        labelRules.add(new LabelRule(ruleName, condition, actions));
      }
      def.setLabelRules(labelRules);
    }

    if (rawNode.has("inboundMappings")) {
      List<InboundSignalMapping> mappings = new ArrayList<>();
      for (JsonNode entry : rawNode.get("inboundMappings")) {
        var mb =
            InboundSignalMapping.builder()
                .signalName(entry.get("signal").asText())
                .connectorType(entry.get("connectorType").asText())
                .correlation(entry.get("correlation").asText())
                .payload(entry.get("payload").asText());
        if (entry.has("correlationResolver")) {
          mb.correlationResolver(entry.get("correlationResolver").asText());
        }
        mappings.add(mb.build());
      }
      def.setInboundMappings(List.copyOf(mappings));
    }

    if (!cognitiveDemands.isEmpty()) {
      def.setCognitiveDemands(cognitiveDemands);
    }

    final JsonNode routingWeightsNode =
        rawNode.has("spec") && rawNode.get("spec").has("routingSignalWeights")
            ? rawNode.get("spec").get("routingSignalWeights")
            : rawNode.has("routingSignalWeights") ? rawNode.get("routingSignalWeights") : null;
    if (routingWeightsNode != null && routingWeightsNode.isObject()) {
      final Map<String, Double> weights = new LinkedHashMap<>();
      routingWeightsNode
          .fields()
          .forEachRemaining(e -> weights.put(e.getKey(), e.getValue().asDouble()));
      def.setRoutingSignalWeights(weights);
    }

    // authorization — spec-level action-to-groups map for ACL grants at case start
    final JsonNode authNode = specNode != null ? specNode.get("authorization") : null;
    if (authNode != null && authNode.isObject()) {
      var authMap = new java.util.EnumMap<AclAction, List<String>>(AclAction.class);
      authNode
          .fields()
          .forEachRemaining(
              e -> {
                AclAction action;
                try {
                  action = AclAction.valueOf(e.getKey().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                  throw new IllegalArgumentException(
                      "Unknown authorization action: '"
                          + e.getKey()
                          + "'. Valid values: "
                          + java.util.Arrays.toString(AclAction.values()));
                }
                if (e.getValue().isArray()) {
                  var groups = new java.util.ArrayList<String>();
                  e.getValue().forEach(g -> groups.add(g.asText()));
                  authMap.put(action, List.copyOf(groups));
                }
              });
      if (!authMap.isEmpty()) {
        def.setAuthorization(Map.copyOf(authMap));
      }
    }

    // defaultQuorum — spec-level default quorum configuration for action gates
    final JsonNode quorumNode = specNode != null ? specNode.get("quorum") : null;
    if (quorumNode != null && quorumNode.isObject()) {
      final int instances = quorumNode.has("instances") ? quorumNode.get("instances").asInt() : 0;
      final int required = quorumNode.has("required") ? quorumNode.get("required").asInt() : 0;
      final io.casehub.api.model.OnThresholdReached onThresholdReached;
      if (quorumNode.has("onThresholdReached")) {
        try {
          onThresholdReached =
              io.casehub.api.model.OnThresholdReached.valueOf(
                  quorumNode.get("onThresholdReached").asText().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException(
              "Invalid onThresholdReached value: '"
                  + quorumNode.get("onThresholdReached").asText()
                  + "'. Valid values: "
                  + java.util.Arrays.toString(io.casehub.api.model.OnThresholdReached.values()));
        }
      } else {
        onThresholdReached = null;
      }
      final boolean allowSameAssignee =
          quorumNode.has("allowSameAssignee") && quorumNode.get("allowSameAssignee").asBoolean();

      def.setDefaultQuorum(
          new io.casehub.api.spi.QuorumConfig(
              instances, required, onThresholdReached, allowSameAssignee));
    }

    // reflection — per-case reflection trigger configuration
    final JsonNode reflectionNode = specNode != null ? specNode.get("reflection") : null;
    if (reflectionNode != null && reflectionNode.isObject()) {
      Map<String, Double> impWeights = ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS;
      JsonNode weightsNode = reflectionNode.get("importanceWeights");
      if (weightsNode != null && weightsNode.isObject()) {
        var parsed = new LinkedHashMap<String, Double>();
        weightsNode.fields().forEachRemaining(e -> parsed.put(e.getKey(), e.getValue().asDouble()));
        impWeights = Map.copyOf(parsed);
      }
      def.setReflectionTrigger(
          new ReflectionTriggerConfig(
              reflectionNode.has("enabled") && reflectionNode.get("enabled").asBoolean(),
              reflectionNode.has("importanceThreshold")
                  ? reflectionNode.get("importanceThreshold").asDouble()
                  : 3.0,
              reflectionNode.has("maxUnreflectedOutcomes")
                  ? reflectionNode.get("maxUnreflectedOutcomes").asInt()
                  : 10,
              reflectionNode.has("maxSourceMemories")
                  ? reflectionNode.get("maxSourceMemories").asInt()
                  : 50,
              impWeights));
    }

    // memoryRetrieval — per-case memory retrieval configuration
    final JsonNode memRetrievalNode = specNode != null ? specNode.get("memoryRetrieval") : null;
    if (memRetrievalNode != null && memRetrievalNode.isObject()) {
      Set<String> domains = Set.of();
      JsonNode domainsNode = memRetrievalNode.get("domains");
      if (domainsNode != null && domainsNode.isArray()) {
        var domainSet = new java.util.LinkedHashSet<String>();
        domainsNode.forEach(n -> domainSet.add(n.asText()));
        domains = Set.copyOf(domainSet);
      }
      def.setMemoryRetrieval(
          new MemoryRetrievalConfig(
              memRetrievalNode.has("enabled") && memRetrievalNode.get("enabled").asBoolean(),
              memRetrievalNode.has("maxMemories")
                  ? memRetrievalNode.get("maxMemories").asInt()
                  : 10,
              domains));
    }

    // maxAdaptations — per-case adaptation ceiling
    if (specNode != null && specNode.has("maxAdaptations")) {
      def.setMaxAdaptations(specNode.get("maxAdaptations").asInt());
    }

    // adaptation — per-case plan adaptation configuration
    final JsonNode adaptationNode = specNode != null ? specNode.get("adaptation") : null;
    if (adaptationNode != null) {
      if (adaptationNode.isTextual()) {
        String preset = adaptationNode.asText();
        switch (preset) {
          case "adaptive" ->
              def.setAdaptationConfig(
                  io.casehub.api.model.AdaptationConfig.of("every-step", "forward-replan"));
          case "conservative" ->
              def.setAdaptationConfig(
                  io.casehub.api.model.AdaptationConfig.of("on-failure", "forward-replan"));
          case "progress" ->
              def.setAdaptationConfig(
                  new io.casehub.api.model.AdaptationConfig(
                      "progress",
                      "forward-replan",
                      io.casehub.api.model.AdaptationConfig.DEFAULT_PROGRESS_THRESHOLD,
                      null,
                      null));
          case "off" -> {} // null = disabled
          default -> throw new IllegalArgumentException("Unknown adaptation preset: " + preset);
        }
      } else if (adaptationNode.isObject()) {
        String trigger =
            adaptationNode.has("trigger") ? adaptationNode.get("trigger").asText() : "every-step";
        String optimization;
        if (adaptationNode.has("optimization")) {
          optimization = adaptationNode.get("optimization").asText();
        } else if (adaptationNode.has("revision")) {
          optimization = adaptationNode.get("revision").asText();
        } else {
          optimization = "forward-replan";
        }
        Double threshold =
            adaptationNode.has("threshold") ? adaptationNode.get("threshold").asDouble() : null;
        String metaReasoner =
            adaptationNode.has("metaReasoner") ? adaptationNode.get("metaReasoner").asText() : null;
        String repair = adaptationNode.has("repair") ? adaptationNode.get("repair").asText() : null;
        Double contingencyThreshold =
            adaptationNode.has("contingencyThreshold")
                ? adaptationNode.get("contingencyThreshold").asDouble()
                : null;
        def.setAdaptationConfig(
            new io.casehub.api.model.AdaptationConfig(
                trigger, optimization, threshold, metaReasoner, repair, contingencyThreshold));
      }
      if (def.getAdaptationConfig() != null && def.getDecompositionStrategy() == null) {
        LOG.warnf(
            "adaptation configured without decompositionStrategy — "
                + "adaptation requires initial decomposition");
      }
    }

    // recoveryPolicy — per-case recovery configuration
    if (specNode != null && specNode.has("recoveryPolicy")) {
      JsonNode rp = specNode.get("recoveryPolicy");
      def.setRecoveryPolicy(
          new RecoveryPolicy(
              rp.has("maxRetries") ? rp.get("maxRetries").asInt() : 3,
              rp.has("maxRerouteAttempts") ? rp.get("maxRerouteAttempts").asInt() : 3,
              rp.has("classifierId") ? rp.get("classifierId").asText() : "heuristic",
              rp.has("revisionStrategyId")
                  ? rp.get("revisionStrategyId").asText()
                  : "forward-replan",
              rp.has("replanStrategyId") ? rp.get("replanStrategyId").asText() : "llm",
              !rp.has("enabled") || rp.get("enabled").asBoolean()));
    }

    // monitoring — per-case expectation tracking configuration
    final JsonNode monitoringNode = specNode != null ? specNode.get("monitoring") : null;
    if (monitoringNode != null && monitoringNode.isObject()) {
      boolean enabled =
          monitoringNode.has("enabled") ? monitoringNode.get("enabled").asBoolean() : true;
      double threshold =
          monitoringNode.has("perCompletionThreshold")
              ? monitoringNode.get("perCompletionThreshold").asDouble()
              : io.casehub.engine.plan.monitoring.MonitoringConfig.DEFAULT_THRESHOLD;
      int windowSize =
          monitoringNode.has("windowSize")
              ? monitoringNode.get("windowSize").asInt()
              : io.casehub.engine.plan.monitoring.MonitoringConfig.DEFAULT_WINDOW_SIZE;
      def.setMonitoringConfig(
          new io.casehub.engine.plan.monitoring.MonitoringConfig(enabled, threshold, windowSize));
    }

    // portfolioConfig — per-case portfolio decomposition configuration
    final JsonNode portfolioNode = specNode != null ? specNode.get("portfolioConfig") : null;
    if (portfolioNode != null && portfolioNode.isObject()) {
      java.util.List<String> delegates = new java.util.ArrayList<>();
      if (portfolioNode.has("delegates") && portfolioNode.get("delegates").isArray()) {
        portfolioNode.get("delegates").forEach(n -> delegates.add(n.asText()));
      }
      java.util.Map<String, Long> timeouts = new java.util.HashMap<>();
      if (portfolioNode.has("timeouts") && portfolioNode.get("timeouts").isObject()) {
        portfolioNode
            .get("timeouts")
            .fields()
            .forEachRemaining(e -> timeouts.put(e.getKey(), e.getValue().asLong()));
      }
      def.setPortfolioConfig(
          new io.casehub.engine.plan.PortfolioConfig(
              delegates.isEmpty() ? null : delegates, timeouts.isEmpty() ? null : timeouts));
    }

    // goapActions — spec-level GOAP action declarations
    final JsonNode goapNode = specNode != null ? specNode.get("goapActions") : null;
    if (goapNode != null && goapNode.isArray()) {
      var actions = new java.util.ArrayList<io.casehub.engine.plan.goap.GoapAction>();
      for (JsonNode actionNode : goapNode) {
        String actionName = actionNode.has("name") ? actionNode.get("name").asText() : null;
        Map<String, Boolean> preconditions = parseBooleanMap(actionNode.get("preconditions"));
        Map<String, Boolean> effects = parseBooleanMap(actionNode.get("effects"));
        double cost = actionNode.has("cost") ? actionNode.get("cost").asDouble() : 1.0;
        double benefit = actionNode.has("benefit") ? actionNode.get("benefit").asDouble() : 0;
        Map<String, Boolean> softPreconditions =
            parseBooleanMap(actionNode.get("softPreconditions"));
        actions.add(
            new io.casehub.engine.plan.goap.GoapAction(
                actionName, preconditions, effects, cost, benefit, softPreconditions));
      }
      var merged =
          def.getGoapActions() != null
              ? new java.util.ArrayList<>(def.getGoapActions())
              : new java.util.ArrayList<io.casehub.engine.plan.goap.GoapAction>();
      merged.addAll(actions);
      def.setGoapActions(merged);
    }

    // goalToEffectKeys — maps goal names to sets of GOAP effect keys
    final JsonNode gtekNode = specNode != null ? specNode.get("goalToEffectKeys") : null;
    if (gtekNode != null && gtekNode.isObject()) {
      var goalToEffects = new java.util.LinkedHashMap<String, Set<String>>();
      gtekNode
          .fields()
          .forEachRemaining(
              e -> {
                var keys = new java.util.LinkedHashSet<String>();
                if (e.getValue().isArray()) {
                  e.getValue().forEach(v -> keys.add(v.asText()));
                }
                goalToEffects.put(e.getKey(), Set.copyOf(keys));
              });
      def.setGoalToEffectKeys(Map.copyOf(goalToEffects));
    }

    // workerServiceAccountIds — map of worker name to service account ID
    final JsonNode wsaiNode = specNode != null ? specNode.get("workerServiceAccountIds") : null;
    if (wsaiNode != null && wsaiNode.isObject()) {
      var ids = new LinkedHashMap<String, String>();
      wsaiNode.fields().forEachRemaining(e -> ids.put(e.getKey(), e.getValue().asText()));
      def.setWorkerServiceAccountIds(Map.copyOf(ids));
    }

    // humanTaskWorkloadConstraint — workload-based candidate filtering
    final JsonNode wlcNode = specNode != null ? specNode.get("humanTaskWorkloadConstraint") : null;
    if (wlcNode != null && wlcNode.isObject()) {
      var builder = io.casehub.api.model.routing.WorkloadConstraint.builder();
      if (wlcNode.has("maxActiveTaskCount")) {
        builder.maxActiveTaskCount(wlcNode.get("maxActiveTaskCount").asInt());
      }
      if (wlcNode.has("loadBalanceWeight")) {
        builder.loadBalanceWeight(wlcNode.get("loadBalanceWeight").asDouble());
      }
      def.setHumanTaskWorkloadConstraint(builder.build());
    }

    // humanTaskContextConstraints — declarative candidate filtering and scoring
    final JsonNode htccNode = specNode != null ? specNode.get("humanTaskContextConstraints") : null;
    if (htccNode != null && htccNode.isArray()) {
      var constraints = new java.util.ArrayList<io.casehub.api.model.routing.ContextConstraint>();
      for (JsonNode constraintNode : htccNode) {
        var ccBuilder = io.casehub.api.model.routing.ContextConstraint.builder();
        if (constraintNode.has("when")) {
          ccBuilder.when(resolveExpression(constraintNode.get("when"), registry, expressionLang));
        }
        if (constraintNode.has("weight")) {
          ccBuilder.weight(constraintNode.get("weight").asDouble());
        }
        JsonNode effectNode = constraintNode.get("effect");
        if (effectNode != null && effectNode.isObject()) {
          if (effectNode.has("preferGroups") || effectNode.has("preferUsers")) {
            Set<String> groups = parseStringSet(effectNode.get("preferGroups"));
            Set<String> users = parseStringSet(effectNode.get("preferUsers"));
            ccBuilder.prefer(groups, users);
          } else if (effectNode.has("excludeGroups") || effectNode.has("excludeUsers")) {
            Set<String> groups = parseStringSet(effectNode.get("excludeGroups"));
            Set<String> users = parseStringSet(effectNode.get("excludeUsers"));
            ccBuilder.exclude(groups, users);
          }
        }
        constraints.add(ccBuilder.build());
      }
      def.setHumanTaskContextConstraints(constraints);
    }

    return def;
  }

  static ExpressionEvaluator resolveExpression(
      final JsonNode rawValue, final ExpressionEngineRegistry registry, final String defaultLang) {
    if (rawValue == null || rawValue.isNull()) {
      return null;
    }
    if (rawValue.isTextual()) {
      return registry.create(rawValue.asText(), defaultLang);
    }
    if (rawValue.isObject()) {
      if (rawValue.size() != 1) {
        throw new IllegalArgumentException(
            "Expression override must be a single-key map {lang: expr}, got "
                + rawValue.size()
                + " keys");
      }
      var entry = rawValue.fields().next();
      String lang = entry.getKey();
      String expr = entry.getValue().asText();
      registry.assertLanguageSupported(lang);
      return registry.create(expr, lang);
    }
    throw new IllegalArgumentException(
        "Expression must be a string or single-key map {lang: expr}, got: "
            + rawValue.getNodeType());
  }

  private static Map<String, Boolean> parseBooleanMap(JsonNode node) {
    if (node == null || !node.isObject()) {
      return Map.of();
    }
    var map = new java.util.LinkedHashMap<String, Boolean>();
    node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asBoolean()));
    return Map.copyOf(map);
  }

  private static Set<String> parseStringSet(JsonNode node) {
    if (node == null || !node.isArray()) {
      return Set.of();
    }
    var set = new java.util.LinkedHashSet<String>();
    node.forEach(e -> set.add(e.asText()));
    return Set.copyOf(set);
  }

  private static CompiledExpression<Map<String, Object>, Boolean> toCompiledBooleanExpression(
      ExpressionEvaluator evaluator, ExpressionEngineRegistry registry) {
    return new CompiledExpression<>() {
      @Override
      public String type() {
        return evaluator.type();
      }

      @Override
      public Boolean eval(Map<String, Object> context) {
        JsonNode node = MAPPER.valueToTree(context);
        return registry.evaluate(evaluator, node);
      }
    };
  }

  private static Binding convertBinding(
      final io.casehub.model.Binding schemaBinding,
      final JsonNode rawBindingNode,
      final Map<String, Capability> capabilityMap,
      final Map<String, CapabilityTarget> capTargetMap,
      final ExpressionEngineRegistry registry,
      final String expressionLang) {
    if (schemaBinding == null) {
      return null;
    }

    final io.casehub.api.model.Trigger trigger =
        convertTrigger(
            schemaBinding.getOn(),
            rawBindingNode != null ? rawBindingNode.get("on") : null,
            registry,
            expressionLang);

    final Binding.Builder builder = Binding.builder().name(schemaBinding.getName()).on(trigger);

    if (schemaBinding.getCapability() != null) {
      final CapabilityTarget capTarget = capTargetMap.get(schemaBinding.getCapability());
      if (capTarget == null) {
        throw new IllegalArgumentException(
            "Capability '"
                + schemaBinding.getCapability()
                + "' not found in capability map for binding '"
                + schemaBinding.getName()
                + "'");
      }
      builder.target(capTarget);
    } else if (schemaBinding.getSubCase() != null) {
      final JsonNode rawSubCaseNode = rawBindingNode != null ? rawBindingNode.get("subCase") : null;
      final io.casehub.api.model.SubCase subCase =
          convertSubCase(schemaBinding.getSubCase(), rawSubCaseNode, registry, expressionLang);
      builder.subCase(subCase);
    } else if (schemaBinding.getHumanTask() != null) {
      try {
        builder.humanTask(convertHumanTask(schemaBinding.getHumanTask()));
      } catch (IllegalStateException | IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Binding '" + schemaBinding.getName() + "' has invalid humanTask: " + e.getMessage(),
            e);
      }
    } else if (schemaBinding.getSignal() != null) {
      JsonNode signalNode = rawBindingNode != null ? rawBindingNode.get("signal") : null;
      if (signalNode == null || signalNode.isEmpty()) {
        throw new IllegalArgumentException(
            "Binding '" + schemaBinding.getName() + "' signal payload must not be empty");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> signalPayload = MAPPER.convertValue(signalNode, Map.class);
      builder.signal(signalPayload);
    } else {
      throw new IllegalArgumentException(
          "Binding '"
              + schemaBinding.getName()
              + "' must have capability, subCase, humanTask, or signal");
    }

    if (rawBindingNode != null && rawBindingNode.has("when")) {
      builder.when(resolveExpression(rawBindingNode.get("when"), registry, expressionLang));
    }

    if (schemaBinding.getConflictResolverStrategy() != null) {
      builder.conflictResolverStrategy(schemaBinding.getConflictResolverStrategy().value());
    }

    if (rawBindingNode != null && rawBindingNode.has("inputProjectionOverride")) {
      builder.inputProjectionOverride(
          resolveExpression(
              rawBindingNode.get("inputProjectionOverride"), registry, expressionLang));
    } else if (schemaBinding.getInputProjectionOverride() != null) {
      builder.inputProjectionOverride(schemaBinding.getInputProjectionOverride());
    }

    if (schemaBinding.getContextWrite() != null) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> writeMap =
          MAPPER.convertValue(schemaBinding.getContextWrite(), Map.class);
      if (!writeMap.isEmpty()) {
        builder.contextWrite(writeMap);
      }
    }

    if (schemaBinding.getProducedKeys() != null && !schemaBinding.getProducedKeys().isEmpty()) {
      builder.producedKeys(new java.util.LinkedHashSet<>(schemaBinding.getProducedKeys()));
    }

    if (schemaBinding.getReplanAfter() != null) {
      builder.replanHint(
          io.casehub.api.model.ReplanHint.valueOf(
              schemaBinding.getReplanAfter().value().toUpperCase()));
    }

    if (schemaBinding.getContingency() != null && !schemaBinding.getContingency().isEmpty()) {
      builder.contingency(schemaBinding.getContingency());
    }

    if (schemaBinding.getOutcomePolicy() != null) {
      final io.casehub.model.OutcomePolicy sp = schemaBinding.getOutcomePolicy();
      final OutcomeAction onDecline =
          sp.getOnDecline() != null
              ? OutcomeAction.valueOf(sp.getOnDecline().value())
              : OutcomeAction.REROUTE;
      final OutcomeAction onFailure =
          sp.getOnFailure() != null
              ? OutcomeAction.valueOf(sp.getOnFailure().value())
              : OutcomeAction.REROUTE;
      final OutcomeAction onExpired =
          sp.getOnExpired() != null
              ? OutcomeAction.valueOf(sp.getOnExpired().value())
              : OutcomeAction.REROUTE;
      final int maxAttempts = sp.getMaxRerouteAttempts() != null ? sp.getMaxRerouteAttempts() : 3;
      builder.outcomePolicy(new OutcomePolicy(onDecline, onFailure, onExpired, maxAttempts));
    }

    if (schemaBinding.getLifecycleScope() != null) {
      builder.lifecycleScope(LifecycleScope.valueOf(schemaBinding.getLifecycleScope().value()));
    }

    if (schemaBinding.getParticipation() != null) {
      builder.participation(Participation.valueOf(schemaBinding.getParticipation().value()));
    }

    if (schemaBinding.getExecutionMode() != null) {
      builder.executionMode(ExecutionMode.valueOf(schemaBinding.getExecutionMode().value()));
    }

    if (schemaBinding.getPermissionIntent() != null
        && !schemaBinding.getPermissionIntent().isEmpty()) {
      builder.permissionIntent(
          schemaBinding.getPermissionIntent().stream()
              .map(io.casehub.api.acl.EngineWorkerActions::fromKebabCase)
              .toList());
    }

    applyExchangeFields(schemaBinding, builder);

    if (rawBindingNode != null && rawBindingNode.has("sideEffectClassification")) {
      builder.sideEffectClassification(
          SideEffectClassification.valueOf(
              rawBindingNode.get("sideEffectClassification").asText()));
    }

    if (rawBindingNode != null && rawBindingNode.has("recoveryOverride")) {
      JsonNode ro = rawBindingNode.get("recoveryOverride");
      java.util.Set<OutcomeType> skipFor = new java.util.HashSet<>();
      if (ro.has("skipRecoveryFor")) {
        ro.get("skipRecoveryFor").forEach(n -> skipFor.add(OutcomeType.valueOf(n.asText())));
      }
      builder.recoveryOverride(
          new RecoveryOverride(
              ro.has("maxRetries") ? ro.get("maxRetries").asInt() : null,
              ro.has("maxRerouteAttempts") ? ro.get("maxRerouteAttempts").asInt() : null,
              ro.has("maxLevel") ? RecoveryLevel.valueOf(ro.get("maxLevel").asText()) : null,
              ro.has("skipRecovery") && ro.get("skipRecovery").asBoolean(),
              skipFor));
    }

    return builder.build();
  }

  private static void applyExchangeFields(
      final io.casehub.model.Binding schemaBinding, final Binding.Builder builder) {
    if (schemaBinding.getProduces() != null) {
      builder.produces(schemaBinding.getProduces());
    }
    if (schemaBinding.getConsumes() != null) {
      builder.consumes(schemaBinding.getConsumes());
    }
    final Object ep = schemaBinding.getExchangeProjection();
    if (ep instanceof String strategyId) {
      builder.exchangeProjectionStrategy(strategyId);
    } else if (ep instanceof java.util.Map<?, ?> epMap) {
      final Object strategy = epMap.get("strategy");
      if (strategy instanceof String strategyId) {
        final Object expression = epMap.get("expression");
        builder.projectWith(strategyId, expression instanceof String expr ? expr : null);
      }
    }
  }

  private static io.casehub.api.model.SubCase convertSubCase(
      final io.casehub.model.SubCase schemaModel,
      final JsonNode rawSubCaseNode,
      final ExpressionEngineRegistry registry,
      final String expressionLang) {
    if (schemaModel == null) {
      return null;
    }

    final io.casehub.api.model.SubCaseCompletionStrategy strategy =
        convertCompletionStrategy(schemaModel.getCompletionStrategy());

    ExpressionEvaluator inputEval =
        rawSubCaseNode != null && rawSubCaseNode.has("inputMapping")
            ? resolveExpression(rawSubCaseNode.get("inputMapping"), registry, expressionLang)
            : null;
    ExpressionEvaluator outputEval =
        rawSubCaseNode != null && rawSubCaseNode.has("outputMapping")
            ? resolveExpression(rawSubCaseNode.get("outputMapping"), registry, expressionLang)
            : null;

    var builder =
        io.casehub.api.model.SubCase.builder()
            .namespace(schemaModel.getNamespace())
            .name(schemaModel.getName())
            .version(schemaModel.getVersion())
            .completionStrategy(strategy)
            .waitForCompletion(
                schemaModel.getWaitForCompletion() != null
                    ? schemaModel.getWaitForCompletion()
                    : true)
            .maxRecursionDepth(
                schemaModel.getMaxRecursionDepth() != null ? schemaModel.getMaxRecursionDepth() : 0)
            .groupId(schemaModel.getGroupId())
            .totalInGroup(schemaModel.getTotalInGroup() != null ? schemaModel.getTotalInGroup() : 0)
            .requiredCount(
                schemaModel.getRequiredCount() != null ? schemaModel.getRequiredCount() : 0)
            .onThresholdReached(
                schemaModel.getOnThresholdReached() != null
                    ? io.casehub.api.model.OnThresholdReached.valueOf(
                        schemaModel.getOnThresholdReached().value())
                    : io.casehub.api.model.OnThresholdReached.KEEP);

    if (inputEval != null) {
      builder.inputMapping(new io.casehub.api.model.SubCaseMapping.Expression(inputEval));
    } else {
      builder.inputMapping(
          schemaModel.getInputMapping() != null ? schemaModel.getInputMapping() : ".");
    }
    if (outputEval != null) {
      builder.outputMapping(new io.casehub.api.model.SubCaseMapping.Expression(outputEval));
    } else {
      builder.outputMapping(
          schemaModel.getOutputMapping() != null ? schemaModel.getOutputMapping() : ".");
    }

    return builder.build();
  }

  private static io.casehub.api.model.SubCaseCompletionStrategy convertCompletionStrategy(
      final io.casehub.model.SubCase.CompletionStrategy schemaStrategy) {
    if (schemaStrategy == null
        || schemaStrategy == io.casehub.model.SubCase.CompletionStrategy.DEFAULT) {
      return new io.casehub.api.model.DefaultSubCaseCompletionStrategy();
    }
    return new io.casehub.api.model.DefaultSubCaseCompletionStrategy();
  }

  private static io.casehub.api.model.Trigger convertTrigger(
      final io.casehub.model.Trigger schemaTrigger,
      final JsonNode rawTriggerNode,
      final ExpressionEngineRegistry registry,
      final String expressionLang) {
    if (schemaTrigger == null) {
      return null;
    }

    if (schemaTrigger.getContextChange() != null) {
      final JsonNode ctxNode = rawTriggerNode != null ? rawTriggerNode.get("contextChange") : null;
      final JsonNode filterNode = ctxNode != null ? ctxNode.get("filter") : null;
      final String listenLayer = schemaTrigger.getContextChange().getListenLayer();
      return new io.casehub.api.model.ContextChangeTrigger(
          resolveExpression(filterNode, registry, expressionLang), listenLayer);
    }

    if (schemaTrigger.getScopeActivated() != null) {
      return new io.casehub.api.model.ScopeActivatedTrigger();
    }

    if (schemaTrigger.getSchedule() != null) {
      io.casehub.model.ScheduleTrigger st = schemaTrigger.getSchedule();
      if (st.getCron() != null) {
        return io.casehub.api.model.ScheduleTrigger.cron(st.getCron());
      } else if (st.getEvery() != null) {
        return io.casehub.api.model.ScheduleTrigger.delay(java.time.Duration.parse(st.getEvery()));
      } else {
        throw new IllegalArgumentException(
            "ScheduleTrigger must have either 'cron' or 'every' set");
      }
    }

    if (schemaTrigger.getCloudEvent() != null) {
      final JsonNode ceNode = rawTriggerNode != null ? rawTriggerNode.get("cloudEvent") : null;
      if (ceNode == null) {
        throw new IllegalArgumentException("CloudEvent trigger present but raw node is missing");
      }
      if (ceNode.isTextual()) {
        return new io.casehub.api.model.CloudEventTrigger(ceNode.asText());
      }
      if (ceNode.isObject()) {
        if (!ceNode.has("type")) {
          throw new IllegalArgumentException("CloudEvent trigger object must have a 'type' field");
        }
        final String type = ceNode.get("type").asText();
        final String source = ceNode.has("source") ? ceNode.get("source").asText() : null;
        final String subject = ceNode.has("subject") ? ceNode.get("subject").asText() : null;
        final ExpressionEvaluator filter =
            ceNode.has("filter")
                ? resolveExpression(ceNode.get("filter"), registry, expressionLang)
                : null;
        return new io.casehub.api.model.CloudEventTrigger(type, source, subject, filter);
      }
      throw new IllegalArgumentException(
          "CloudEvent trigger must be a string or object, got: " + ceNode.getNodeType());
    }

    throw new IllegalArgumentException(
        "Trigger must have one of: contextChange, cloudEvent, schedule, scopeActivated");
  }

  private static GoalExpression convertGoalExpression(
      final io.casehub.model.GoalExpression expr, final Map<String, Goal> goalMap) {
    if (expr == null) {
      return null;
    }

    if (expr.getAllOf() != null && !expr.getAllOf().isEmpty()) {
      return new AllOfGoalExpression(
          expr.getAllOf().stream()
              .map(
                  name -> {
                    Goal goal = goalMap.get(name);
                    if (goal == null) {
                      throw new IllegalArgumentException(
                          "Goal '" + name + "' referenced in completion expression is not defined");
                    }
                    return (GoalExpression) new SingleGoalExpression(goal.getName());
                  })
              .collect(Collectors.toList()));
    }

    if (expr.getAnyOf() != null && !expr.getAnyOf().isEmpty()) {
      return new AnyOfGoalExpression(
          expr.getAnyOf().stream()
              .map(
                  name -> {
                    Goal goal = goalMap.get(name);
                    if (goal == null) {
                      throw new IllegalArgumentException(
                          "Goal '" + name + "' referenced in completion expression is not defined");
                    }
                    return (GoalExpression) new SingleGoalExpression(goal.getName());
                  })
              .collect(Collectors.toList()));
    }

    return null;
  }

  private static GoalKind resolveGoalKind(String kindValue, JsonNode exprNode) {
    if ("doneWhen".equals(kindValue)) {
      throw new IllegalArgumentException(
          "'doneWhen' is a reserved name and cannot be used as a goal kind");
    }
    if ("success".equals(kindValue) || "failure".equals(kindValue)) {
      if (exprNode.has("status")) {
        throw new IllegalArgumentException(
            "Standard goal kind '"
                + kindValue
                + "' has an implicit terminal status"
                + " — explicit 'status' field is not allowed");
      }
      return StandardGoalKind.fromValue(kindValue);
    }
    if (!exprNode.has("status")) {
      throw new IllegalArgumentException(
          "Custom goal kind '"
              + kindValue
              + "' requires an explicit 'status' field"
              + " (COMPLETED or FAULTED)");
    }
    return GoalKind.of(kindValue, CaseStatus.valueOf(exprNode.get("status").asText()));
  }

  private static GoalExpression parseGoalExpressionFromNode(
      JsonNode node, Map<String, Goal> goalMap) {
    JsonNode allOfNode = node.get("allOf");
    if (allOfNode != null && allOfNode.isArray()) {
      if (allOfNode.isEmpty()) {
        throw new IllegalArgumentException("allOf array must not be empty");
      }
      List<GoalExpression> children = new java.util.ArrayList<>();
      for (JsonNode element : allOfNode) {
        children.add(parseGoalElement(element, goalMap));
      }
      return new AllOfGoalExpression(children);
    }
    JsonNode anyOfNode = node.get("anyOf");
    if (anyOfNode != null && anyOfNode.isArray()) {
      if (anyOfNode.isEmpty()) {
        throw new IllegalArgumentException("anyOf array must not be empty");
      }
      List<GoalExpression> children = new java.util.ArrayList<>();
      for (JsonNode element : anyOfNode) {
        children.add(parseGoalElement(element, goalMap));
      }
      return new AnyOfGoalExpression(children);
    }
    return null;
  }

  private static GoalExpression parseGoalElement(JsonNode element, Map<String, Goal> goalMap) {
    if (element.isTextual()) {
      String goalName = element.asText();
      Goal goal = goalMap.get(goalName);
      if (goal == null) {
        throw new IllegalArgumentException(
            "Goal '" + goalName + "' referenced in completion expression is not defined");
      }
      return new SingleGoalExpression(goal.getName());
    }
    if (element.isObject()) {
      GoalExpression nested = parseGoalExpressionFromNode(element, goalMap);
      if (nested == null) {
        throw new IllegalArgumentException(
            "Completion expression object must contain 'allOf' or 'anyOf'");
      }
      return nested;
    }
    throw new IllegalArgumentException(
        "Completion expression element must be a goal name (string) or an object with allOf/anyOf");
  }

  private static HumanTaskTarget convertHumanTask(final io.casehub.model.HumanTask schema) {
    if ((schema.getTitle() != null || schema.getTitleExpression() != null)
        && schema.getTemplateRef() != null) {
      throw new IllegalArgumentException(
          "humanTask cannot specify both title/titleExpression and templateRef"
              + " - use inline mode (title or titleExpression) or template mode (templateRef), not both");
    }
    final HumanTaskTarget.Builder builder;
    if (schema.getTemplateRef() != null) {
      builder = HumanTaskTarget.template(schema.getTemplateRef());
    } else {
      builder = HumanTaskTarget.inline();
      if (schema.getTitle() != null) {
        builder.title(schema.getTitle());
      }
    }

    if (schema.getInputMapping() != null) {
      builder.inputMapping(schema.getInputMapping());
    }
    if (schema.getOutputMapping() != null) {
      builder.outputMapping(schema.getOutputMapping());
    }
    final io.casehub.api.spi.routing.CandidateSetSpec groupsSpec =
        parseCandidateSet(schema.getCandidateGroups(), "candidateGroups");
    if (groupsSpec != null) {
      builder.candidateGroups(groupsSpec);
    }

    final io.casehub.api.spi.routing.CandidateSetSpec usersSpec =
        parseCandidateSet(schema.getCandidateUsers(), "candidateUsers");
    if (usersSpec != null) {
      builder.candidateUsers(usersSpec);
    }
    if (schema.getScope() != null) {
      builder.scope(schema.getScope());
    }
    if (schema.getClaimDeadlineHours() != null) {
      builder.claimDeadlineHours(schema.getClaimDeadlineHours());
    }
    if (schema.getExpiresIn() != null) {
      final Duration duration;
      try {
        duration = Duration.parse(schema.getExpiresIn());
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(
            "invalid expiresIn '"
                + schema.getExpiresIn()
                + "' - must be ISO-8601 duration (e.g. PT24H, PT1H30M)",
            e);
      }
      if (duration.isNegative() || duration.isZero()) {
        throw new IllegalArgumentException(
            "expiresIn must be positive, got '" + schema.getExpiresIn() + "'");
      }
      builder.expiresIn(duration);
    }
    if (schema.getTitleExpression() != null && !schema.getTitleExpression().isBlank()) {
      validateJqSyntax(schema.getTitleExpression(), "titleExpression");
      builder.titleExpression(schema.getTitleExpression());
    }
    if (schema.getScopeExpression() != null && !schema.getScopeExpression().isBlank()) {
      validateJqSyntax(schema.getScopeExpression(), "scopeExpression");
      builder.scopeExpression(schema.getScopeExpression());
    }
    if (schema.getExpiresInExpression() != null && !schema.getExpiresInExpression().isBlank()) {
      if (schema.getExpiresIn() != null) {
        throw new IllegalArgumentException(
            "cannot specify both expiresIn and expiresInExpression"
                + " — use static duration or dynamic expression, not both");
      }
      validateJqSyntax(schema.getExpiresInExpression(), "expiresInExpression");
      builder.expiresInExpression(schema.getExpiresInExpression());
    }
    if (schema.getExpiresAtExpression() != null && !schema.getExpiresAtExpression().isBlank()) {
      validateJqSyntax(schema.getExpiresAtExpression(), "expiresAtExpression");
      builder.expiresAtExpression(schema.getExpiresAtExpression());
    }
    if (schema.getOutcomes() != null && !schema.getOutcomes().isEmpty()) {
      builder.outcomes(new LinkedHashSet<>(schema.getOutcomes()));
    }
    if (schema.getPayloadType() != null) {
      try {
        builder.payloadType(Class.forName(schema.getPayloadType()));
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "humanTask payloadType class not found: " + schema.getPayloadType(), e);
      }
    }
    if (schema.getResolutionType() != null) {
      try {
        builder.resolutionType(Class.forName(schema.getResolutionType()));
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "humanTask resolutionType class not found: " + schema.getResolutionType(), e);
      }
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static io.casehub.api.spi.routing.CandidateSetSpec parseCandidateSet(
      final Object raw, final String fieldName) {
    if (raw instanceof List<?> list && !list.isEmpty()) {
      return new io.casehub.api.spi.routing.CandidateSetSpec.Inline(
          io.casehub.api.spi.routing.StaticSetStrategy.of(
              new LinkedHashSet<>(castStringList(fieldName, list))));
    } else if (raw instanceof String expr && !expr.isBlank()) {
      return new io.casehub.api.spi.routing.CandidateSetSpec.Inline(
          new io.casehub.api.spi.routing.JqCandidateSetStrategy(expr));
    } else if (raw instanceof Map<?, ?> map) {
      if (map.containsKey("strategy")) {
        final String strategyId = (String) map.get("strategy");
        final Map<String, Object> config =
            map.containsKey("config") ? (Map<String, Object>) map.get("config") : Map.of();
        return new io.casehub.api.spi.routing.CandidateSetSpec.Named(strategyId, config);
      } else if (map.containsKey("expression")) {
        final String expression = (String) map.get("expression");
        final String lang = map.containsKey("lang") ? (String) map.get("lang") : "jq";
        if ("jq".equals(lang)) {
          return new io.casehub.api.spi.routing.CandidateSetSpec.Inline(
              new io.casehub.api.spi.routing.JqCandidateSetStrategy(expression));
        }
        return new io.casehub.api.spi.routing.CandidateSetSpec.Named(
            "expression-" + lang, Map.of("expression", expression, "lang", lang));
      }
    }
    return null;
  }

  private static ExecutionPolicy convertExecutionPolicy(
      final io.casehub.model.ExecutionPolicy schema) {
    final io.casehub.model.RetryPolicy sr = schema.getRetries();
    final BackoffStrategy strategy =
        sr.getBackoffStrategy() != null
            ? BackoffStrategy.valueOf(sr.getBackoffStrategy())
            : BackoffStrategy.FIXED;
    final RetryPolicy retries = new RetryPolicy(sr.getMaxAttempts(), sr.getDelayMs(), strategy);
    return new ExecutionPolicy(schema.getTimeoutMs(), retries);
  }

  @SuppressWarnings("unchecked")
  private static List<String> castStringList(final String fieldName, final List<?> raw) {
    for (final Object element : raw) {
      if (!(element instanceof String)) {
        throw new IllegalArgumentException(
            fieldName
                + " list must contain only strings, found: "
                + element.getClass().getSimpleName());
      }
    }
    return (List<String>) raw;
  }

  private static void validateJqSyntax(final String expression, final String fieldName) {
    try {
      net.thisptr.jackson.jq.JsonQuery.compile(expression, net.thisptr.jackson.jq.Versions.JQ_1_6);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "invalid " + fieldName + " '" + expression + "' — " + e.getMessage(), e);
    }
  }

  private static CompiledExpression<Map<String, Object>, Boolean> compileJqBoolean(
      String expression) {
    try {
      JsonQuery query = JsonQuery.compile(expression, Versions.JQ_1_6);
      Scope rootScope = Scope.newEmptyScope();
      BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
      CompiledExpression<JsonNode, Boolean> jsonNodeExpr =
          new CompiledExpression<>() {
            @Override
            public String type() {
              return "jq";
            }

            @Override
            public Boolean eval(JsonNode context) {
              try {
                Scope childScope = Scope.newChildScope(rootScope);
                List<JsonNode> out = new ArrayList<>();
                query.apply(childScope, context, out::add);
                for (JsonNode node : out) {
                  if (node.isBoolean() && node.asBoolean()) {
                    return true;
                  }
                }
                return false;
              } catch (JsonQueryException e) {
                return false;
              }
            }
          };
      return new CompiledExpression<>() {
        @Override
        public String type() {
          return "jq";
        }

        @Override
        public Boolean eval(Map<String, Object> context) {
          return jsonNodeExpr.eval(MAPPER.valueToTree(context));
        }
      };
    } catch (JsonQueryException e) {
      throw new IllegalArgumentException("Invalid JQ expression: " + expression, e);
    }
  }

  /**
   * Wraps a registry to produce {@link TypedMvelExpressionEvaluator} for MVEL expressions when a
   * contextType is declared.
   */
  private static final class TypedMvelRegistry implements ExpressionEngineRegistry {
    private final ExpressionEngineRegistry delegate;
    private final Class<?> contextClass;

    TypedMvelRegistry(ExpressionEngineRegistry delegate, Class<?> contextClass) {
      this.delegate = delegate;
      this.contextClass = contextClass;
    }

    @Override
    public ExpressionEvaluator create(String expression, String expressionLang) {
      final ExpressionEvaluator delegateResult = delegate.create(expression, expressionLang);
      if ("mvel".equals(expressionLang)) {
        return new TypedMvelExpressionEvaluator(expression, contextClass);
      }
      return delegateResult;
    }

    @Override
    public void assertLanguageSupported(String expressionLang) {
      delegate.assertLanguageSupported(expressionLang);
    }

    @Override
    public boolean evaluate(ExpressionEvaluator evaluator, CaseContext context) {
      return delegate.evaluate(evaluator, context);
    }

    @Override
    public boolean evaluate(ExpressionEvaluator evaluator, JsonNode asNode) {
      return delegate.evaluate(evaluator, asNode);
    }

    @Override
    public void validate(ExpressionEvaluator evaluator) {
      delegate.validate(evaluator);
    }

    @Override
    public java.util.List<JsonNode> transform(ExpressionEvaluator evaluator, JsonNode input) {
      return delegate.transform(evaluator, input);
    }

    @Override
    public java.util.Optional<String> extractString(
        ExpressionEvaluator evaluator, CaseContext context) {
      return delegate.extractString(evaluator, context);
    }
  }
}
