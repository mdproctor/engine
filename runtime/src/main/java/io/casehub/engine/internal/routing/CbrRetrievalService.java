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
import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.PlanAdapter;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import io.casehub.neocortex.memory.cbr.TextualCbrCase;
import io.quarkus.arc.All;
import io.quarkus.arc.Lock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CbrRetrievalService {

  static final int MAX_CACHE_SIZE = 1000;
  private static final Logger LOG = Logger.getLogger(CbrRetrievalService.class);
  private static final Map<String, Class<? extends CbrCase>> BUILT_IN_TYPES =
      Map.of(
          "plan", PlanCbrCase.class,
          "feature-vector", FeatureVectorCbrCase.class,
          "textual", TextualCbrCase.class);

  private final ConcurrentHashMap<UUID, List<RetrievedExperience>> cache =
      new ConcurrentHashMap<>();

  private final JQEvaluator jqEvaluator;
  private final CbrCaseMemoryStore cbrStore;
  private final PlanAdapter planAdapter;
  private final Map<String, Class<? extends CbrCase>> typeMap;

  @Inject
  public CbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrCaseMemoryStore cbrStore,
      PlanAdapter planAdapter,
      @All Instance<CbrCaseTypeRegistration> registrations) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.planAdapter = planAdapter;
    this.typeMap = buildTypeMap(registrations);
  }

  CbrRetrievalService(
      JQEvaluator jqEvaluator, CbrCaseMemoryStore cbrStore, PlanAdapter planAdapter) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.planAdapter = planAdapter;
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

  public List<RetrievedExperience> retrieve(CaseDefinition definition, CaseInstance instance) {
    try {
      CbrConfig config = definition.getCbrConfig();
      if (config == null) {
        return List.of();
      }
      String cbrType = config.cbrType() != null ? config.cbrType() : "plan";
      Class<? extends CbrCase> caseClass = typeMap.get(cbrType);
      if (caseClass == null) {
        throw new IllegalStateException("Unknown cbrType: " + cbrType);
      }
      return retrieveInternal(definition, instance, caseClass);
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR retrieval failed for case definition '%s' — proceeding without experiences",
          definition.getName());
      return List.of();
    }
  }

  public <C extends CbrCase> List<RetrievedExperience> retrieve(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    return retrieveInternal(definition, instance, caseClass);
  }

  private <C extends CbrCase> List<RetrievedExperience> retrieveInternal(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    try {
      CbrConfig config = definition.getCbrConfig();
      if (config == null) {
        return List.of();
      }

      if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
        List<RetrievedExperience> cached = cache.get(instance.getUuid());
        if (cached != null) {
          return cached;
        }
      }

      Map<String, FeatureValue> features = extractFeatures(config, instance.getCaseContext());
      if (features.isEmpty()) {
        return List.of();
      }

      String resolvedDomain = resolveDomain(config, definition);
      if (resolvedDomain == null) {
        LOG.warnf(
            "CbrConfig present but domain unresolvable for case definition '%s' — CBR retrieval skipped",
            definition.getName());
        return List.of();
      }

      String caseType = config.caseType() != null ? config.caseType() : definition.getName();

      CbrQuery baseQuery =
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

      CbrQuery query =
          config.temporalDecayHalfLifeDays() != null
              ? baseQuery.withTemporalDecay(
                  new TemporalDecay.HalfLife(Duration.ofDays(config.temporalDecayHalfLifeDays())))
              : baseQuery;

      List<ScoredCbrCase<C>> scoredCases = cbrStore.retrieveSimilar(query, caseClass);
      List<RetrievedExperience> result = List.copyOf(mapResults(scoredCases, caseType, features));

      if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
        cacheIfUnderBound(instance.getUuid(), result);
      }

      return result;
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR retrieval failed for case definition '%s' — proceeding without experiences",
          definition.getName());
      return List.of();
    }
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
      List<ScoredCbrCase<C>> scoredCases, String caseType, Map<String, FeatureValue> features) {
    return scoredCases.stream().map(s -> mapScoredCase(s, caseType, features)).toList();
  }

  @SuppressWarnings("unchecked")
  private <C extends CbrCase> RetrievedExperience mapScoredCase(
      ScoredCbrCase<C> scored, String caseType, Map<String, FeatureValue> features) {
    CbrCase c = scored.cbrCase();
    List<ExperiencePlanStep> trace;
    if (c instanceof PlanCbrCase) {
      trace = adaptAndMapPlanTrace((ScoredCbrCase<PlanCbrCase>) scored, caseType, features);
    } else {
      trace = List.of();
    }
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

  private List<ExperiencePlanStep> adaptAndMapPlanTrace(
      ScoredCbrCase<PlanCbrCase> scored, String caseType, Map<String, FeatureValue> features) {
    try {
      AdaptedPlan adapted = planAdapter.adapt(caseType, scored, features);
      return adapted.steps().stream()
          .filter(s -> s.action() != AdaptationAction.REMOVED)
          .map(
              s ->
                  new ExperiencePlanStep(
                      s.bindingName(),
                      s.capabilityName(),
                      s.workerName(),
                      s.stepOutcome(),
                      s.priority(),
                      s.parameters(),
                      s.action().name(),
                      s.reason()))
          .toList();
    } catch (Exception e) {
      LOG.warnf(e, "PlanAdapter.adapt() failed — falling back to raw plan trace");
      return mapPlanTrace(scored.cbrCase().planTrace());
    }
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
}
