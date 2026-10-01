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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.routing.CbrRetrievalResult;
import io.casehub.api.spi.routing.ConsensusScope;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.AdaptationAction;
import io.casehub.neocortex.memory.cbr.AdaptedPlan;
import io.casehub.neocortex.memory.cbr.AdaptedStep;
import io.casehub.neocortex.memory.cbr.CbrGuidanceRecord;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.neocortex.memory.cbr.CbrPlanAdapter;
import io.casehub.neocortex.memory.cbr.CbrPlanEnsembleAnalyzer;
import io.casehub.neocortex.memory.cbr.CbrPlanRecord;
import io.casehub.neocortex.memory.cbr.CbrPlanStep;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.CbrRecordSchema;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.EnsemblePlan;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.StepConsensus;
import io.casehub.neocortex.memory.cbr.TemporalDecay;
// NoOpCbrPlanEnsembleAnalyzer removed in neocortex #376 — inline lambda used instead
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CbrRetrievalServiceTest {

  private RecordingCbrStore cbrStore;
  private JQEvaluator jqEvaluator;
  private CbrRetrievalService service;

  private RecordingCbrPlanAdapter planAdapter;

  @BeforeEach
  void setUp() {
    jqEvaluator = new JQEvaluator(null, null);

    cbrStore = new RecordingCbrStore();
    planAdapter = new RecordingCbrPlanAdapter();
    service =
        new CbrRetrievalService(
            jqEvaluator, cbrStore, planAdapter, (caseType, scored, adapted, features) -> null);
  }

  @Test
  void null_cbrConfig_returns_empty() {
    CaseDefinition def = buildDefinition(null);
    CaseInstance instance = buildInstance();
    CbrRetrievalResult result = service.retrieve(def, instance);
    assertTrue(result.experiences().isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void empty_features_returns_empty() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of()).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(result.experiences().isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void null_domain_no_episodic_returns_empty() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).build();
    CaseDefinition def = buildDefinition(config);
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(result.experiences().isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void domain_falls_back_to_episodic() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).build();
    CaseDefinition def = buildDefinition(config);
    def.setEpisodicMemoryConfig(EpisodicMemoryConfig.of("episodic-domain", ".id"));
    cbrStore.setResult(List.of());
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(cbrStore.wasCalled());
    assertEquals("episodic-domain", cbrStore.lastQuery().domain().name());
  }

  @Test
  void jq_extraction_builds_correct_query() {
    CbrConfig config =
        CbrConfig.builder()
            .feature("posture", ".enemy.posture")
            .feature("size", ".enemy.army_size")
            .weight("posture", 2.0)
            .topK(3)
            .minSimilarity(0.4)
            .domain("sc2")
            .caseType("game")
            .vectorWeight(0.6)
            .build();
    CaseDefinition def = buildDefinition(config);
    CaseInstance instance =
        buildInstanceWithContext(Map.of("enemy", Map.of("posture", "aggressive", "army_size", 50)));
    cbrStore.setResult(List.of());
    service.retrieve(def, instance);

    CbrQuery query = cbrStore.lastQuery();
    assertEquals("sc2", query.domain().name());
    assertEquals("game", query.caseType());
    assertEquals(3, query.topK());
    assertEquals(0.4, query.minSimilarity());
    assertEquals(0.6, query.vectorWeight());
    assertEquals(FeatureValue.string("aggressive"), query.features().get("posture"));
    assertEquals(FeatureValue.number(50), query.features().get("size"));
    assertEquals(2.0, query.weights().get("posture"));
  }

  @Test
  void jq_partial_extraction_proceeds_with_available_features() {
    CbrConfig config =
        CbrConfig.builder()
            .feature("exists", ".enemy.posture")
            .feature("missing", ".enemy.nonexistent")
            .domain("test")
            .build();
    CaseDefinition def = buildDefinition(config);
    CaseInstance instance =
        buildInstanceWithContext(Map.of("enemy", Map.of("posture", "defensive")));
    cbrStore.setResult(List.of());
    service.retrieve(def, instance);

    assertTrue(cbrStore.wasCalled());
    Map<String, FeatureValue> features = cbrStore.lastQuery().features();
    assertEquals(1, features.size());
    assertEquals(FeatureValue.string("defensive"), features.get("exists"));
  }

  @Test
  void jq_all_null_returns_empty() {
    CbrConfig config =
        CbrConfig.builder()
            .feature("a", ".nonexistent1")
            .feature("b", ".nonexistent2")
            .domain("test")
            .build();
    CaseDefinition def = buildDefinition(config);
    CaseInstance instance = buildInstanceWithContext(Map.of());
    CbrRetrievalResult result = service.retrieve(def, instance);
    assertTrue(result.experiences().isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void lambda_extraction_invoked() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "extracted"))
            .domain("test")
            .build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setResult(List.of());
    service.retrieve(def, buildInstance());

    assertTrue(cbrStore.wasCalled());
    assertEquals(FeatureValue.string("extracted"), cbrStore.lastQuery().features().get("f1"));
  }

  @Test
  void results_mapped_to_retrieved_experience() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep planTrace =
        new CbrPlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(), null);
    CbrPlanRecord cbrCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.95, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(planTrace),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(cbrCase, "plan", 0.87)));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().size());
    RetrievedExperience exp = result.experiences().get(0);
    assertEquals("problem1", exp.problem());
    assertEquals("solution1", exp.solution());
    assertEquals("COMPLETED", exp.outcome());
    assertEquals(0.95, exp.confidence());
    assertEquals(0.87, exp.similarityScore());
    assertEquals(1, exp.planTrace().size());
    assertEquals("bind1", exp.planTrace().get(0).bindingName());
  }

  @Test
  void store_failure_returns_empty_list() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setFailure(new RuntimeException("Qdrant timeout"));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(result.experiences().isEmpty());
  }

  @Test
  void caseType_defaults_to_definition_name() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setResult(List.of());
    service.retrieve(def, buildInstance());
    assertEquals("test-case", cbrStore.lastQuery().caseType());
  }

  @Test
  void lambda_extractor_failure_returns_empty() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(
                ctx -> {
                  throw new RuntimeException("extractor NPE");
                })
            .domain("test")
            .build();
    CaseDefinition def = buildDefinition(config);
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(result.experiences().isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void retrieve_with_feature_vector_cbrType() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .cbrType("feature-vector")
            .build();
    CaseDefinition def = buildDefinition(config);
    io.casehub.neocortex.memory.cbr.CbrFeatureRecord fvCase =
        new io.casehub.neocortex.memory.cbr.CbrFeatureRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(fvCase, "feature-vector", 0.85)));
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertEquals(1, result.experiences().size());
    assertEquals("problem1", result.experiences().get(0).problem());
    assertTrue(result.experiences().get(0).planTrace().isEmpty());
  }

  @Test
  void resolution_guide_mapped_with_document_fields() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .cbrType("textual")
            .build();
    CaseDefinition def = buildDefinition(config);
    var guide =
        new CbrGuidanceRecord(
            "phishing runbook", "1. Isolate mailbox 2. Reset credentials", null, null, null, null);
    cbrStore.setResult(List.of(new CbrMatch<>(guide, "textual", 0.82)));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().size());
    RetrievedExperience exp = result.experiences().get(0);
    assertEquals("phishing runbook", exp.problem());
    assertEquals("1. Isolate mailbox 2. Reset credentials", exp.solution());
    assertTrue(exp.planTrace().isEmpty());
    assertEquals("textual", exp.caseType());
  }

  @Test
  void cross_type_retrieval_returns_mixed_source_types() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .crossType(true)
            .build();
    CaseDefinition def = buildDefinition(config);

    var planCase =
        new CbrPlanRecord(
            "plan problem",
            "plan solution",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(new CbrPlanStep("b1", "c1", "w1", "SUCCESS", 0, Map.of(), null)),
            null,
            null);
    var guideCase =
        new CbrGuidanceRecord("guide problem", "guide solution", null, null, null, null);

    @SuppressWarnings("unchecked")
    var mixed =
        (List<CbrMatch<CbrRecord>>)
            (List<?>)
                List.of(
                    new CbrMatch<>(planCase, "plan", 0.9),
                    new CbrMatch<>(guideCase, "textual", 0.8));
    cbrStore.setResult(mixed);

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(2, result.experiences().size());
    var sourceTypes = result.experiences().stream().map(RetrievedExperience::sourceType).toList();
    assertTrue(sourceTypes.contains(io.casehub.api.spi.routing.ResolutionSourceType.PLAN_TRACE));
    assertTrue(
        sourceTypes.contains(io.casehub.api.spi.routing.ResolutionSourceType.RESOLUTION_GUIDE));
  }

  @Test
  void retrieve_with_explicit_class_overload() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    io.casehub.neocortex.memory.cbr.CbrFeatureRecord fvCase =
        new io.casehub.neocortex.memory.cbr.CbrFeatureRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.8, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(fvCase, "feature-vector", 0.75)));
    CbrRetrievalResult result =
        service.retrieve(
            def, buildInstance(), io.casehub.neocortex.memory.cbr.CbrFeatureRecord.class);
    assertEquals(1, result.experiences().size());
    assertTrue(result.experiences().get(0).planTrace().isEmpty());
  }

  @Test
  void unknown_cbrType_returns_empty() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .cbrType("nonexistent")
            .build();
    CaseDefinition def = buildDefinition(config);
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertTrue(result.experiences().isEmpty());
  }

  @Test
  void plan_case_with_explicit_cbrType_maps_plan_trace() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .cbrType("plan")
            .build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep pt = new CbrPlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(), null);
    CbrPlanRecord planCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.95, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(pt),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(planCase, "plan", 0.9)));
    CbrRetrievalResult result = service.retrieve(def, buildInstance());
    assertEquals(1, result.experiences().size());
    assertEquals(1, result.experiences().get(0).planTrace().size());
    assertEquals("bind1", result.experiences().get(0).planTrace().get(0).bindingName());
  }

  @Test
  void temporalDecay_set_on_query_when_configured() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .temporalDecayHalfLifeDays(30)
            .build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setResult(List.of());
    service.retrieve(def, buildInstance());

    CbrQuery query = cbrStore.lastQuery();
    assertNotNull(query.temporalDecay());
    assertInstanceOf(TemporalDecay.HalfLife.class, query.temporalDecay());
    TemporalDecay.HalfLife halfLife = (TemporalDecay.HalfLife) query.temporalDecay();
    assertEquals(Duration.ofDays(30), halfLife.halfLife());
  }

  @Test
  void temporalDecay_null_when_not_configured() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setResult(List.of());
    service.retrieve(def, buildInstance());

    assertNull(cbrStore.lastQuery().temporalDecay());
  }

  // --- helpers ---

  @Test
  void planCbrCase_adaptationInvoked() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep pt = new CbrPlanStep("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of(), null);
    CbrPlanRecord planCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.95, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(pt),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(planCase, "test-case", 0.87)));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertTrue(planAdapter.wasCalled());
    assertEquals("test-case", planAdapter.lastCaseType());
    assertEquals(1, result.experiences().size());
    assertEquals("RETAINED", result.experiences().get(0).planTrace().get(0).adaptationAction());
  }

  @Test
  void planCbrCase_caseType_threaded_from_config() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .caseType("custom-type")
            .build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanRecord planCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(new CbrPlanStep("b1", "c1", "w1", "SUCCESS", 0, Map.of(), null)),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(planCase, "custom-type", 0.8)));

    service.retrieve(def, buildInstance());

    assertEquals("custom-type", planAdapter.lastCaseType());
  }

  @Test
  void planCbrCase_removedSteps_filtered() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanRecord planCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(
                new CbrPlanStep("b1", "c1", "w1", "SUCCESS", 0, Map.of(), null),
                new CbrPlanStep("b2", "c2", "w2", "FAILURE", 0, Map.of(), null)),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(planCase, "plan", 0.8)));

    planAdapter.setResult(
        new AdaptedPlan(
            List.of(
                new AdaptedStep(
                    "b1", "c1", "w1", "SUCCESS", 0, Map.of(), AdaptationAction.RETAINED, null),
                new AdaptedStep(
                    "b2",
                    "c2",
                    "w2",
                    "FAILURE",
                    0,
                    Map.of(),
                    AdaptationAction.REMOVED,
                    "irrelevant to current case"))));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().get(0).planTrace().size());
    assertEquals("b1", result.experiences().get(0).planTrace().get(0).bindingName());
  }

  @Test
  void featureVectorCase_adapterNotCalled() {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .cbrType("feature-vector")
            .build();
    CaseDefinition def = buildDefinition(config);
    io.casehub.neocortex.memory.cbr.CbrFeatureRecord fvCase =
        new io.casehub.neocortex.memory.cbr.CbrFeatureRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(fvCase, "feature-vector", 0.85)));

    service.retrieve(def, buildInstance());

    assertFalse(planAdapter.wasCalled());
  }

  @Test
  void adapterFailure_fallsBackToRawMapping() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep pt = new CbrPlanStep("b1", "c1", "w1", "SUCCESS", 0, Map.of(), null);
    CbrPlanRecord planCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(pt),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(planCase, "plan", 0.8)));

    service =
        new CbrRetrievalService(
            jqEvaluator,
            cbrStore,
            new CbrPlanAdapter() {
              @Override
              public AdaptedPlan adapt(
                  String caseType,
                  CbrMatch<CbrPlanRecord> retrieved,
                  Map<String, FeatureValue> currentFeatures) {
                throw new RuntimeException("adapter explosion");
              }
            },
            (caseType, scored, adapted, features) -> null);

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().size());
    assertEquals("b1", result.experiences().get(0).planTrace().get(0).bindingName());
    assertNull(result.experiences().get(0).planTrace().get(0).adaptationAction());
  }

  @Test
  void planTrace_stepOutcome_convertedToRoutingOutcome() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep planTrace =
        new CbrPlanStep("bind1", "cap1", "worker1", "DECLINED", 0, Map.of(), null);
    CbrPlanRecord cbrCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.95, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(planTrace),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(cbrCase, "plan", 0.87)));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().size());
    assertEquals(
        io.casehub.api.spi.routing.RoutingOutcome.DECLINED,
        result.experiences().get(0).planTrace().get(0).stepOutcome());
  }

  @Test
  void planTrace_unknownOutcome_fallsBackToFailure() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    CbrPlanStep planTrace =
        new CbrPlanStep("bind1", "cap1", "worker1", "UNKNOWN_VALUE", 0, Map.of(), null);
    CbrPlanRecord cbrCase =
        new CbrPlanRecord(
            "problem1",
            "solution1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.95, java.time.Instant.now()),
            Map.of("f1", FeatureValue.string("v1")),
            List.of(planTrace),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(cbrCase, "plan", 0.87)));

    CbrRetrievalResult result = service.retrieve(def, buildInstance());

    assertEquals(1, result.experiences().size());
    assertEquals(
        io.casehub.api.spi.routing.RoutingOutcome.FAILURE,
        result.experiences().get(0).planTrace().get(0).stepOutcome());
  }

  private CaseDefinition buildDefinition(CbrConfig config) {
    CaseDefinition def =
        CaseDefinition.builder().namespace("ns").name("test-case").version("1.0.0").build();
    if (config != null) {
      def.setCbrConfig(config);
    }
    return def;
  }

  private CaseInstance buildInstance() {
    return buildInstanceWithContext(Map.of());
  }

  private CaseInstance buildInstanceWithContext(Map<String, Object> workingData) {
    CaseInstance instance = new CaseInstance();
    instance.tenancyId = "test-tenant";
    instance.setCaseContext(new CaseContextImpl(workingData));
    return instance;
  }

  /** Recording stub for CbrRecordStore — no Mockito. */
  static class RecordingCbrStore implements CbrRecordStore {
    private boolean called;
    private CbrQuery lastQuery;
    private List<?> result;
    private RuntimeException failure;

    void setResult(List<?> result) {
      this.result = result;
    }

    void setFailure(RuntimeException e) {
      this.failure = e;
    }

    boolean wasCalled() {
      return called;
    }

    CbrQuery lastQuery() {
      return lastQuery;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrRecord> List<CbrMatch<C>> retrieveSimilar(
        CbrQuery query, Class<C> caseType) {
      called = true;
      lastQuery = query;
      if (failure != null) {
        throw failure;
      }
      return (List<CbrMatch<C>>) (List<?>) result;
    }

    @Override
    public void registerSchema(CbrRecordSchema schema) {}

    @Override
    public String store(
        CbrRecord c,
        String ct,
        String eid,
        MemoryDomain d,
        String tid,
        String cid,
        io.casehub.platform.api.path.Path scope) {
      return "";
    }

    @Override
    public Integer erase(EraseRequest r) {
      return 0;
    }

    @Override
    public Integer eraseEntity(String eid, String tid) {
      return 0;
    }

    @Override
    public void recordOutcome(
        String caseId, String tenantId, io.casehub.neocortex.memory.cbr.CbrOutcome outcome) {}

    @Override
    public Integer purge(io.casehub.neocortex.memory.cbr.CbrRetentionPolicy policy) {
      return 0;
    }

    @Override
    public boolean supersede(String caseId, String tenantId, String newCaseId, String reason) {
      return true;
    }

    @Override
    public boolean reinstate(String caseId, String tenantId) {
      return true;
    }

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
      return 0;
    }

    @Override
    public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(
        String tenantId, io.casehub.neocortex.memory.MemoryDomain domain) {
      return java.util.List.of();
    }

    @Override
    public java.util.List<String> findCaseIds(
        String caseType,
        io.casehub.neocortex.memory.MemoryDomain domain,
        String tenantId,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
      return java.util.List.of();
    }

    @Override
    public int supersedeMatching(
        String tenantId,
        io.casehub.neocortex.memory.MemoryDomain domain,
        String reason,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters,
        String newCaseId) {
      return 0;
    }

    @Override
    public int supersedeAll(java.util.Collection<String> caseIds, String tenantId, String reason) {
      return 0;
    }

    @Override
    public int reinstateMatching(
        String tenantId,
        io.casehub.neocortex.memory.MemoryDomain domain,
        String reason,
        java.util.Map<String, io.casehub.neocortex.memory.cbr.CbrFilter> filters) {
      return 0;
    }

    @Override
    public int reinstateAll(java.util.Collection<String> caseIds, String tenantId) {
      return 0;
    }

    @Override
    public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(
        String caseId, String tenantId) {
      return null;
    }
  }

  static class RecordingCbrPlanAdapter implements CbrPlanAdapter {
    private boolean called;
    private String lastCaseType;
    private AdaptedPlan result;

    void setResult(AdaptedPlan result) {
      this.result = result;
    }

    boolean wasCalled() {
      return called;
    }

    String lastCaseType() {
      return lastCaseType;
    }

    @Override
    public AdaptedPlan adapt(
        String caseType,
        CbrMatch<CbrPlanRecord> retrieved,
        Map<String, FeatureValue> currentFeatures) {
      called = true;
      lastCaseType = caseType;
      if (result != null) {
        return result;
      }
      return new AdaptedPlan(
          retrieved.cbrRecord().cbrPlanStep().stream()
              .map(
                  t ->
                      new AdaptedStep(
                          t.bindingName(),
                          t.capabilityName(),
                          t.workerName(),
                          t.stepOutcome(),
                          t.priority(),
                          t.parameters(),
                          AdaptationAction.RETAINED,
                          null))
              .toList());
    }
  }

  static class RecordingCbrPlanEnsembleAnalyzer implements CbrPlanEnsembleAnalyzer {
    private boolean called;
    private EnsemblePlan result;

    void setResult(EnsemblePlan result) {
      this.result = result;
    }

    boolean wasCalled() {
      return called;
    }

    @Override
    public EnsemblePlan analyze(
        String caseType,
        List<CbrMatch<CbrPlanRecord>> scoredCases,
        List<AdaptedPlan> adaptedPlans,
        Map<String, FeatureValue> currentFeatures) {
      called = true;
      if (result != null) return result;
      List<StepConsensus> steps =
          List.of(
              new StepConsensus(
                  "triage",
                  "triage",
                  2,
                  2,
                  Map.of("w1", 2),
                  Map.of("SUCCESS", 2),
                  Map.of(1, 2),
                  List.of("c1", "c2"),
                  io.casehub.neocortex.memory.cbr.StepAgreement.UNANIMOUS));
      return new EnsemblePlan(new AdaptedPlan(List.of()), steps, List.of("c1", "c2"), 0.9, 2);
    }
  }

  @Test
  void retrieveForSelectionWithEnsemble_invokes_ensemble_for_plan_type() {
    var analyzer = new RecordingCbrPlanEnsembleAnalyzer();
    service = new CbrRetrievalService(jqEvaluator, cbrStore, planAdapter, analyzer);

    CbrPlanStep step1 = new CbrPlanStep("triage", "triage", "w1", "SUCCESS", 1, Map.of(), null);
    CbrPlanRecord case1 =
        new CbrPlanRecord(
            "p1",
            "s1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("severity", FeatureValue.string("HIGH")),
            List.of(step1),
            null,
            null);
    CbrPlanRecord case2 =
        new CbrPlanRecord(
            "p2",
            "s2",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.8, java.time.Instant.now()),
            Map.of("severity", FeatureValue.string("HIGH")),
            List.of(step1),
            null,
            null);
    cbrStore.setResult(
        List.of(new CbrMatch<>(case1, "TestCase", 0.9), new CbrMatch<>(case2, "TestCase", 0.8)));

    CbrRetrievalResult result =
        service.retrieveForSelectionWithEnsemble(
            "tenant-1",
            "test-domain",
            Map.of("severity", FeatureValue.string("HIGH")),
            5,
            0.0,
            Map.of(),
            "TestCase");

    assertTrue(analyzer.wasCalled());
    assertNotNull(result.ensemble());
    assertEquals(ConsensusScope.STEP_LEVEL, result.ensemble().scope());
    assertEquals(2, result.experiences().size());
  }

  @Test
  void retrieveForSelectionWithEnsemble_null_caseType_returns_outcome_only() {
    service =
        new CbrRetrievalService(
            jqEvaluator, cbrStore, planAdapter, (caseType, scored, adapted, features) -> null);

    CbrPlanStep step1 = new CbrPlanStep("triage", "triage", "w1", "SUCCESS", 1, Map.of(), null);
    CbrPlanRecord case1 =
        new CbrPlanRecord(
            "p1",
            "s1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("severity", FeatureValue.string("HIGH")),
            List.of(step1),
            null,
            null);
    CbrPlanRecord case2 =
        new CbrPlanRecord(
            "p2",
            "s2",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.8, java.time.Instant.now()),
            Map.of("severity", FeatureValue.string("HIGH")),
            List.of(step1),
            null,
            null);
    cbrStore.setResult(
        List.of(new CbrMatch<>(case1, "TestCase", 0.9), new CbrMatch<>(case2, "TestCase", 0.8)));

    CbrRetrievalResult result =
        service.retrieveForSelectionWithEnsemble(
            "tenant-1",
            "test-domain",
            Map.of("severity", FeatureValue.string("HIGH")),
            5,
            0.0,
            Map.of(),
            null);

    assertNotNull(result.ensemble());
    assertEquals(ConsensusScope.OUTCOME_ONLY, result.ensemble().scope());
    assertEquals(2, result.experiences().size());
  }

  @Test
  void retrieveForSelectionWithEnsemble_single_result_returns_null_ensemble() {
    service =
        new CbrRetrievalService(
            jqEvaluator, cbrStore, planAdapter, (caseType, scored, adapted, features) -> null);

    CbrPlanStep step1 = new CbrPlanStep("triage", "triage", "w1", "SUCCESS", 1, Map.of(), null);
    CbrPlanRecord case1 =
        new CbrPlanRecord(
            "p1",
            "s1",
            "COMPLETED",
            io.casehub.neocortex.cognitive.Confidence.inferred(0.9, java.time.Instant.now()),
            Map.of("severity", FeatureValue.string("HIGH")),
            List.of(step1),
            null,
            null);
    cbrStore.setResult(List.of(new CbrMatch<>(case1, "TestCase", 0.9)));

    CbrRetrievalResult result =
        service.retrieveForSelectionWithEnsemble(
            "tenant-1",
            "test-domain",
            Map.of("severity", FeatureValue.string("HIGH")),
            5,
            0.0,
            Map.of(),
            "TestCase");

    assertNull(result.ensemble());
    assertEquals(1, result.experiences().size());
  }
}
