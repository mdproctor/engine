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

import io.casehub.api.engine.CaseHub;
import io.casehub.api.engine.ExpressionEngineRegistry;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.engine.common.internal.config.ConfigManager;
import io.casehub.engine.common.internal.config.SecretManager;
import io.casehub.engine.common.internal.model.CaseKey;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.utils.ReactiveUtils;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseMetaModelRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
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

  private record RegistryEntry(CaseDefinition definition, CaseMetaModel metaModel) {}

  private final Map<CaseKey, RegistryEntry> registry = new ConcurrentHashMap<>();

  private static final Logger LOG = Logger.getLogger(DefaultCaseDefinitionRegistry.class);

  @Inject Instance<CaseHub> caseHubInstance;

  @Inject CaseMetaModelRepository caseMetaModelRepository;

  @Inject Vertx vertx;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject SecretManager secretManager;

  @Inject ConfigManager configManager;

  @Inject CurrentPrincipal currentPrincipal;

  void onStart(@Observes @Priority(10) StartupEvent ev) {
    ReactiveUtils.runOnSafeVertxContext(vertx, this::registerKnownDefinitions)
        .await()
        .atMost(Duration.ofSeconds(30)); // TODO this timeout must be configurable
  }

  Uni<Void> registerKnownDefinitions() {
    // Fail-fast: detect CaseHub beans that produce definitions with the same key.
    // Two different beans sharing a key is always a bug — the registry uses "first wins",
    // so whichever registers first silently shadows the other (engine#480).
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

    return Multi.createFrom()
        .iterable(caseHubInstance)
        .onItem()
        .transformToUniAndConcatenate(hub -> registerCaseDefinition(hub.getDefinition()))
        .collect()
        .last()
        .replaceWithVoid();
  }

  @Override
  public Uni<CaseMetaModel> registerCaseDefinition(CaseDefinition model) {
    try {
      validateExpressions(model);
    } catch (IllegalArgumentException e) {
      LOG.errorf("Case definition '%s' rejected: %s", model.getName(), e.getMessage());
      return Uni.createFrom().failure(e);
    }

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
      return Uni.createFrom().item(existing.metaModel());
    }

    CaseMetaModel definition = new CaseMetaModel();
    definition.setName(model.getName());
    definition.setNamespace(model.getNamespace());
    definition.setVersion(model.getVersion());

    return caseMetaModelRepository
        .findByKey(
            model.getNamespace(), model.getName(), model.getVersion(), currentPrincipal.tenancyId())
        .onItem()
        .transformToUni(
            dbModel -> {
              if (dbModel != null) {
                registry.put(CaseKey.of(dbModel), new RegistryEntry(model, dbModel));
                return Uni.createFrom().item(dbModel);
              }
              definition.setDsl(model.getDsl());
              definition.setCreatedAt(Instant.now());
              return caseMetaModelRepository
                  .save(definition, currentPrincipal.tenancyId())
                  .invoke(
                      saved -> registry.put(CaseKey.of(saved), new RegistryEntry(model, saved)));
            });
  }

  @Override
  public CaseDefinition getCaseDefinition(CaseMetaModel definition) {
    RegistryEntry entry = registry.get(CaseKey.of(definition));
    return entry != null ? entry.definition() : null;
  }

  @Override
  public Optional<CaseMetaModel> findByIdentity(String namespace, String name, String version) {
    RegistryEntry entry = registry.get(new CaseKey(namespace, name, version));
    return Optional.ofNullable(entry).map(RegistryEntry::metaModel);
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
}
