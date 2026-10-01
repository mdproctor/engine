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
import io.casehub.api.spi.routing.AgreementLevel;
import io.casehub.api.spi.routing.CbrRetrievalResult;
import io.casehub.api.spi.routing.ConsensusScope;
import io.casehub.api.spi.routing.DocumentStep;
import io.casehub.api.spi.routing.EnsembleConsensus;
import io.casehub.api.spi.routing.ExperienceAnalyser;
import io.casehub.api.spi.routing.ExperiencePlanStep;
import io.casehub.api.spi.routing.ResolutionSourceType;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.RoutingOutcome;
import io.casehub.api.spi.routing.StepConsensusEntry;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.jq.ValidationResult;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.CbrGuidanceRecord;
import io.casehub.neocortex.memory.cbr.CbrGuidanceStep;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrPlanAdapter;
import io.casehub.neocortex.memory.cbr.CbrPlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.CbrPlanRecord;
import io.casehub.neocortex.memory.cbr.CbrPlanStep;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.EnsemblePlan;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

public class CbrRetrievalService {

  static final int MAX_CACHE_SIZE = 1000;
  static final long DEFAULT_ENSEMBLE_TIMEOUT_MS = 5000L;
  private static final Logger LOG = Logger.getLogger(CbrRetrievalService.class);
  private static final Map<String, Class<? extends CbrRecord>> BUILT_IN_TYPES =
      Map.of(
          "plan", CbrPlanRecord.class,
          "feature-vector", CbrFeatureRecord.class,
          "textual", CbrGuidanceRecord.class);

  private final ConcurrentHashMap<UUID, CbrRetrievalResult> cache = new ConcurrentHashMap<>();

  private final JQEvaluator jqEvaluator;
  private final CbrRecordStore cbrStore;
  private final CbrPlanAdapter planAdapter;
  private final CbrPlanEnsembleAnalyzer ensembleAnalyzer;
  private final Map<String, Class<? extends CbrRecord>> typeMap;
  private final long ensembleTimeoutMs;

  record AdaptationResult(AdaptedPlan adaptedPlan, List<ExperiencePlanStep> steps) {}

  public CbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrRecordStore cbrStore,
      CbrPlanAdapter planAdapter,
      CbrPlanEnsembleAnalyzer ensembleAnalyzer,
      List<CbrCaseTypeRegistration> registrations,
      long ensembleTimeoutMs) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.planAdapter = planAdapter;
    this.ensembleAnalyzer = ensembleAnalyzer;
    this.typeMap = buildTypeMap(registrations);
    this.ensembleTimeoutMs = ensembleTimeoutMs;
  }

  CbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrRecordStore cbrStore,
      CbrPlanAdapter planAdapter,
      CbrPlanEnsembleAnalyzer ensembleAnalyzer) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.planAdapter = planAdapter;
    this.ensembleAnalyzer = ensembleAnalyzer;
    this.typeMap = Map.copyOf(BUILT_IN_TYPES);
    this.ensembleTimeoutMs = DEFAULT_ENSEMBLE_TIMEOUT_MS;
  }

  CbrRetrievalService(
      JQEvaluator jqEvaluator,
      CbrRecordStore cbrStore,
      CbrPlanAdapter planAdapter,
      CbrPlanEnsembleAnalyzer ensembleAnalyzer,
      long ensembleTimeoutMs) {
    this.jqEvaluator = jqEvaluator;
    this.cbrStore = cbrStore;
    this.planAdapter = planAdapter;
    this.ensembleAnalyzer = ensembleAnalyzer;
    this.typeMap = Map.copyOf(BUILT_IN_TYPES);
    this.ensembleTimeoutMs = ensembleTimeoutMs;
  }

  private static Map<String, Class<? extends CbrRecord>> buildTypeMap(
      List<CbrCaseTypeRegistration> registrations) {
    Map<String, Class<? extends CbrRecord>> map = new java.util.HashMap<>(BUILT_IN_TYPES);
    for (CbrCaseTypeRegistration reg : registrations) {
      @SuppressWarnings("unchecked")
      Class<? extends CbrRecord> caseClass = (Class<? extends CbrRecord>) reg.caseClass();
      Class<? extends CbrRecord> existing = map.put(reg.cbrType(), caseClass);
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

  public CbrRetrievalResult retrieve(CaseDefinition definition, CaseInstance instance) {
    try {
      CbrConfig config = definition.getCbrConfig();
      if (config == null) {
        return CbrRetrievalResult.empty();
      }
      String cbrType = config.cbrType() != null ? config.cbrType() : "plan";
      Class<? extends CbrRecord> caseClass = typeMap.get(cbrType);
      if (caseClass == null) {
        throw new IllegalStateException("Unknown cbrType: " + cbrType);
      }
      return retrieveInternal(definition, instance, caseClass);
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR retrieval failed for case definition '%s' — proceeding without experiences",
          definition.getName());
      return CbrRetrievalResult.empty();
    }
  }

  public <C extends CbrRecord> CbrRetrievalResult retrieve(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    return retrieveInternal(definition, instance, caseClass);
  }

  public List<RetrievedExperience> retrieveForSelection(
      String tenancyId,
      String domain,
      Map<String, FeatureValue> features,
      int topK,
      double minSimilarity,
      Map<String, Double> weights) {
    return retrieveForSelection(
        tenancyId, domain, features, topK, minSimilarity, weights, CbrPlanRecord.class);
  }

  public <C extends CbrRecord> List<RetrievedExperience> retrieveForSelection(
      String tenancyId,
      String domain,
      Map<String, FeatureValue> features,
      int topK,
      double minSimilarity,
      Map<String, Double> weights,
      Class<C> caseClass) {
    try {
      if (features.isEmpty()) {
        return List.of();
      }

      CbrQuery query =
          CbrQuery.crossType(
                  tenancyId,
                  new MemoryDomain(domain),
                  io.casehub.platform.api.path.Path.root(),
                  features,
                  topK)
              .withMinSimilarity(minSimilarity)
              .withWeights(weights);

      List<CbrMatch<C>> scoredCases = cbrStore.retrieveSimilar(query, caseClass);
      return List.copyOf(mapResults(scoredCases, features));
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR selection retrieval failed for domain '%s' — proceeding without experiences",
          domain);
      return List.of();
    }
  }

  public CbrRetrievalResult retrieveForSelectionWithEnsemble(
      String tenancyId,
      String domain,
      Map<String, FeatureValue> features,
      int topK,
      double minSimilarity,
      Map<String, Double> weights,
      String caseType) {
    return retrieveForSelectionWithEnsemble(
        tenancyId, domain, features, topK, minSimilarity, weights, caseType, CbrPlanRecord.class);
  }

  @SuppressWarnings("unchecked")
  public <C extends CbrRecord> CbrRetrievalResult retrieveForSelectionWithEnsemble(
      String tenancyId,
      String domain,
      Map<String, FeatureValue> features,
      int topK,
      double minSimilarity,
      Map<String, Double> weights,
      String caseType,
      Class<C> caseClass) {
    try {
      if (features.isEmpty()) {
        return CbrRetrievalResult.empty();
      }

      CbrQuery query =
          CbrQuery.crossType(
                  tenancyId,
                  new MemoryDomain(domain),
                  io.casehub.platform.api.path.Path.root(),
                  features,
                  topK)
              .withMinSimilarity(minSimilarity)
              .withWeights(weights);

      List<CbrMatch<C>> scoredCases = cbrStore.retrieveSimilar(query, caseClass);
      List<RetrievedExperience> experiences = List.copyOf(mapResults(scoredCases, features));

      if (experiences.size() < 2) {
        return new CbrRetrievalResult(experiences, null);
      }

      List<CbrMatch<CbrPlanRecord>> planCases = new ArrayList<>();
      List<AdaptedPlan> rawAdaptedPlans = new ArrayList<>();
      for (CbrMatch<C> sc : scoredCases) {
        if (sc.cbrRecord() instanceof CbrPlanRecord rc) {
          planCases.add((CbrMatch<CbrPlanRecord>) (CbrMatch<?>) sc);
          List<AdaptedStep> retainedSteps =
              rc.cbrPlanStep().stream()
                  .map(
                      step ->
                          new AdaptedStep(
                              step.bindingName(),
                              step.capabilityName(),
                              step.workerName(),
                              step.stepOutcome(),
                              step.priority(),
                              step.parameters(),
                              AdaptationAction.RETAINED,
                              null))
                  .toList();
          rawAdaptedPlans.add(new AdaptedPlan(retainedSteps));
        }
      }

      EnsembleConsensus ensemble;
      if (caseType != null && planCases.size() >= 2) {
        ensemble = invokeEnsembleAnalyzer(caseType, planCases, rawAdaptedPlans, features);
      } else {
        ensemble = buildOutcomeOnlyConsensus(experiences, scoredCases);
      }

      return new CbrRetrievalResult(experiences, ensemble);
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR selection retrieval with ensemble failed for domain '%s'"
              + " — proceeding without experiences",
          domain);
      return CbrRetrievalResult.empty();
    }
  }

  @SuppressWarnings("unchecked")
  private <C extends CbrRecord> CbrRetrievalResult retrieveInternal(
      CaseDefinition definition, CaseInstance instance, Class<C> caseClass) {
    try {
      CbrConfig config = definition.getCbrConfig();
      if (config == null) {
        return CbrRetrievalResult.empty();
      }

      if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME) {
        CbrRetrievalResult cached = cache.get(instance.getUuid());
        if (cached != null) {
          return cached;
        }
      }

      FeatureExtractionResult extraction = extractFeatures(config, instance.getCaseContext());
      Map<String, FeatureValue> features = extraction.features();
      if (features.isEmpty()) {
        return CbrRetrievalResult.empty();
      }

      String resolvedDomain = resolveDomain(config, definition);
      if (resolvedDomain == null) {
        LOG.warnf(
            "CbrConfig present but domain unresolvable for case definition '%s'"
                + " — CBR retrieval skipped",
            definition.getName());
        return CbrRetrievalResult.empty();
      }

      io.casehub.platform.api.path.Path queryScope =
          config.scope() != null
              ? io.casehub.platform.api.path.Path.of(config.scope().split("/"))
              : io.casehub.platform.api.path.Path.root();

      CbrQuery baseQuery;
      if (config.crossType()) {
        baseQuery =
            CbrQuery.crossType(
                    instance.tenancyId,
                    new MemoryDomain(resolvedDomain),
                    queryScope,
                    features,
                    config.topK())
                .withMinSimilarity(config.minSimilarity())
                .withWeights(config.weights())
                .withVectorWeight(config.vectorWeight());
      } else {
        String caseType = config.caseType() != null ? config.caseType() : definition.getName();
        baseQuery =
            CbrQuery.of(
                    instance.tenancyId,
                    new MemoryDomain(resolvedDomain),
                    queryScope,
                    caseType,
                    features,
                    config.topK())
                .withMinSimilarity(config.minSimilarity())
                .withWeights(config.weights())
                .withVectorWeight(config.vectorWeight());
      }

      if (queryScope.equals(io.casehub.platform.api.path.Path.root())) {
        baseQuery =
            baseQuery.withScopeDecay(new io.casehub.neocortex.memory.cbr.ScopeDecay.Step(1.0));
      }

      CbrQuery query =
          config.temporalDecayHalfLifeDays() != null
              ? baseQuery.withTemporalDecay(
                  new TemporalDecay.HalfLife(Duration.ofDays(config.temporalDecayHalfLifeDays())))
              : baseQuery;

      List<CbrMatch<C>> scoredCases = cbrStore.retrieveSimilar(query, caseClass);

      List<CbrMatch<CbrPlanRecord>> planCases = new ArrayList<>();
      List<AdaptationResult> planAdaptations = new ArrayList<>();
      List<RetrievedExperience> experiences = new ArrayList<>(scoredCases.size());

      for (CbrMatch<C> scored : scoredCases) {
        CbrRecord c = scored.cbrRecord();
        String resultCaseType = scored.caseType();
        List<ExperiencePlanStep> trace;

        if (c instanceof CbrPlanRecord) {
          CbrMatch<CbrPlanRecord> planScored = (CbrMatch<CbrPlanRecord>) scored;
          AdaptationResult adaptation = adaptPlan(planScored, resultCaseType, features);
          planCases.add(planScored);
          planAdaptations.add(adaptation);
          trace = adaptation.steps();
        } else {
          trace = List.of();
        }

        experiences.add(buildExperience(c, scored, trace, resultCaseType));
      }

      List<RetrievedExperience> immutableExperiences = List.copyOf(experiences);
      EnsembleConsensus ensemble =
          computeEnsemble(
              config,
              definition,
              immutableExperiences,
              planCases,
              planAdaptations,
              scoredCases,
              features);

      CbrRetrievalResult result = new CbrRetrievalResult(immutableExperiences, ensemble);

      if (config.timing() == CbrRetrievalTiming.CASE_LIFETIME && extraction.complete()) {
        cacheIfUnderBound(instance.getUuid(), result);
      }

      return result;
    } catch (Exception failure) {
      LOG.warnf(
          failure,
          "CBR retrieval failed for case definition '%s' — proceeding without experiences",
          definition.getName());
      return CbrRetrievalResult.empty();
    }
  }

  private <C extends CbrRecord> EnsembleConsensus computeEnsemble(
      CbrConfig config,
      CaseDefinition definition,
      List<RetrievedExperience> experiences,
      List<CbrMatch<CbrPlanRecord>> planCases,
      List<AdaptationResult> planAdaptations,
      List<CbrMatch<C>> allScoredCases,
      Map<String, FeatureValue> features) {
    if (experiences.size() < 2) {
      return null;
    }

    if (planCases.size() >= 2) {
      Set<String> distinctCaseTypes =
          planCases.stream().map(CbrMatch::caseType).collect(Collectors.toSet());

      if (distinctCaseTypes.size() == 1) {
        String caseType = config.caseType() != null ? config.caseType() : definition.getName();
        List<AdaptedPlan> adaptedPlans =
            planAdaptations.stream().map(AdaptationResult::adaptedPlan).toList();
        return invokeEnsembleAnalyzer(caseType, planCases, adaptedPlans, features);
      }
    }

    return buildOutcomeOnlyConsensus(experiences, allScoredCases);
  }

  private EnsembleConsensus invokeEnsembleAnalyzer(
      String caseType,
      List<CbrMatch<CbrPlanRecord>> planCases,
      List<AdaptedPlan> adaptedPlans,
      Map<String, FeatureValue> features) {
    try {
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      Future<EnsemblePlan> future =
          executor.submit(
              () -> ensembleAnalyzer.analyze(caseType, planCases, adaptedPlans, features));
      EnsemblePlan plan;
      try {
        plan = future.get(ensembleTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException te) {
        future.cancel(true);
        LOG.warnf(
            "CbrPlanEnsembleAnalyzer.analyze() timed out after %dms"
                + " — proceeding without ensemble",
            ensembleTimeoutMs);
        return null;
      } finally {
        executor.shutdownNow();
      }

      if (plan.inputPlanCount() < 2) {
        return null;
      }

      return mapEnsemblePlan(plan);
    } catch (Exception e) {
      LOG.warnf(e, "CbrPlanEnsembleAnalyzer.analyze() failed — proceeding without ensemble");
      return null;
    }
  }

  private EnsembleConsensus mapEnsemblePlan(EnsemblePlan plan) {
    List<StepConsensusEntry> entries =
        plan.stepAnalysis().stream()
            .map(
                sc ->
                    new StepConsensusEntry(
                        sc.bindingName(),
                        sc.capabilityName(),
                        sc.occurrenceCount(),
                        sc.totalPlans(),
                        sc.workerDistribution(),
                        sc.outcomeDistribution(),
                        sc.priorityDistribution(),
                        sc.contributingCaseIds(),
                        AgreementLevel.valueOf(sc.agreement().name())))
            .toList();
    return new EnsembleConsensus(
        ConsensusScope.STEP_LEVEL,
        entries,
        plan.ensembleConfidence(),
        plan.inputPlanCount(),
        plan.sourceCaseIds());
  }

  private <C extends CbrRecord> EnsembleConsensus buildOutcomeOnlyConsensus(
      List<RetrievedExperience> experiences, List<CbrMatch<C>> scoredCases) {
    double confidence = ExperienceAnalyser.outcomeConsistency(experiences);
    List<String> caseIds =
        scoredCases.stream().map(CbrMatch::caseId).filter(Objects::nonNull).toList();
    return new EnsembleConsensus(
        ConsensusScope.OUTCOME_ONLY, List.of(), confidence, experiences.size(), caseIds);
  }

  private AdaptationResult adaptPlan(
      CbrMatch<CbrPlanRecord> scored, String caseType, Map<String, FeatureValue> features) {
    try {
      AdaptedPlan adapted = planAdapter.adapt(caseType, scored, features);
      List<ExperiencePlanStep> steps =
          adapted.steps().stream()
              .filter(s -> s.action() != AdaptationAction.REMOVED)
              .map(
                  s ->
                      new ExperiencePlanStep(
                          s.bindingName(),
                          s.capabilityName(),
                          s.workerName(),
                          parseOutcome(s.stepOutcome()),
                          s.priority(),
                          s.parameters(),
                          s.action().name(),
                          s.reason()))
              .toList();
      return new AdaptationResult(adapted, steps);
    } catch (Exception e) {
      LOG.warnf(e, "CbrPlanAdapter.adapt() failed — falling back to raw plan trace");
      List<ExperiencePlanStep> fallbackSteps = mapCbrPlanStep(scored.cbrRecord().cbrPlanStep());
      List<AdaptedStep> retainedSteps =
          scored.cbrRecord().cbrPlanStep().stream()
              .map(
                  rs ->
                      new AdaptedStep(
                          rs.bindingName(),
                          rs.capabilityName(),
                          rs.workerName(),
                          rs.stepOutcome(),
                          rs.priority(),
                          rs.parameters(),
                          AdaptationAction.RETAINED,
                          null))
              .toList();
      return new AdaptationResult(new AdaptedPlan(retainedSteps), fallbackSteps);
    }
  }

  synchronized void cacheIfUnderBound(UUID caseId, CbrRetrievalResult result) {
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

  record FeatureExtractionResult(Map<String, FeatureValue> features, boolean complete) {}

  private FeatureExtractionResult extractFeatures(CbrConfig config, CaseContext context) {
    return switch (config.featureExtractor()) {
      case JqFeatureExtractor jq -> extractJqFeatures(jq, context);
      case LambdaFeatureExtractor lambda ->
          new FeatureExtractionResult(FeatureValue.toFeatureMap(lambda.extract(context)), true);
    };
  }

  private FeatureExtractionResult extractJqFeatures(JqFeatureExtractor jq, CaseContext context) {
    JsonNode workingNode = context.layer(ContextLayer.WORKING).asJsonNode();
    Map<String, FeatureValue> features = new LinkedHashMap<>();
    int expectedCount = jq.featureExpressions().size();

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

    return new FeatureExtractionResult(features, features.size() == expectedCount);
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

  private <C extends CbrRecord> List<RetrievedExperience> mapResults(
      List<CbrMatch<C>> scoredCases, Map<String, FeatureValue> features) {
    return scoredCases.stream().map(s -> mapScoredCase(s, features)).toList();
  }

  @SuppressWarnings("unchecked")
  private <C extends CbrRecord> RetrievedExperience mapScoredCase(
      CbrMatch<C> scored, Map<String, FeatureValue> features) {
    CbrRecord c = scored.cbrRecord();
    String resultCaseType = scored.caseType();
    List<ExperiencePlanStep> trace;
    if (c instanceof CbrPlanRecord) {
      trace = adaptAndMapCbrPlanStep((CbrMatch<CbrPlanRecord>) scored, resultCaseType, features);
    } else {
      trace = List.of();
    }
    return buildExperience(c, scored, trace, resultCaseType);
  }

  private List<ExperiencePlanStep> adaptAndMapCbrPlanStep(
      CbrMatch<CbrPlanRecord> scored, String caseType, Map<String, FeatureValue> features) {
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
                      parseOutcome(s.stepOutcome()),
                      s.priority(),
                      s.parameters(),
                      s.action().name(),
                      s.reason()))
          .toList();
    } catch (Exception e) {
      LOG.warnf(e, "CbrPlanAdapter.adapt() failed — falling back to raw plan trace");
      return mapCbrPlanStep(scored.cbrRecord().cbrPlanStep());
    }
  }

  private List<ExperiencePlanStep> mapCbrPlanStep(List<CbrPlanStep> traces) {
    return traces.stream()
        .map(
            t ->
                new ExperiencePlanStep(
                    t.bindingName(),
                    t.capabilityName(),
                    t.workerName(),
                    parseOutcome(t.stepOutcome()),
                    t.priority(),
                    t.parameters()))
        .toList();
  }

  private <C extends CbrRecord> RetrievedExperience buildExperience(
      CbrRecord c, CbrMatch<C> scored, List<ExperiencePlanStep> trace, String resultCaseType) {
    ResolutionSourceType sourceType;
    String documentContent = null;
    List<DocumentStep> documentSteps = null;
    if (c instanceof CbrGuidanceRecord guide) {
      sourceType = ResolutionSourceType.RESOLUTION_GUIDE;
      documentContent = guide.solution();
      documentSteps = mapCbrGuidanceSteps(guide.steps());
    } else {
      sourceType = ResolutionSourceType.PLAN_TRACE;
    }
    return new RetrievedExperience(
        c.problem(),
        c.solution(),
        c.outcome(),
        c.confidence() != null ? c.confidence().value() : null,
        scored.score(),
        new LinkedHashMap<>(c.features()),
        trace,
        scored.featureSimilarities(),
        resultCaseType,
        sourceType,
        documentContent,
        documentSteps);
  }

  private List<DocumentStep> mapCbrGuidanceSteps(List<CbrGuidanceStep> steps) {
    if (steps == null || steps.isEmpty()) return null;
    return steps.stream()
        .map(
            s ->
                new DocumentStep(
                    s.description(), s.preconditions(), s.expectedOutcome(), s.automationHint()))
        .toList();
  }

  private static RoutingOutcome parseOutcome(String raw) {
    if (raw == null) return RoutingOutcome.FAILURE;
    try {
      return RoutingOutcome.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return RoutingOutcome.FAILURE;
    }
  }
}
