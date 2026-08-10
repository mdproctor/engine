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
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.AllOfGoalExpression;
import io.casehub.api.model.AnyOfGoalExpression;
import io.casehub.api.model.Binding;
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
import io.casehub.api.model.Participation;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.WorkerFunctions;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
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
    // Read bytes once so we can parse both as JsonNode (for free-form fields) and typed schema
    // model
    final byte[] bytes = yamlStream.readAllBytes();
    final JsonNode rawNode = objectMapper.readTree(bytes);
    // Disable FAIL_ON_UNKNOWN_PROPERTIES so free-form schema fields (e.g. semanticData with
    // additionalProperties:true) are silently ignored by the generated empty schema class.
    final ObjectMapper lenient =
        objectMapper
            .copy()
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    lenient.addHandler(UnknownPropertyWarningHandler.INSTANCE);
    final io.casehub.model.CaseDefinition schema =
        lenient.readValue(bytes, io.casehub.model.CaseDefinition.class);
    return convertToApiModel(schema, rawNode, objectMapper, registry, providerRegistry);
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
    final String expressionLang =
        schema.getExpressionLang() != null
            ? schema.getExpressionLang()
            : JQExpressionEvaluator.TYPE;
    registry.assertLanguageSupported(expressionLang);

    final CaseDefinition def =
        new CaseDefinition(schema.getNamespace(), schema.getName(), schema.getVersion());
    def.setDsl(schema.getDsl());
    def.setTitle(schema.getTitle());
    if (schema.getSummary() != null) {
      def.setSummary(schema.getSummary());
    }

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
              final io.casehub.api.model.ai.Agent apiAgent =
                  AgentConverter.toApiAgent(sw.getAgent());
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
    }

    // Convert bindings
    if (schema.getSpec() != null && schema.getSpec().getBindings() != null) {
      for (io.casehub.model.Binding sr : schema.getSpec().getBindings()) {
        final Binding binding = convertBinding(sr, capabilityMap, registry, expressionLang);
        def.getBindings().add(binding);
      }
    }

    // Convert milestones
    if (schema.getSpec() != null && schema.getSpec().getMilestones() != null) {
      for (io.casehub.model.Milestone sm : schema.getSpec().getMilestones()) {
        final Milestone.Builder milestoneBuilder =
            Milestone.builder()
                .name(sm.getName())
                .completionCriteria(registry.create(sm.getCondition(), expressionLang));

        if (sm.getEntryCriteria() != null) {
          milestoneBuilder.entryCriteria(registry.create(sm.getEntryCriteria(), expressionLang));
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
    if (schema.getSpec() != null && schema.getSpec().getGoals() != null) {
      for (io.casehub.model.Goal sg : schema.getSpec().getGoals()) {
        final Goal goal =
            new Goal(
                sg.getName(),
                registry.create(sg.getCondition(), expressionLang),
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
      String doneWhen =
          completionNode.has("doneWhen") ? completionNode.get("doneWhen").asText() : null;
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
      if (doneWhen != null && hasGoalEntries) {
        throw new IllegalArgumentException(
            "Completion block cannot mix 'doneWhen' with goal kind entries"
                + " — use one completion mechanism per definition");
      }
      if (doneWhen != null) {
        def.setCompletion(new PredicateBasedCompletion(new JQExpressionEvaluator(doneWhen)));
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
      def.setPlanningConstraints(
          new io.casehub.engine.plan.PlanningConstraints(timeBudget, resourceLimit, weights));
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
        String when = ruleNode.get("when").asText();
        validateJqSyntax(when, "labelRules[" + ruleName + "].when");
        CompiledExpression<Map<String, Object>, Boolean> condition = compileJqBoolean(when);
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

    // adaptation — per-case plan adaptation configuration
    final JsonNode adaptationNode = specNode != null ? specNode.get("adaptation") : null;
    if (adaptationNode != null) {
      if (adaptationNode.isTextual()) {
        String preset = adaptationNode.asText();
        switch (preset) {
          case "adaptive" ->
              def.setAdaptationConfig(
                  new io.casehub.api.model.AdaptationConfig("every-step", "forward-replan"));
          case "conservative" ->
              def.setAdaptationConfig(
                  new io.casehub.api.model.AdaptationConfig("on-failure", "forward-replan"));
          case "off" -> {} // null = disabled
          default -> throw new IllegalArgumentException("Unknown adaptation preset: " + preset);
        }
      } else if (adaptationNode.isObject()) {
        String trigger =
            adaptationNode.has("trigger") ? adaptationNode.get("trigger").asText() : "every-step";
        String revision =
            adaptationNode.has("revision")
                ? adaptationNode.get("revision").asText()
                : "forward-replan";
        def.setAdaptationConfig(new io.casehub.api.model.AdaptationConfig(trigger, revision));
      }
      if (def.getAdaptationConfig() != null && def.getDecompositionStrategy() == null) {
        LOG.warnf(
            "adaptation configured without decompositionStrategy — "
                + "adaptation requires initial decomposition");
      }
    }

    return def;
  }

  private static Binding convertBinding(
      final io.casehub.model.Binding schemaBinding,
      final Map<String, Capability> capabilityMap,
      final ExpressionEngineRegistry registry,
      final String expressionLang) {
    if (schemaBinding == null) {
      return null;
    }

    final io.casehub.api.model.Trigger trigger =
        convertTrigger(schemaBinding.getOn(), registry, expressionLang);

    final Binding.Builder builder = Binding.builder().name(schemaBinding.getName()).on(trigger);

    if (schemaBinding.getCapability() != null) {
      final Capability cap = capabilityMap.get(schemaBinding.getCapability());
      if (cap == null) {
        throw new IllegalArgumentException(
            "Capability '"
                + schemaBinding.getCapability()
                + "' not found in capability map for binding '"
                + schemaBinding.getName()
                + "'");
      }
      builder.capability(cap);
    } else if (schemaBinding.getSubCase() != null) {
      final io.casehub.api.model.SubCase subCase = convertSubCase(schemaBinding.getSubCase());
      builder.subCase(subCase);
    } else if (schemaBinding.getHumanTask() != null) {
      try {
        builder.humanTask(convertHumanTask(schemaBinding.getHumanTask()));
      } catch (IllegalStateException | IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "Binding '" + schemaBinding.getName() + "' has invalid humanTask: " + e.getMessage(),
            e);
      }
    } else {
      throw new IllegalArgumentException(
          "Binding '" + schemaBinding.getName() + "' must have capability, subCase, or humanTask");
    }

    if (schemaBinding.getWhen() != null) {
      builder.when(registry.create(schemaBinding.getWhen(), expressionLang));
    }

    if (schemaBinding.getConflictResolverStrategy() != null) {
      builder.conflictResolverStrategy(schemaBinding.getConflictResolverStrategy().value());
    }

    if (schemaBinding.getInputProjectionOverride() != null) {
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

    return builder.build();
  }

  private static io.casehub.api.model.SubCase convertSubCase(
      final io.casehub.model.SubCase schemaModel) {
    if (schemaModel == null) {
      return null;
    }

    final io.casehub.api.model.SubCaseCompletionStrategy strategy =
        convertCompletionStrategy(schemaModel.getCompletionStrategy());

    return io.casehub.api.model.SubCase.builder()
        .namespace(schemaModel.getNamespace())
        .name(schemaModel.getName())
        .version(schemaModel.getVersion())
        .completionStrategy(strategy)
        .waitForCompletion(
            schemaModel.getWaitForCompletion() != null ? schemaModel.getWaitForCompletion() : true)
        .inputMapping(schemaModel.getInputMapping() != null ? schemaModel.getInputMapping() : ".")
        .outputMapping(
            schemaModel.getOutputMapping() != null ? schemaModel.getOutputMapping() : ".")
        .maxRecursionDepth(
            schemaModel.getMaxRecursionDepth() != null ? schemaModel.getMaxRecursionDepth() : 0)
        .groupId(schemaModel.getGroupId())
        .totalInGroup(schemaModel.getTotalInGroup() != null ? schemaModel.getTotalInGroup() : 0)
        .requiredCount(schemaModel.getRequiredCount() != null ? schemaModel.getRequiredCount() : 0)
        .onThresholdReached(
            schemaModel.getOnThresholdReached() != null
                ? io.casehub.api.model.OnThresholdReached.valueOf(
                    schemaModel.getOnThresholdReached().value())
                : io.casehub.api.model.OnThresholdReached.KEEP)
        .build();
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
      final ExpressionEngineRegistry registry,
      final String expressionLang) {
    if (schemaTrigger == null) {
      return null;
    }

    if (schemaTrigger.getContextChange() != null) {
      final String filter = schemaTrigger.getContextChange().getFilter();
      final String listenLayer = schemaTrigger.getContextChange().getListenLayer();
      return new io.casehub.api.model.ContextChangeTrigger(
          filter != null ? registry.create(filter, expressionLang) : null, listenLayer);
    }

    if (schemaTrigger.getScopeActivated() != null) {
      return new io.casehub.api.model.ScopeActivatedTrigger();
    }

    // TODO: Add support for CloudEventTrigger and ScheduleTrigger
    throw new UnsupportedOperationException(
        "Only ContextChangeTrigger and ScopeActivatedTrigger are currently supported. "
            + "CloudEventTrigger and ScheduleTrigger conversion not yet implemented.");
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
}
