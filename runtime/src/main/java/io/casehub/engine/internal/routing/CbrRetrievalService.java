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
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.quarkus.arc.Lock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Runtime bridge for CBR retrieval. Evaluates feature extractors against the case context, builds a
 * {@link CbrQuery}, calls the {@link CbrCaseMemoryStore}, and maps results to engine-owned {@link
 * RetrievedExperience} types.
 *
 * <p>When {@link CbrRetrievalTiming#CASE_LIFETIME} is configured, retrieval results are cached per
 * case UUID and reused for the case's lifetime. The {@link #MAX_CACHE_SIZE} bound is enforced
 * atomically via {@link io.quarkus.arc.Lock @Lock(WRITE)} on {@link #cacheIfUnderBound}. Eviction
 * is triggered by {@link CbrCacheEvictionHandler} on terminal state transitions.
 *
 * <p>CBR failure never blocks case progression — the full chain is wrapped with {@code
 * .onFailure().recoverWithItem(List.of())}.
 */
@ApplicationScoped
public class CbrRetrievalService {

  private static final Logger LOG = Logger.getLogger(CbrRetrievalService.class);

  static final int MAX_CACHE_SIZE = 1000;

  private final ConcurrentHashMap<UUID, List<RetrievedExperience>> cache =
      new ConcurrentHashMap<>();

  private final JQEvaluator jqEvaluator;
  private final CbrCaseMemoryStore cbrStore;

  @Inject
  public CbrRetrievalService(JQEvaluator jqEvaluator, CbrCaseMemoryStore cbrStore) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
  }

  /**
   * Retrieve similar past cases for the given case definition and instance.
   *
   * @param definition the case definition (carries CbrConfig)
   * @param instance the current case instance (carries context and tenancy)
   * @return list of retrieved experiences, or empty list on failure or missing config
   */
  public Uni<List<RetrievedExperience>> retrieve(CaseDefinition definition, CaseInstance instance) {
    return Uni.createFrom()
        .<List<RetrievedExperience>>deferred(
            () -> {
              CbrConfig config = definition.getCbrConfig();
              if (config == null) {
                return Uni.createFrom().item(List.of());
              }

              // Cache hit path for CASE_LIFETIME timing
              if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
                List<RetrievedExperience> cached = cache.get(instance.getUuid());
                if (cached != null) {
                  return Uni.createFrom().item(cached);
                }
              }

              Map<String, Object> features = extractFeatures(config, instance.getCaseContext());
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
                          caseType,
                          features,
                          config.topK())
                      .withMinSimilarity(config.minSimilarity())
                      .withWeights(config.weights())
                      .withVectorWeight(config.vectorWeight());

              return Uni.createFrom()
                  .item(() -> cbrStore.retrieveSimilar(query, PlanCbrCase.class))
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

  /**
   * Cache a retrieval result if the cache has not reached its bound. The {@code @Lock(WRITE)}
   * annotation makes size-check + put atomic, eliminating the TOCTOU race on MAX_CACHE_SIZE.
   */
  @Lock(Lock.Type.WRITE)
  void cacheIfUnderBound(UUID caseId, List<RetrievedExperience> result) {
    if (cache.size() < MAX_CACHE_SIZE) {
      cache.putIfAbsent(caseId, result);
    }
  }

  /**
   * Evict the cached CBR retrieval result for the given case ID. Called by {@link
   * CbrCacheEvictionHandler} when a case reaches a terminal state.
   *
   * @param caseId the case UUID to evict
   */
  public void evict(UUID caseId) {
    cache.remove(caseId);
  }

  /** Returns the current cache size. Package-private for testing. */
  int cacheSize() {
    return cache.size();
  }

  private Map<String, Object> extractFeatures(CbrConfig config, CaseContext context) {
    return switch (config.featureExtractor()) {
      case JqFeatureExtractor jq -> extractJqFeatures(jq, context);
      case LambdaFeatureExtractor lambda -> lambda.extract(context);
    };
  }

  private Map<String, Object> extractJqFeatures(JqFeatureExtractor jq, CaseContext context) {
    JsonNode workingNode = context.layer(ContextLayer.WORKING).asJsonNode();
    Map<String, Object> features = new LinkedHashMap<>();

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

      features.put(featureName, value);
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

  private List<RetrievedExperience> mapResults(List<ScoredCbrCase<PlanCbrCase>> scoredCases) {
    return scoredCases.stream().map(this::mapScoredCase).toList();
  }

  private RetrievedExperience mapScoredCase(ScoredCbrCase<PlanCbrCase> scored) {
    PlanCbrCase c = scored.cbrCase();
    return new RetrievedExperience(
        c.problem(),
        c.solution(),
        c.outcome(),
        c.confidence(),
        scored.score(),
        c.features(),
        mapPlanTrace(c.planTrace()));
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

  /**
   * Unwraps a JsonNode to a plain Java value. Returns null for null/missing nodes, the appropriate
   * primitive for value nodes, and the textual representation for complex nodes.
   */
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
    // For arrays and objects, return the textual JSON representation
    return node.toString();
  }
}
