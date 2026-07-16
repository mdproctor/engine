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
import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ContextLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.cbr.CbrCaseTypeRegistration;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming;
import io.casehub.api.model.cbr.JqFeatureExtractor;
import io.casehub.api.model.cbr.LambdaFeatureExtractor;
import io.casehub.api.spi.routing.ExperiencePlanStep;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.quarkus.arc.All;
import io.quarkus.arc.Lock;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CbrRetrievalService {

  private static final Logger LOG = Logger.getLogger(CbrRetrievalService.class);

  static final int MAX_CACHE_SIZE = 1000;

  private static final Map<String, Class<? extends CbrCase>> BUILT_IN_TYPES =
      Map.of(
          "plan", PlanCbrCase.class,
          "feature-vector", FeatureVectorCbrCase.class,
          "textual", TextualCbrCase.class);

  private final ConcurrentHashMap<UUID, List<RetrievedExperience>> cache =
      new ConcurrentHashMap<>();

  private final JQEvaluator jqEvaluator;
  private final CbrCaseMemoryStore cbrStore;
  private final Map<String, Class<? extends CbrCase>> typeMap;

  @Inject
  public CbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrCaseMemoryStore cbrStore,
      @All Instance<CbrCaseTypeRegistration> registrations) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.typeMap = buildTypeMap(registrations);
  }

  CbrRetrievalService(JQEvaluator jqEvaluator, CbrCaseMemoryStore cbrStore) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.typeMap = Map.copyOf(BUILT_IN_TYPES);
  }

  private static Map<String, Class<? extends CbrCase>> buildTypeMap(
      Instance<CbrCaseTypeRegistration> registrations) {
    Map<String, Class<? extends CbrCase>> map = new java.util.HashMap<>(BUILT_IN_TYPES);
    for (CbrCaseTypeRegistration reg : registrations) {
      @SuppressWarnings("unchecked")
      Class<? extends CbrCase> caseClass = (Class<? extends CbrCase>) reg.caseClass();
      Class<? extends CbrCase> existing = map.put(reg.cbrType(), caseClass);
      if (existing != null && !BUILT_IN_TYPES.containsKey(reg.cbrType())) {
        throw new IllegalStateException(
            "Duplicate CbrCaseTypeRegistration for cbrType '" + reg.cbrType() + "'");
      }
    }
    return Map.copyOf(map);
  }

  public Uni<List<RetrievedExperience>> retrieve(CaseDefinition definition, CaseInstance instance) {
    return Uni.createFrom()
        .<List<RetrievedExperience>>deferred(
            () -> {
              CbrConfig config = definition.getCbrConfig();
              if (config == null) {
                return Uni.createFrom().item(List.of());
              }
              String cbrType = config.cbrType() != null ? config.cbrType() : "plan";
              Class<? extends CbrCase> caseClass = typeMap.get(cbrType);
              if (caseClass == null) {
                throw new IllegalStateException("Unknown cbrType: " + cbrType);
              }
              return retrieveInternal(definition, instance, caseClass);
            })
        .onFailure()
        .recoverWithItem(
            failure -> {
              LOG.warnf(
                  failure,
                  "CBR retrieval failed for case definition '%s' — proceeding without experiences",
                  definition.getName());
              return List.of();
            });
  }

  public <C extends CbrCase> Uni<List<RetrievedExperience>> retrieve(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    return retrieveInternal(definition, instance, caseClass);
  }

  private <C extends CbrCase> Uni<List<RetrievedExperience>> retrieveInternal(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    return Uni.createFrom()
        .<List<RetrievedExperience>>deferred(
            () -> {
              CbrConfig config = definition.getCbrConfig();
              if (config == null) {
                return Uni.createFrom().item(List.of());
              }

              if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
                List<RetrievedExperience> cached = cache.get(instance.getUuid());
                if (cached != null) {
                  return Uni.createFrom().item(cached);
                }
              }

              Map<String, FeatureValue> features =
                  extractFeatures(config, instance.getCaseContext());
              if (features.isEmpty()) {
                return Uni.createFrom().item(List.of());
              }

              String resolvedDomain = resolveDomain(config, definition);
              if (resolvedDomain == null) {
                LOG.warnf(
                    "CbrConfig present but domain unresolvable for case definition '%s' — CBR retrieval skipped",
                    definition.getName());
                return Uni.createFrom().item(List.of());
              }

              String caseType =
                  config.caseType() != null ? config.caseType() : definition.getName();

              CbrQuery query =
                  CbrQuery.of(
                          instance.tenancyId,
                          new MemoryDomain(resolvedDomain),
                          io.casehub.platform.api.path.Path.root(),
                          caseType,
                          features,
                          config.topK())
                      .withMinSimilarity(config.minSimilarity())
                      .withWeights(config.weights())
                      .withVectorWeight(config.vectorWeight());

              return Uni.createFrom()
                  .item(() -> cbrStore.retrieveSimilar(query, caseClass))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .map(this::mapResults)
                  .map(List::copyOf)
                  .invoke(
                      result -> {
                        if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
                          cacheIfUnderBound(instance.getUuid(), result);
                        }
                      });
            })
        .onFailure()
        .recoverWithItem(
            failure -> {
              LOG.warnf(
                  failure,
                  "CBR retrieval failed for case definition '%s' — proceeding without experiences",
                  definition.getName());
              return List.of();
            });
  }

  @Lock(Lock.Type.WRITE)
  void cacheIfUnderBound(UUID caseId, List<RetrievedExperience> result) {
    if (cache.size() < MAX_CACHE_SIZE) {
      cache.putIfAbsent(caseId, result);
    }
  }

  public void evict(UUID caseId) {
    cache.remove(caseId);
  }

  int cacheSize() {
    return cache.size();
  }

  private Map<String, FeatureValue> extractFeatures(CbrConfig config, CaseContext context) {
    return switch (config.featureExtractor()) {
      case JqFeatureExtractor jq -> extractJqFeatures(jq, context);
      case LambdaFeatureExtractor lambda -> FeatureValue.toFeatureMap(lambda.extract(context));
    };
  }

  private Map<String, FeatureValue> extractJqFeatures(JqFeatureExtractor jq, CaseContext context) {
    JsonNode workingNode = context.layer(ContextLayer.WORKING).asJsonNode();
    Map<String, FeatureValue> features = new LinkedHashMap<>();

    for (Map.Entry<String, String> entry : jq.featureExpressions().entrySet()) {
      String featureName = entry.getKey();
      String expression = entry.getValue();

      ValidationResult result = jqEvaluator.eval(expression, workingNode);
      if (!result.ok()) {
        LOG.warnf(
            "JQ feature extraction error for '%s' (expr: %s): %s",
            featureName, expression, result.error());
        continue;
      }

      List<JsonNode> output = result.output();
      if (output == null || output.isEmpty()) {
        LOG.debugf("JQ feature '%s' returned no output, skipping", featureName);
        continue;
      }

      JsonNode node = output.get(0);
      Object value = unwrap(node);
      if (value == null) {
        LOG.debugf("JQ feature '%s' resolved to null, skipping", featureName);
        continue;
      }

      features.put(featureName, FeatureValue.of(value));
    }

    return features;
  }

  private String resolveDomain(CbrConfig config, CaseDefinition definition) {
    if (config.domain() != null) {
      return config.domain();
    }
    EpisodicMemoryConfig episodic = definition.getEpisodicMemoryConfig();
    if (episodic != null) {
      return episodic.domain();
    }
    return null;
  }

  private <C extends CbrCase> List<RetrievedExperience> mapResults(
      List<ScoredCbrCase<C>> scoredCases) {
    return scoredCases.stream().map(this::mapScoredCase).toList();
  }

  private <C extends CbrCase> RetrievedExperience mapScoredCase(ScoredCbrCase<C> scored) {
    CbrCase c = scored.cbrCase();
    List<ExperiencePlanStep> trace =
        (c instanceof PlanCbrCase plan) ? mapPlanTrace(plan.planTrace()) : List.of();
    return new RetrievedExperience(
        c.problem(),
        c.solution(),
        c.outcome(),
        c.confidence(),
        scored.score(),
        new LinkedHashMap<>(c.features()),
        trace,
        scored.featureSimilarities());
  }

  private List<ExperiencePlanStep> mapPlanTrace(List<PlanTrace> traces) {
    return traces.stream()
        .map(
            t ->
                new ExperiencePlanStep(
                    t.bindingName(),
                    t.capabilityName(),
                    t.workerName(),
                    t.stepOutcome(),
                    t.priority(),
                    t.parameters()))
        .toList();
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return null;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isInt()) {
      return node.asInt();
    }
    if (node.isLong()) {
      return node.asLong();
    }
    if (node.isDouble() || node.isFloat()) {
      return node.asDouble();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    return node.toString();
  }
}
