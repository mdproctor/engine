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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for CBR retrieval caching with {@link CbrRetrievalTiming#CASE_LIFETIME}. Verifies cache
 * hit, eviction on terminal state, MAX_CACHE_SIZE bound, and List immutability.
 */
class CbrRetrievalCachingTest {

  private CountingCbrStore cbrStore;
  private CbrRetrievalService service;
  private CbrCacheEvictionHandler evictionHandler;

  @BeforeEach
  void setUp() throws Exception {
    JQEvaluator jqEvaluator = new JQEvaluator();
    Method init = JQEvaluator.class.getDeclaredMethod("init");
    init.setAccessible(true);
    init.invoke(jqEvaluator);

    cbrStore = new CountingCbrStore();
    service = new CbrRetrievalService(jqEvaluator, cbrStore);
    evictionHandler = new CbrCacheEvictionHandler(service);
  }

  @Test
  void caseLifetime_caches_on_first_access() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    List<RetrievedExperience> first = service.retrieve(def, instance).await().indefinitely();
    List<RetrievedExperience> second = service.retrieve(def, instance).await().indefinitely();

    assertEquals(1, cbrStore.callCount(), "store should be called only once");
    assertSame(first, second, "second call should return the cached instance");
    assertEquals(1, first.size());
  }

  @Test
  void perEvaluation_does_not_cache() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.PER_EVALUATION);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    service.retrieve(def, instance).await().indefinitely();
    service.retrieve(def, instance).await().indefinitely();

    assertEquals(2, cbrStore.callCount(), "PER_EVALUATION should call store every time");
  }

  @Test
  void default_timing_is_perEvaluation() {
    // Builder without explicit timing() should default to PER_EVALUATION
    CbrConfig config =
        CbrConfig.builder().featureExtractor(ctx -> Map.of("f1", "v1")).domain("test").build();
    assertEquals(CbrRetrievalTiming.PER_EVALUATION, config.timing());
  }

  @Test
  void eviction_on_terminal_state_clears_cache() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    // Populate cache
    service.retrieve(def, instance).await().indefinitely();
    assertEquals(1, service.cacheSize());

    // Evict via terminal status event
    CaseStatusChanged event =
        new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.COMPLETED.name());
    evictionHandler.onCaseStatusChanged(event).await().indefinitely();

    assertEquals(0, service.cacheSize(), "cache should be empty after eviction");

    // Next retrieval should hit the store again
    service.retrieve(def, instance).await().indefinitely();
    assertEquals(2, cbrStore.callCount(), "store should be called again after eviction");
  }

  @Test
  void eviction_on_faulted_clears_cache() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    service.retrieve(def, instance).await().indefinitely();
    CaseStatusChanged event =
        new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.FAULTED.name());
    evictionHandler.onCaseStatusChanged(event).await().indefinitely();

    assertEquals(0, service.cacheSize());
  }

  @Test
  void eviction_on_cancelled_clears_cache() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    service.retrieve(def, instance).await().indefinitely();
    CaseStatusChanged event =
        new CaseStatusChanged(instance, CaseStatus.RUNNING.name(), CaseStatus.CANCELLED.name());
    evictionHandler.onCaseStatusChanged(event).await().indefinitely();

    assertEquals(0, service.cacheSize());
  }

  @Test
  void non_terminal_status_does_not_evict() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    service.retrieve(def, instance).await().indefinitely();
    CaseStatusChanged event =
        new CaseStatusChanged(instance, CaseStatus.STARTING.name(), CaseStatus.RUNNING.name());
    evictionHandler.onCaseStatusChanged(event).await().indefinitely();

    assertEquals(1, service.cacheSize(), "non-terminal status should not evict");
    assertEquals(1, cbrStore.callCount());
  }

  @Test
  void max_cache_size_bound() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    // Fill cache to MAX_CACHE_SIZE
    for (int i = 0; i < CbrRetrievalService.MAX_CACHE_SIZE; i++) {
      CaseInstance instance = buildInstance();
      service.retrieve(def, instance).await().indefinitely();
    }
    assertEquals(CbrRetrievalService.MAX_CACHE_SIZE, service.cacheSize());

    // One more should not grow the cache
    int storeCallsBefore = cbrStore.callCount();
    CaseInstance overflow = buildInstance();
    service.retrieve(def, overflow).await().indefinitely();

    assertEquals(
        CbrRetrievalService.MAX_CACHE_SIZE,
        service.cacheSize(),
        "cache should not exceed MAX_CACHE_SIZE");
    assertEquals(
        storeCallsBefore + 1, cbrStore.callCount(), "overflow case should still hit the store");
  }

  @Test
  void cached_list_is_immutable() {
    CaseDefinition def = buildDefinition(CbrRetrievalTiming.CASE_LIFETIME);
    CaseInstance instance = buildInstance();
    cbrStore.setResult(List.of(scoredCase("problem1", "solution1")));

    List<RetrievedExperience> result = service.retrieve(def, instance).await().indefinitely();

    assertThrows(
        UnsupportedOperationException.class,
        () -> result.add(null),
        "cached list should be immutable (List.copyOf)");
  }

  // --- helpers ---

  private CaseDefinition buildDefinition(CbrRetrievalTiming timing) {
    CbrConfig config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("f1", "v1"))
            .domain("test")
            .timing(timing)
            .build();
    CaseDefinition def =
        CaseDefinition.builder().namespace("ns").name("test-case").version("1.0.0").build();
    def.setCbrConfig(config);
    return def;
  }

  private CaseInstance buildInstance() {
    CaseInstance instance = new CaseInstance();
    instance.setUuid(UUID.randomUUID());
    instance.tenancyId = "test-tenant";
    instance.setCaseContext(new CaseContextImpl(Map.of()));
    return instance;
  }

  private ScoredCbrCase<PlanCbrCase> scoredCase(String problem, String solution) {
    PlanTrace trace = new PlanTrace("bind1", "cap1", "worker1", "SUCCESS", 0, Map.of());
    PlanCbrCase cbrCase =
        new PlanCbrCase(
            problem,
            solution,
            "COMPLETED",
            0.95,
            Map.of("f1", FeatureValue.string("v1")),
            List.of(trace));
    return new ScoredCbrCase<>(cbrCase, 0.87);
  }

  /** Counting stub for CbrCaseMemoryStore — tracks invocation count. */
  static class CountingCbrStore implements CbrCaseMemoryStore {
    private int callCount;
    private List<ScoredCbrCase<PlanCbrCase>> result = List.of();

    void setResult(List<ScoredCbrCase<PlanCbrCase>> result) {
      this.result = result;
    }

    int callCount() {
      return callCount;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
        CbrQuery query, Class<C> caseType) {
      callCount++;
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

    @Override
    public void recordOutcome(
        String caseId, String tenantId, io.casehub.neocortex.memory.cbr.CbrOutcome outcome) {}
  }
}
