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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.JqFeatureExtractor;
import io.casehub.api.model.cbr.LambdaFeatureExtractor;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.eidos.api.VocabularyRegistry;
import io.casehub.engine.common.internal.config.ConfigManager;
import io.casehub.engine.common.internal.config.SecretManager;
import io.casehub.engine.common.internal.model.CaseKey;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.path.Path;
import io.casehub.worker.api.Worker;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Default implementation of {@link CaseDefinitionRegistry}.
 *
 * <p>Persists each definition's metadata via {@link CaseMetaModelRepository} on startup so the
 * engine can reference it by id.
 */
@ApplicationScoped
public class DefaultCaseDefinitionRegistry implements CaseDefinitionRegistry {

  private static final Logger LOG = Logger.getLogger(DefaultCaseDefinitionRegistry.class);

  /**
   * Dedicated ObjectMapper for serializing CaseDefinition metadata to JSON. Uses MixIns to exclude
   * non-serializable lambda fields (WorkerFunction, Predicate, Function) that exist only in the
   * in-memory model.
   */
  private static final ObjectMapper metadataMapper = createMetadataMapper();

  private final Map<CaseKey, RegistryEntry> registry = new ConcurrentHashMap<>();
  @Inject Instance<CaseHub> caseHubInstance;
  @Inject CaseMetaModelRepository caseMetaModelRepository;
  @Inject ExpressionEngineRegistry expressionEngineRegistry;
  @Inject SecretManager secretManager;
  @Inject ConfigManager configManager;
  @Inject CurrentPrincipal currentPrincipal;
  @Inject Instance<VocabularyRegistry> vocabularyRegistry;

  private static ObjectMapper createMetadataMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.addMixIn(Worker.class, WorkerMixIn.class);
    mapper.addMixIn(LambdaExpressionEvaluator.class, LambdaExpressionEvaluatorMixIn.class);
    mapper.addMixIn(LambdaFeatureExtractor.class, LambdaFeatureExtractorMixIn.class);
    return mapper;
  }

  void onStart(@Observes @Priority(10) StartupEvent ev) {
    registerKnownDefinitions();
  }

  void registerKnownDefinitions() {
    Map<CaseKey, String> seen = new java.util.LinkedHashMap<>();
    for (CaseHub hub : caseHubInstance) {
      CaseDefinition def = hub.getDefinition();
      CaseKey key = CaseKey.of(def);
      String beanName = hub.getClass().getName();
      String existing = seen.get(key);
      if (existing != null) {
        throw new IllegalStateException(
            String.format(
                "Duplicate CaseDefinition key %s/%s/%s — registered by both [%s] and [%s]. "
                    + "Each (namespace, name, version) tuple must be unique across all CaseHub beans.",
                key.namespace(), key.name(), key.version(), existing, beanName));
      }
      seen.put(key, beanName);
    }

    for (CaseHub hub : caseHubInstance) {
      registerCaseDefinitionBlocking(hub.getDefinition());
    }
  }

  private CaseMetaModel registerCaseDefinitionBlocking(CaseDefinition model) {
    validateExpressions(model);

    LOG.info(
        "Registering case: "
            + model.getName()
            + " version: "
            + model.getVersion()
            + " namespace: "
            + model.getNamespace());

    CaseKey key = CaseKey.of(model);

    RegistryEntry existing = registry.get(key);
    if (existing != null) {
      return existing.metaModel();
    }

    CaseMetaModel metaModel = new CaseMetaModel();
    metaModel.setName(model.getName());
    metaModel.setNamespace(model.getNamespace());
    metaModel.setVersion(model.getVersion());

    JsonNode definitionJson = serializeDefinition(model);

    CaseMetaModel dbModel =
        caseMetaModelRepository.findByKey(
            model.getNamespace(),
            model.getName(),
            model.getVersion(),
            currentPrincipal.tenancyId());
    if (dbModel != null) {
      registry.put(CaseKey.of(dbModel), new RegistryEntry(model, dbModel));
      return dbModel;
    }
    metaModel.setDsl(model.getDsl());
    metaModel.setDefinition(definitionJson);
    metaModel.setCreatedAt(Instant.now());
    CaseMetaModel saved = caseMetaModelRepository.save(metaModel, currentPrincipal.tenancyId());
    registry.put(CaseKey.of(saved), new RegistryEntry(model, saved));
    return saved;
  }

  @Override
  public Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model) {
    try {
      return Uni.createFrom().item(registerCaseDefinitionBlocking(model));
    } catch (IllegalArgumentException e) {
      LOG.errorf("Case definition '%s' rejected: %s", model.getName(), e.getMessage());
      return Uni.createFrom().failure(e);
    }
  }

  @Override
  public CaseDefinition getCaseDefinition(CaseMetaModel definition) {
    CaseKey lookupKey = CaseKey.of(definition);
    RegistryEntry entry = registry.get(lookupKey);
    if (entry == null) {
      LOG.errorf(
          "getCaseDefinition lookup miss — key=%s/%s/%s, registry contains %d entries: %s",
          lookupKey.namespace(),
          lookupKey.name(),
          lookupKey.version(),
          registry.size(),
          registry.keySet());
    }
    return entry != null ? entry.definition() : null;
  }

  @Override
  public Optional<CaseMetaModel> findByIdentity(String namespace, String name, String version) {
    RegistryEntry entry = registry.get(new CaseKey(namespace, name, version));
    return Optional.ofNullable(entry).map(RegistryEntry::metaModel);
  }

  @Override
  public Optional<CaseDefinition> findByName(String name) {
    List<RegistryEntry> matches =
        registry.values().stream().filter(e -> name.equals(e.definition().getName())).toList();
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.size() > 1) {
      throw new IllegalArgumentException(
          "Ambiguous caseType '"
              + name
              + "' — matches "
              + matches.size()
              + " definitions across namespaces. Use qualified lookup to disambiguate.");
    }
    return Optional.of(matches.get(0).definition());
  }

  @Override
  public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) {
    RegistryEntry entry = registry.get(CaseKey.of(caseDefinition));
    if (entry == null) {
      throw new RuntimeException(
          "CaseMetaModel not found for caseDefinition: "
              + caseDefinition.getNamespace()
              + "."
              + caseDefinition.getName()
              + ":"
              + caseDefinition.getVersion());
    }
    return entry.metaModel();
  }

  @Override
  public List<CaseDefinition> findByType(Path type) {
    return registry.values().stream()
        .map(RegistryEntry::definition)
        .filter(
            def -> def.getTypes().stream().anyMatch(t -> t.equals(type) || type.isAncestorOf(t)))
        .toList();
  }

  @Override
  public List<CaseDefinition> findByLabel(Path label) {
    return registry.values().stream()
        .map(RegistryEntry::definition)
        .filter(
            def -> def.getLabels().stream().anyMatch(l -> l.equals(label) || label.isAncestorOf(l)))
        .toList();
  }

  @Override
  public java.util.Collection<io.casehub.api.model.CaseDefinition> allDefinitions() {
    return registry.values().stream().map(RegistryEntry::definition).toList();
  }

  private void validateExpressions(CaseDefinition definition) {
    // Validate use.secrets and use.configMaps (fail-fast)
    validateDependencies(definition);

    if (definition.getBindings() != null) {
      for (Binding rule : definition.getBindings()) {
        if (rule.getOn() instanceof ContextChangeTrigger cct) {
          expressionEngineRegistry.validate(cct.getFilter());
        }
        expressionEngineRegistry.validate(rule.getWhen());
      }
    }
    if (definition.getMilestones() != null) {
      for (Milestone milestone : definition.getMilestones()) {
        if (milestone.getEntryCriteria() != null) {
          expressionEngineRegistry.validate(milestone.getEntryCriteria());
        }
        if (milestone.getCompletionCriteria() != null) {
          expressionEngineRegistry.validate(milestone.getCompletionCriteria());
        }
      }
    }
    if (definition.getGoals() != null) {
      for (Goal goal : definition.getGoals()) {
        expressionEngineRegistry.validate(goal.getCondition());
      }
    }
    if (definition.getCompletion() instanceof PredicateBasedCompletion pbc) {
      expressionEngineRegistry.validate(pbc.getDoneWhen());
    }

    // Warn if goals are not referenced in any GoalExpression
    if (definition.getGoals() != null
        && definition.getCompletion() instanceof GoalBasedCompletion<?> gbc) {
      Map<String, Goal> goalsByName =
          definition.getGoals().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      Goal::getName, java.util.function.Function.identity()));
      var referencedGoals = new HashSet<String>();
      for (var entry : gbc.getGoals().entrySet()) {
        GoalExpression expr = entry.getValue();
        if (expr != null) {
          referencedGoals.addAll(expr.goalNames());
        }
      }
      for (Goal goal : definition.getGoals()) {
        if (!referencedGoals.contains(goal.getName())) {
          LOG.warnf(
              "Goal '%s' is not referenced in any GoalExpression. "
                  + "Goals should drive case completion — use Milestone for non-terminal checkpoints.",
              goal.getName());
        }
      }

      // Kind mismatch warning
      for (var entry : gbc.getGoals().entrySet()) {
        String kindValue = entry.getKey().value();
        GoalExpression expr = entry.getValue();
        if (expr != null) {
          for (String goalName : expr.goalNames()) {
            Goal g = goalsByName.get(goalName);
            if (g != null && g.getKind() != null && !g.getKind().equals(kindValue)) {
              LOG.warnf(
                  "Goal '%s' has kind '%s' but is referenced in completion entry '%s'"
                      + " — kind mismatch may indicate a configuration error.",
                  g.getName(), g.getKind(), kindValue);
            }
          }
        }
      }
    }

    // Validate CbrConfig
    validateCbrConfig(definition);

    // Validate types and labels against VocabularyRegistry (advisory only)
    validateVocabularyPaths(definition);
  }

  private void validateCbrConfig(CaseDefinition definition) {
    CbrConfig cbrConfig = definition.getCbrConfig();
    if (cbrConfig == null) {
      return;
    }

    // Check 1: Warn if CbrConfig has no domain and no EpisodicMemoryConfig
    if (cbrConfig.domain() == null && definition.getEpisodicMemoryConfig() == null) {
      LOG.warnf(
          "CbrConfig is present but has no domain and no EpisodicMemoryConfig is configured. "
              + "CBR retrieval will always return empty results without a domain scope.");
    }

    // Check 2: Validate JQ expressions in JqFeatureExtractor
    if (cbrConfig.featureExtractor() instanceof JqFeatureExtractor jqExtractor) {
      for (var entry : jqExtractor.featureExpressions().entrySet()) {
        String featureName = entry.getKey();
        String jqExpression = entry.getValue();
        try {
          expressionEngineRegistry.validate(new JQExpressionEvaluator(jqExpression));
        } catch (Exception e) {
          LOG.warnf(
              "CbrConfig has invalid JQ expression for feature '%s': %s — expression: %s",
              featureName, e.getMessage(), jqExpression);
        }
      }
    }
  }

  private void validateVocabularyPaths(CaseDefinition definition) {
    if (!vocabularyRegistry.isResolvable()) {
      return;
    }

    // Validate type paths
    if (definition.getTypes() != null) {
      for (Path typePath : definition.getTypes()) {
        validatePathSegments(typePath, "type");
      }
    }

    // Validate label paths
    if (definition.getLabels() != null) {
      for (Path labelPath : definition.getLabels()) {
        validatePathSegments(labelPath, "label");
      }
    }
  }

  private void validatePathSegments(Path path, String pathKind) {
    VocabularyRegistry registry = vocabularyRegistry.get();

    String pathStr = path.value();

    int lastSlash = pathStr.lastIndexOf('/');
    if (lastSlash == -1) {
      return;
    }

    String vocabUri = pathStr.substring(0, lastSlash);
    String segment = pathStr.substring(lastSlash + 1);

    if (!registry.isRegistered(vocabUri)) {
      return;
    }

    Optional<?> resolved = registry.resolve(vocabUri, segment);
    if (resolved.isEmpty()) {
      LOG.warnf(
          "CaseDefinition has unresolvable %s path '%s' — vocabulary '%s' does not contain segment '%s'. "
              + "Vocabulary infrastructure might load this term later.",
          pathKind, pathStr, vocabUri, segment);
    }
  }

  /**
   * Serializes a CaseDefinition to a JsonNode for storage in the CaseMetaModel definition column.
   * Non-serializable lambda fields are excluded via MixIns.
   */
  private JsonNode serializeDefinition(CaseDefinition model) {
    try {
      return metadataMapper.valueToTree(model);
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Failed to serialize CaseDefinition '%s/%s/%s' — definition column will be null: %s",
          model.getNamespace(), model.getName(), model.getVersion(), e.getMessage());
      return null;
    }
  }

  /**
   * Validate use.secrets and use.configMaps declarations.
   *
   * <p>Fail-fast: throws IllegalArgumentException if any declared secret/configMap does not exist.
   *
   * @param definition case definition to validate
   * @throws IllegalArgumentException if validation fails
   */
  private void validateDependencies(CaseDefinition definition) {
    if (definition.getUse() == null) {
      return;
    }

    // Validate secrets
    if (definition.getUse().getSecrets() != null) {
      for (String secretName : definition.getUse().getSecrets()) {
        try {
          secretManager.secret(secretName);
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Secret '" + secretName + "' declared in use.secrets not found: " + e.getMessage(),
              e);
        }
      }
    }

    // Validate config maps
    if (definition.getUse().getConfigMaps() != null) {
      for (String configMapName : definition.getUse().getConfigMaps()) {
        try {
          configManager.configMap(configMapName);
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "ConfigMap '"
                  + configMapName
                  + "' declared in use.configMaps not found: "
                  + e.getMessage(),
              e);
        }
      }
    }
  }

  private record RegistryEntry(CaseDefinition definition, CaseMetaModel metaModel) {}

  /** Excludes the non-serializable {@code function} record component from Worker. */
  abstract static class WorkerMixIn {
    @JsonIgnore
    abstract Object function();
  }

  /** Excludes the non-serializable {@code predicate} field from LambdaExpressionEvaluator. */
  abstract static class LambdaExpressionEvaluatorMixIn {
    @JsonIgnore Object predicate;
  }

  /** Excludes the non-serializable {@code extractionFunction} field from LambdaFeatureExtractor. */
  abstract static class LambdaFeatureExtractorMixIn {
    @JsonIgnore Object extractionFunction;
  }
}
