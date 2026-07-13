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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.EpisodicMemoryConfig;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CbrRetrievalServiceTest {

  private RecordingCbrStore cbrStore;
  private JQEvaluator jqEvaluator;
  private CbrRetrievalService service;

  @BeforeEach
  void setUp() throws Exception {
    jqEvaluator = new JQEvaluator();
    // JQEvaluator.init() is package-private @PostConstruct — invoke via reflection
    Method init = JQEvaluator.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(jqEvaluator);

    cbrStore = new RecordingCbrStore();
    service = new CbrRetrievalService(jqEvaluator, cbrStore);
  }

  @Test
  void null_cbrConfig_returns_empty() {
    CaseDefinition def = buildDefinition(null);
    CaseInstance instance = buildInstance();
    List<RetrievedExperience> result = service.retrieve(def, instance).await().indefinitely();
    assertTrue(result.isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void empty_features_returns_empty() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of()).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertTrue(result.isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void null_domain_no_episodic_returns_empty() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).build();
    CaseDefinition def = buildDefinition(config);
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertTrue(result.isEmpty());
    assertFalse(cbrStore.wasCalled());
  }

  @Test
  void domain_falls_back_to_episodic() {
    CbrConfig config = CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).build();
    CaseDefinition def = buildDefinition(config);
    def.setEpisodicMemoryConfig(EpisodicMemoryConfig.of("episodic-domain", ".id"));
    cbrStore.setResult(List.of());
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
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
    service.retrieve(def, instance).await().indefinitely();

    CbrQuery query = cbrStore.lastQuery();
    assertEquals("sc2", query.domain().name());
    assertEquals("game", query.caseType());
    assertEquals(3, query.topK());
    assertEquals(0.4, query.minSimilarity());
    assertEquals(0.6, query.vectorWeight());
    assertEquals("aggressive", query.features().get("posture"));
    assertEquals(50, query.features().get("size"));
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
    service.retrieve(def, instance).await().indefinitely();

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
    List<RetrievedExperience> result = service.retrieve(def, instance).await().indefinitely();
    assertTrue(result.isEmpty());
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
    service.retrieve(def, buildInstance()).await().indefinitely();

    assertTrue(cbrStore.wasCalled());
    assertEquals("extracted", cbrStore.lastQuery().features().get("f1"));
  }

  @Test
  void results_mapped_to_retrieved_experience() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    PlanTrace planTrace = new PlanTrace("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    PlanCbrCase cbrCase =
        new PlanCbrCase(
            "problem1",
            "solution1",
            "COMPLETED",
            0.95,
            Map.of("f1", FeatureValue.string("v1")),
            List.of(planTrace));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(cbrCase, 0.87)));

    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();

    assertEquals(1, result.size());
    RetrievedExperience exp = result.get(0);
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

    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertTrue(result.isEmpty());
  }

  @Test
  void caseType_defaults_to_definition_name() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    cbrStore.setResult(List.of());
    service.retrieve(def, buildInstance()).await().indefinitely();
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
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertTrue(result.isEmpty());
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
    io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase fvCase =
        new io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase(
            "problem1", "solution1", "COMPLETED", 0.9, Map.of("f1", FeatureValue.string("v1")));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(fvCase, 0.85)));
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertEquals(1, result.size());
    assertEquals("problem1", result.get(0).problem());
    assertTrue(result.get(0).planTrace().isEmpty());
  }

  @Test
  void retrieve_with_explicit_class_overload() {
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    CaseDefinition def = buildDefinition(config);
    io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase fvCase =
        new io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase(
            "problem1", "solution1", "COMPLETED", 0.8, Map.of("f1", FeatureValue.string("v1")));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(fvCase, 0.75)));
    List<RetrievedExperience> result =
        service
            .retrieve(
                def, buildInstance(), io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase.class)
            .await()
            .indefinitely();
    assertEquals(1, result.size());
    assertTrue(result.get(0).planTrace().isEmpty());
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
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertTrue(result.isEmpty());
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
    PlanTrace pt = new PlanTrace("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    PlanCbrCase planCase =
        new PlanCbrCase(
            "problem1",
            "solution1",
            "COMPLETED",
            0.95,
            Map.of("f1", FeatureValue.string("v1")),
            List.of(pt));
    cbrStore.setResult(List.of(new ScoredCbrCase<>(planCase, 0.9)));
    List<RetrievedExperience> result =
        service.retrieve(def, buildInstance()).await().indefinitely();
    assertEquals(1, result.size());
    assertEquals(1, result.get(0).planTrace().size());
    assertEquals("bind1", result.get(0).planTrace().get(0).bindingName());
  }

  // --- helpers ---

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

  /** Recording stub for CbrCaseMemoryStore — no Mockito. */
  static class RecordingCbrStore implements CbrCaseMemoryStore {
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
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
        CbrQuery query, Class<C> caseType) {
      called = true;
      lastQuery = query;
      if (failure != null) {
        throw failure;
      }
      return (List<ScoredCbrCase<C>>) (List<?>) result;
    }

    @Override
    public void registerSchema(CbrFeatureSchema schema) {}

    @Override
    public String store(CbrCase c, String ct, String eid, MemoryDomain d, String tid, String cid) {
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
  }
}
