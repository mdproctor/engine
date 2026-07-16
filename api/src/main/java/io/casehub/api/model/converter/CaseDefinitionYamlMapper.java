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
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.StandardGoalKind;
import io.casehub.api.model.WorkerFunctions;
import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.WorkerFunctionProviderRegistry;
import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    // Convert capabilities
    final Map<String, Capability> capabilityMap = new LinkedHashMap<>();
    if (schema.getSpec() != null && schema.getSpec().getCapabilities() != null) {
      for (io.casehub.model.Capability sc : schema.getSpec().getCapabilities()) {
        final Capability cap =
            Capability.builder()
                .name(sc.getName())
                .inputSchema(sc.getInputProjection() != null ? sc.getInputProjection() : ".")
                .outputSchema(sc.getOutputProjection() != null ? sc.getOutputProjection() : ".")
                .description(sc.getDescription())
                .build();
        capabilityMap.put(sc.getName(), cap);
        def.getCapabilities().add(cap);
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
          // Try providers first (for SDK-dependent types like flow)
          final JsonNode rawWorkerNode = rawWorkers.get(workerIndex);
          WorkerFunction function = providerRegistry.createFunction(rawWorkerNode);
          if (function == null) {
            // API-local construction (no external SDK dependency)
            if (sw.getAgent() != null) {
              final io.casehub.api.model.ai.Agent apiAgent =
                  AgentConverter.toApiAgent(sw.getAgent());
              function = new AgentWorkerFunction(apiAgent);
            } else if (sw.getContextType() != null) {
              try {
                Class<?> contextType = Class.forName(sw.getContextType());
                function =
                    new WorkerFunction.Sync<>(
                        contextType,
                        input -> {
                          throw new UnsupportedOperationException(
                              "YAML-declared contextType worker '"
                                  + sw.getName()
                                  + "' has no in-process function — dispatch via external backend");
                        });
              } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException(
                    "Worker '"
                        + sw.getName()
                        + "' contextType class not found: "
                        + sw.getContextType(),
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
          final SlaStartFrom startFrom = SlaStartFrom.valueOf(sm.getSlaStartFrom().value());
          if (startFrom == SlaStartFrom.PREVIOUS_MILESTONE_COMPLETED
              || startFrom == SlaStartFrom.EVENT_OCCURRED) {
            throw new UnsupportedOperationException(
                "Milestone '"
                    + sm.getName()
                    + "' uses slaStartFrom="
                    + startFrom
                    + " which is not yet implemented."
                    + " Use CASE_CREATED or MILESTONE_ACTIVATED.");
          }
          milestoneBuilder.slaStartFrom(startFrom);
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
        if ("doneWhen".equals(kindValue)) continue;
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

    // TODO: Add support for CloudEventTrigger and ScheduleTrigger
    throw new UnsupportedOperationException(
        "Only ContextChangeTrigger is currently supported. "
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
}
