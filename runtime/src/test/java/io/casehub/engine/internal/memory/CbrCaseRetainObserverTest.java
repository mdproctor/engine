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
package io.casehub.engine.internal.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Binding;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.engine.common.internal.jq.JQEvaluator;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.internal.model.PlanItemSaveRequest;
import io.casehub.engine.common.internal.model.TargetType;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.worker.api.Capability;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CbrCaseRetainObserverTest {

  private RecordingCbrStore store;
  private StubRegistry registry;
  private StubPlanItemStore planItemStore;
  private JQEvaluator jqEvaluator;
  private CbrCaseRetainObserver observer;

  @BeforeEach
  void setUp() {
    store = new RecordingCbrStore();
    registry = new StubRegistry();
    planItemStore = new StubPlanItemStore();
    jqEvaluator = new JQEvaluator();
    @SuppressWarnings("unchecked")
    Instance<PlanItemStore> planItemStoreInstance = mock(Instance.class);
    when(planItemStoreInstance.isUnsatisfied()).thenReturn(false);
    when(planItemStoreInstance.get()).thenReturn(planItemStore);
    observer = new CbrCaseRetainObserver(store, registry, planItemStoreInstance, jqEvaluator);
  }

  @Test
  void stores_plan_cbr_case_on_completed_case() {
    registry.register(
        defWithJqCbr(
            "test-case",
            "test-domain",
            Map.of("amount", ".transaction.amount"),
            capBinding("assess-risk", "risk-assessment")));
    planItemStore.items = List.of(planItem("assess-risk", "worker-1", TaskStatus.COMPLETED));

    observer.onOutcome(
        event("test-case", "COMPLETED", Map.of("transaction", Map.of("amount", 50000))));

    assertThat(store.storedCases).hasSize(1);
    PlanCbrCase stored = store.storedCases.get(0);
    assertThat(stored.problem()).isEqualTo("test-case");
    assertThat(stored.outcome()).isEqualTo("COMPLETED");
    assertThat(stored.features()).containsEntry("amount", FeatureValue.number(50000));
    assertThat(stored.planTrace()).hasSize(1);
    assertThat(stored.planTrace().get(0).bindingName()).isEqualTo("assess-risk");
    assertThat(stored.planTrace().get(0).capabilityName()).isEqualTo("risk-assessment");
    assertThat(stored.planTrace().get(0).workerName()).isEqualTo("worker-1");
    assertThat(stored.planTrace().get(0).stepOutcome()).isEqualTo("SUCCESS");
  }

  @Test
  void stores_with_correct_store_parameters() {
    UUID caseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    registry.register(
        defWithJqCbr("my-case", "my-domain", Map.of("k", ".k"), capBinding("b1", "cap1")));
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.COMPLETED));

    observer.onOutcome(event("my-case", "COMPLETED", Map.of("k", "v"), "tenant-42", caseId));

    assertThat(store.lastCaseType).isEqualTo("my-case");
    assertThat(store.lastEntityId).isEqualTo("case-retain");
    assertThat(store.lastDomain.name()).isEqualTo("my-domain");
    assertThat(store.lastTenantId).isEqualTo("tenant-42");
    assertThat(store.lastCaseId).isEqualTo("00000000-0000-0000-0000-000000000001");
  }

  @Test
  void no_store_call_when_definition_not_found() {
    observer.onOutcome(event("unknown-case", "COMPLETED", Map.of()));
    assertThat(store.storedCases).isEmpty();
  }

  @Test
  void no_store_call_when_no_cbr_config() {
    registry.register(
        CaseDefinition.builder().name("no-cbr").namespace("test").version("1.0.0").build());

    observer.onOutcome(event("no-cbr", "COMPLETED", Map.of()));
    assertThat(store.storedCases).isEmpty();
  }

  @Test
  void no_store_call_when_domain_unresolvable() {
    registry.register(defWithJqCbr("d-case", null, Map.of("k", ".k"), capBinding("b1", "cap1")));

    observer.onOutcome(event("d-case", "COMPLETED", Map.of("k", "v")));
    assertThat(store.storedCases).isEmpty();
  }

  @Test
  void no_store_call_when_features_empty() {
    registry.register(
        defWithJqCbr("e-case", "dom", Map.of("k", ".nonexistent"), capBinding("b1", "cap1")));
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.COMPLETED));

    observer.onOutcome(event("e-case", "COMPLETED", Map.of("other", "val")));
    assertThat(store.storedCases).isEmpty();
  }

  @Test
  void no_store_call_when_filtered_trace_empty() {
    registry.register(defWithJqCbr("t-case", "dom", Map.of("k", ".k"), capBinding("b1", "cap1")));
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.PENDING));

    observer.onOutcome(event("t-case", "COMPLETED", Map.of("k", "v")));
    assertThat(store.storedCases).isEmpty();
  }

  @Test
  void includes_humanTask_bindings_in_plan_trace() {
    registry.register(
        defWithJqCbr(
            "f-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("cap-bind", "cap1"),
            Binding.builder()
                .name("ht-bind")
                .target(HumanTaskTarget.inline().title("Review task").build())
                .on(new io.casehub.api.model.ContextChangeTrigger("true"))
                .build()));
    planItemStore.items =
        List.of(
            planItem("cap-bind", "w1", TaskStatus.COMPLETED),
            planItem("ht-bind", "w2", TaskStatus.COMPLETED));

    observer.onOutcome(event("f-case", "COMPLETED", Map.of("k", "v")));

    assertThat(store.storedCases).hasSize(1);
    assertThat(store.storedCases.get(0).planTrace()).hasSize(2);
    assertThat(store.storedCases.get(0).planTrace().get(0).bindingName()).isEqualTo("cap-bind");
    assertThat(store.storedCases.get(0).planTrace().get(0).capabilityName()).isEqualTo("cap1");
    assertThat(store.storedCases.get(0).planTrace().get(1).bindingName()).isEqualTo("ht-bind");
    assertThat(store.storedCases.get(0).planTrace().get(1).capabilityName()).isNull();
  }

  @Test
  void filters_non_terminal_plan_items() {
    registry.register(
        defWithJqCbr(
            "nt-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("b1", "cap1"),
            capBinding("b2", "cap2")));
    planItemStore.items =
        List.of(
            planItem("b1", "w1", TaskStatus.COMPLETED), planItem("b2", "w2", TaskStatus.RUNNING));

    observer.onOutcome(event("nt-case", "COMPLETED", Map.of("k", "v")));

    assertThat(store.storedCases.get(0).planTrace()).hasSize(1);
    assertThat(store.storedCases.get(0).planTrace().get(0).bindingName()).isEqualTo("b1");
  }

  @Test
  void filters_null_executor_name() {
    registry.register(
        defWithJqCbr(
            "ne-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("b1", "cap1"),
            capBinding("b2", "cap2")));
    planItemStore.items =
        List.of(
            planItem("b1", "w1", TaskStatus.COMPLETED), planItem("b2", null, TaskStatus.CANCELLED));

    observer.onOutcome(event("ne-case", "CANCELLED", Map.of("k", "v")));

    assertThat(store.storedCases.get(0).planTrace()).hasSize(1);
  }

  @Test
  void maps_task_status_to_outcome_strings() {
    registry.register(
        defWithJqCbr(
            "os-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("b1", "c1"),
            capBinding("b2", "c2"),
            capBinding("b3", "c3"),
            capBinding("b4", "c4"),
            capBinding("b5", "c5")));
    planItemStore.items =
        List.of(
            planItem("b1", "w1", TaskStatus.COMPLETED),
            planItem("b2", "w2", TaskStatus.FAULTED),
            planItem("b3", "w3", TaskStatus.REJECTED),
            planItem("b4", "w4", TaskStatus.CANCELLED),
            planItem("b5", "w5", TaskStatus.OBSOLETE));

    observer.onOutcome(event("os-case", "FAULTED", Map.of("k", "v")));

    var traces = store.storedCases.get(0).planTrace();
    assertThat(traces)
        .extracting("stepOutcome")
        .containsExactly("SUCCESS", "FAILURE", "DECLINED", "CANCELLED", "OBSOLETE");
  }

  @Test
  void solution_synthesis() {
    registry.register(
        defWithJqCbr(
            "sol-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("assess", "cap1"),
            capBinding("review", "cap2")));
    planItemStore.items =
        List.of(
            planItem("assess", "agent-1", TaskStatus.COMPLETED),
            planItem("review", "agent-2", TaskStatus.FAULTED));

    observer.onOutcome(event("sol-case", "FAULTED", Map.of("k", "v")));

    assertThat(store.storedCases.get(0).solution())
        .isEqualTo("assess→agent-1(SUCCESS), review→agent-2(FAILURE)");
  }

  @Test
  void plan_trace_priorities_reflect_creation_order() {
    registry.register(
        defWithJqCbr(
            "pri-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("first", "cap1"),
            capBinding("second", "cap2"),
            capBinding("third", "cap3")));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    planItemStore.items =
        List.of(
            planItemAt("third", "w3", TaskStatus.COMPLETED, t0.plusSeconds(20)),
            planItemAt("first", "w1", TaskStatus.COMPLETED, t0),
            planItemAt("second", "w2", TaskStatus.COMPLETED, t0.plusSeconds(10)));

    observer.onOutcome(event("pri-case", "COMPLETED", Map.of("k", "v")));

    var traces = store.storedCases.get(0).planTrace();
    assertThat(traces).hasSize(3);
    assertThat(traces.get(0).bindingName()).isEqualTo("first");
    assertThat(traces.get(0).priority()).isEqualTo(0);
    assertThat(traces.get(1).bindingName()).isEqualTo("second");
    assertThat(traces.get(1).priority()).isEqualTo(1);
    assertThat(traces.get(2).bindingName()).isEqualTo("third");
    assertThat(traces.get(2).priority()).isEqualTo(2);
  }

  @Test
  void single_plan_item_gets_priority_zero() {
    registry.register(
        defWithJqCbr("single-case", "dom", Map.of("k", ".k"), capBinding("only", "cap1")));
    planItemStore.items =
        List.of(
            planItemAt("only", "w1", TaskStatus.COMPLETED, Instant.parse("2026-01-01T00:00:00Z")));

    observer.onOutcome(event("single-case", "COMPLETED", Map.of("k", "v")));

    var traces = store.storedCases.get(0).planTrace();
    assertThat(traces).hasSize(1);
    assertThat(traces.get(0).priority()).isEqualTo(0);
  }

  @Test
  void priorities_assigned_only_to_filtered_items() {
    registry.register(
        defWithJqCbr(
            "gap-case",
            "dom",
            Map.of("k", ".k"),
            capBinding("first", "cap1"),
            capBinding("second", "cap2")));

    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    planItemStore.items =
        List.of(
            planItemAt("first", "w1", TaskStatus.COMPLETED, t0),
            planItemAt("second", null, TaskStatus.COMPLETED, t0.plusSeconds(10)),
            planItemAt("first", "w3", TaskStatus.COMPLETED, t0.plusSeconds(20)));

    observer.onOutcome(event("gap-case", "COMPLETED", Map.of("k", "v")));

    var traces = store.storedCases.get(0).planTrace();
    assertThat(traces).hasSize(2);
    assertThat(traces.get(0).priority()).isEqualTo(0);
    assertThat(traces.get(1).priority()).isEqualTo(1);
  }

  @Test
  void lambda_feature_extraction() {
    var config =
        CbrConfig.builder()
            .featureExtractor(ctx -> Map.of("extracted", ctx.get("raw")))
            .domain("dom")
            .build();
    var def =
        CaseDefinition.builder()
            .name("lambda-case")
            .namespace("test")
            .version("1.0.0")
            .cbrConfig(config)
            .bindings(capBinding("b1", "cap1"))
            .build();
    registry.register(def);
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.COMPLETED));

    observer.onOutcome(event("lambda-case", "COMPLETED", Map.of("raw", "data")));

    assertThat(store.storedCases.get(0).features())
        .containsEntry("extracted", FeatureValue.string("data"));
  }

  @Test
  void domain_falls_back_to_episodic_memory_config() {
    var config = CbrConfig.builder().feature("k", ".k").build();
    var def =
        CaseDefinition.builder()
            .name("fb-case")
            .namespace("test")
            .version("1.0.0")
            .cbrConfig(config)
            .episodicMemory("fallback-domain", "entity-1")
            .bindings(capBinding("b1", "cap1"))
            .build();
    registry.register(def);
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.COMPLETED));

    observer.onOutcome(event("fb-case", "COMPLETED", Map.of("k", "v")));

    assertThat(store.lastDomain.name()).isEqualTo("fallback-domain");
  }

  @Test
  void store_exception_does_not_propagate() {
    registry.register(defWithJqCbr("err-case", "dom", Map.of("k", ".k"), capBinding("b1", "cap1")));
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.COMPLETED));
    store.throwOnStore = new RuntimeException("store down");

    assertThatCode(() -> observer.onOutcome(event("err-case", "COMPLETED", Map.of("k", "v"))))
        .doesNotThrowAnyException();
  }

  @Test
  void faulted_case_stores_outcome() {
    registry.register(
        defWithJqCbr("fault-case", "dom", Map.of("k", ".k"), capBinding("b1", "cap1")));
    planItemStore.items = List.of(planItem("b1", "w1", TaskStatus.FAULTED));

    observer.onOutcome(event("fault-case", "FAULTED", Map.of("k", "v")));

    assertThat(store.storedCases).hasSize(1);
    assertThat(store.storedCases.get(0).outcome()).isEqualTo("FAULTED");
  }

  // --- Helpers ---

  private CaseOutcomeEvent event(String caseType, String outcome, Map<String, Object> snapshot) {
    return event(caseType, outcome, snapshot, "test-tenant", UUID.randomUUID());
  }

  private CaseOutcomeEvent event(
      String caseType,
      String outcome,
      Map<String, Object> snapshot,
      String tenancyId,
      UUID caseId) {
    return new CaseOutcomeEvent(
        caseType, tenancyId, caseId, snapshot, outcome, Instant.now(), Map.of());
  }

  private CaseDefinition defWithJqCbr(
      String name, String domain, Map<String, String> features, Binding... bindings) {
    var configBuilder = CbrConfig.builder();
    features.forEach(configBuilder::feature);
    if (domain != null) {
      configBuilder.domain(domain);
    }
    return CaseDefinition.builder()
        .name(name)
        .namespace("test")
        .version("1.0.0")
        .cbrConfig(configBuilder.build())
        .bindings(bindings)
        .build();
  }

  private Binding capBinding(String bindingName, String capabilityName) {
    return Binding.builder()
        .name(bindingName)
        .target(new CapabilityTarget(Capability.of(capabilityName, "{}", "{}")))
        .on(new io.casehub.api.model.ContextChangeTrigger("true"))
        .build();
  }

  private PlanItemRecord planItem(String bindingName, String executorName, TaskStatus status) {
    return new PlanItemRecord(
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        bindingName,
        status,
        Instant.now(),
        TargetType.CAPABILITY,
        null,
        "test-tenant",
        bindingName + " description",
        executorName,
        null);
  }

  private PlanItemRecord planItemAt(
      String bindingName, String executorName, TaskStatus status, Instant createdAt) {
    return new PlanItemRecord(
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        bindingName,
        status,
        createdAt,
        TargetType.CAPABILITY,
        null,
        "test-tenant",
        bindingName + " description",
        executorName,
        null);
  }

  // --- Stubs ---

  static class StubRegistry implements CaseDefinitionRegistry {
    private final Map<String, CaseDefinition> defs = new HashMap<>();

    void register(CaseDefinition def) {
      defs.put(def.getName(), def);
    }

    @Override
    public Optional<CaseDefinition> findByName(String name) {
      return Optional.ofNullable(defs.get(name));
    }

    @Override
    public Uni<io.casehub.engine.common.internal.model.CaseMetaModel> registerCaseDefinition(
        CaseDefinition model) {
      return Uni.createFrom().nullItem();
    }

    @Override
    public CaseDefinition getCaseDefinition(
        io.casehub.engine.common.internal.model.CaseMetaModel definition) {
      return null;
    }

    @Override
    public io.casehub.engine.common.internal.model.CaseMetaModel getCaseMetaModel(
        CaseDefinition caseDefinition) {
      return null;
    }
  }

  static class StubPlanItemStore implements PlanItemStore {
    List<PlanItemRecord> items = List.of();

    @Override
    public List<PlanItemRecord> findByCaseId(UUID caseId, String tenancyId) {
      return items;
    }

    @Override
    public void save(PlanItemSaveRequest r, String t) {}

    @Override
    public void updateStatus(String id, TaskStatus s) {}

    @Override
    public List<PlanItemRecord> findDelegatedCrossTenant(UUID id) {
      return List.of();
    }

    @Override
    public List<PlanItemRecord> findAllDelegated() {
      return List.of();
    }
  }

  static class RecordingCbrStore implements CbrCaseMemoryStore {
    final List<PlanCbrCase> storedCases = new ArrayList<>();
    String lastCaseType, lastEntityId, lastTenantId, lastCaseId;
    MemoryDomain lastDomain;
    RuntimeException throwOnStore;

    @Override
    public String store(
        CbrCase c,
        String ct,
        String eid,
        MemoryDomain d,
        String tid,
        String cid,
        io.casehub.platform.api.path.Path scope) {
      if (throwOnStore != null) {
        throw throwOnStore;
      }
      storedCases.add((PlanCbrCase) c);
      lastCaseType = ct;
      lastEntityId = eid;
      lastDomain = d;
      lastTenantId = tid;
      lastCaseId = cid;
      return "stored-id";
    }

    @Override
    public void registerSchema(CbrFeatureSchema s) {}

    @Override
    public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(CbrQuery q, Class<C> t) {
      return List.of();
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
    public void supersede(String caseId, String tenantId, String newCaseId, String reason) {}

    @Override
    public void reinstate(String caseId, String tenantId) {}

    @Override
    public Integer eraseByScope(io.casehub.platform.api.path.Path scope, String tenantId) {
      return 0;
    }

    @Override
    public List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(
        String tenantId, MemoryDomain domain) {
      return List.of();
    }

    @Override
    public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(
        String caseId, String tenantId) {
      return null;
    }
  }
}
