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
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.PredicateBasedCompletion;
import io.casehub.engine.internal.model.CaseMetaModel;
import io.casehub.engine.internal.utils.ReactiveUtils;
import io.casehub.engine.spi.CaseDefinitionRegistry;
import io.casehub.engine.spi.CaseMetaModelRepository;
import io.casehub.engine.spi.ExpressionEngineRegistry;
import io.casehub.platform.api.expression.ConfigManager;
import io.casehub.platform.api.expression.SecretManager;
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

  private final Map<CaseMetaModel, CaseDefinition> registry = new ConcurrentHashMap<>();

  private static final Logger LOG = Logger.getLogger(DefaultCaseDefinitionRegistry.class);

  @Inject Instance<CaseHub> caseHubInstance;

  @Inject CaseMetaModelRepository caseMetaModelRepository;

  @Inject Vertx vertx;

  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @Inject SecretManager secretManager;

  @Inject ConfigManager configManager;

  void onStart(@Observes @Priority(10) StartupEvent ev) {
    ReactiveUtils.runOnSafeVertxContext(vertx, this::registerKnownDefinitions)
        .await()
        .atMost(Duration.ofSeconds(30)); // TODO this timeout must be configurable
  }

  Uni<Void> registerKnownDefinitions() {
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

    CaseMetaModel definition = new CaseMetaModel();
    definition.setName(model.getName());
    definition.setNamespace(model.getNamespace());
    definition.setVersion(model.getVersion());

    for (CaseMetaModel registered : registry.keySet()) {
      if (registered.equals(definition)) {
        return Uni.createFrom().item(registered);
      }
    }

    return caseMetaModelRepository
        .findByKey(model.getNamespace(), model.getName(), model.getVersion())
        .onItem()
        .transformToUni(
            existing -> {
              if (existing != null) {
                registry.put(existing, model);
                return Uni.createFrom().item(existing);
              }
              definition.setDsl(model.getDsl());
              definition.setCreatedAt(Instant.now());
              return caseMetaModelRepository
                  .save(definition)
                  .invoke(saved -> registry.put(saved, model));
            });
  }

  @Override
  public CaseDefinition getCaseDefinition(CaseMetaModel definition) {
    return registry.get(definition);
  }

  @Override
  public CaseMetaModel getCaseMetaModel(CaseDefinition caseDefinition) {
    for (Map.Entry<CaseMetaModel, CaseDefinition> entry : registry.entrySet()) {
      if (entry.getValue().equals(caseDefinition)) {
        return entry.getKey();
      }
    }
    throw new RuntimeException(
        "CaseMetaModel not found for caseDefinition: "
            + caseDefinition.getNamespace()
            + "."
            + caseDefinition.getName()
            + ":"
            + caseDefinition.getVersion());
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
