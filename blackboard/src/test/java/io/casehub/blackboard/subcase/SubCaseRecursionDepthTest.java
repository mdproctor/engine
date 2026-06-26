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
package io.casehub.blackboard.subcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.context.PropagationContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.SubCase;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.SubCaseScheduleEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.casehub.engine.common.internal.model.SubCaseGroup;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.SubCaseGroupRepository;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
import io.casehub.engine.internal.work.PendingWorkRegistry;
import io.smallrye.mutiny.Uni;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubCaseRecursionDepthTest {

  private static final String NS = "test";
  private static final String NAME = "recursive-case";
  private static final String VERSION = "1.0.0";

  private BlackboardRegistry registry;
  private CaseHubRuntime caseHubRuntime;
  private CaseInstanceCache caseInstanceCache;
  private SubCaseExecutionHandler handler;

  @BeforeEach
  void setUp() {
    registry = new BlackboardRegistry();
    caseHubRuntime = mock(CaseHubRuntime.class);
    CaseDefinitionRegistry definitionRegistry = mock(CaseDefinitionRegistry.class);
    CaseInstanceRepository instanceRepository = mock(CaseInstanceRepository.class);
    EventLogRepository eventLogRepository = mock(EventLogRepository.class);
    PendingWorkRegistry pendingWorkRegistry = mock(PendingWorkRegistry.class);
    SubCaseGroupRepository subCaseGroupRepository = mock(SubCaseGroupRepository.class);
    caseInstanceCache = new CaseInstanceCacheImpl();

    when(eventLogRepository.append(any(), any())).thenReturn(Uni.createFrom().voidItem());
    when(definitionRegistry.getCaseDefinition(any()))
        .thenReturn(mock(io.casehub.api.model.CaseDefinition.class));
    when(instanceRepository.updateStateAndAppendEvent(any(), any(), any()))
        .thenReturn(Uni.createFrom().nullItem());

    SubCaseGroup stubGroup = mock(SubCaseGroup.class);
    when(subCaseGroupRepository.getOrCreate(any(), any(), anyInt(), anyInt(), any(), any()))
        .thenReturn(Uni.createFrom().item(stubGroup));
    when(subCaseGroupRepository.registerChild(any(), any(), any(), any()))
        .thenReturn(Uni.createFrom().item(stubGroup));

    handler =
        new SubCaseExecutionHandler(
            caseHubRuntime,
            definitionRegistry,
            instanceRepository,
            eventLogRepository,
            pendingWorkRegistry,
            subCaseGroupRepository,
            registry,
            caseInstanceCache);
  }

  private CaseInstance buildInstance(
      UUID id, UUID parentCaseId, String ns, String name, String ver) {
    CaseMetaModel meta = new CaseMetaModel();
    meta.setNamespace(ns);
    meta.setName(name);
    meta.setVersion(ver);

    CaseInstance instance = new CaseInstance();
    instance.setUuid(id);
    instance.setParentCaseId(parentCaseId);
    instance.setCaseMetaModel(meta);
    instance.setState(CaseStatus.RUNNING);
    instance.setPropagationContext(PropagationContext.createRoot());
    instance.tenancyId = "test-tenant";
    return instance;
  }

  private SubCaseScheduleEvent selfReferenceEvent(CaseInstance parent, int maxRecursionDepth) {
    SubCase subCase =
        SubCase.builder()
            .namespace(NS)
            .name(NAME)
            .version(VERSION)
            .maxRecursionDepth(maxRecursionDepth)
            .build();
    return new SubCaseScheduleEvent(parent, subCase, Map.of(), "spawn-self");
  }

  @Test
  void depth_zero_preserves_hard_block() {
    UUID rootId = UUID.randomUUID();
    CaseInstance root = buildInstance(rootId, null, NS, NAME, VERSION);
    caseInstanceCache.put(root);
    registry.getOrCreate(rootId, "test-tenant");
    PlanItem item = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(rootId).orElseThrow()).addPlanItem(item);

    handler.onSubCaseSchedule(selfReferenceEvent(root, 0)).await().indefinitely();

    assertThat(item.getStatus())
        .as("maxRecursionDepth=0 must fault on self-reference (preserves current behavior)")
        .isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void depth_n_allows_n_levels_of_self_reference() {
    int maxDepth = 3;

    // Build chain: root → L1 → L2 (spawning parent)
    UUID rootId = UUID.randomUUID();
    CaseInstance root = buildInstance(rootId, null, NS, NAME, VERSION);
    caseInstanceCache.put(root);

    UUID l1Id = UUID.randomUUID();
    CaseInstance l1 = buildInstance(l1Id, rootId, NS, NAME, VERSION);
    caseInstanceCache.put(l1);

    UUID l2Id = UUID.randomUUID();
    CaseInstance l2 = buildInstance(l2Id, l1Id, NS, NAME, VERSION);
    caseInstanceCache.put(l2);

    // L2 has depth=2 same-def ancestors (root, L1). 2 < 3 → spawn should succeed.
    UUID l3Id = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(l3Id));

    registry.getOrCreate(l2Id, "test-tenant");
    PlanItem item = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(l2Id).orElseThrow()).addPlanItem(item);

    handler.onSubCaseSchedule(selfReferenceEvent(l2, maxDepth)).await().indefinitely();

    assertThat(item.getStatus())
        .as("depth 2 < maxRecursionDepth 3 — spawn must succeed")
        .isEqualTo(PlanItemStatus.DELEGATED);
  }

  @Test
  void nth_plus_one_self_referencing_spawn_faults() {
    int maxDepth = 2;

    // Build chain: root → L1 → L2 (spawning parent)
    UUID rootId = UUID.randomUUID();
    CaseInstance root = buildInstance(rootId, null, NS, NAME, VERSION);
    caseInstanceCache.put(root);

    UUID l1Id = UUID.randomUUID();
    CaseInstance l1 = buildInstance(l1Id, rootId, NS, NAME, VERSION);
    caseInstanceCache.put(l1);

    UUID l2Id = UUID.randomUUID();
    CaseInstance l2 = buildInstance(l2Id, l1Id, NS, NAME, VERSION);
    caseInstanceCache.put(l2);

    // L2 has depth=2 (root + L1). 2 >= 2 → fault.
    registry.getOrCreate(l2Id, "test-tenant");
    PlanItem item = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(l2Id).orElseThrow()).addPlanItem(item);

    handler.onSubCaseSchedule(selfReferenceEvent(l2, maxDepth)).await().indefinitely();

    assertThat(item.getStatus())
        .as("depth 2 >= maxRecursionDepth 2 — must fault")
        .isEqualTo(PlanItemStatus.FAULTED);
  }

  @Test
  void non_self_referencing_spawn_ignores_max_recursion_depth() {
    UUID parentId = UUID.randomUUID();
    CaseInstance parent = buildInstance(parentId, null, "other-ns", "other-case", "2.0.0");
    caseInstanceCache.put(parent);

    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    registry.getOrCreate(parentId, "test-tenant");
    PlanItem item = PlanItem.create("spawn-different", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(parentId).orElseThrow()).addPlanItem(item);

    // Parent is other-case, child is recursive-case — not a self-reference
    SubCase differentChild =
        SubCase.builder().namespace(NS).name(NAME).version(VERSION).maxRecursionDepth(0).build();
    SubCaseScheduleEvent event =
        new SubCaseScheduleEvent(parent, differentChild, Map.of(), "spawn-different");

    handler.onSubCaseSchedule(event).await().indefinitely();

    assertThat(item.getStatus())
        .as("non-self-reference bypasses depth check entirely")
        .isEqualTo(PlanItemStatus.DELEGATED);
  }

  @Test
  void cache_miss_stops_walk_permissive_defensive() {
    int maxDepth = 3;

    // Build chain: root → L1 → L2 (spawning parent), but do NOT cache root.
    UUID rootId = UUID.randomUUID();
    // root deliberately NOT put in cache

    UUID l1Id = UUID.randomUUID();
    CaseInstance l1 = buildInstance(l1Id, rootId, NS, NAME, VERSION);
    caseInstanceCache.put(l1);

    UUID l2Id = UUID.randomUUID();
    CaseInstance l2 = buildInstance(l2Id, l1Id, NS, NAME, VERSION);
    caseInstanceCache.put(l2);

    // L2's walk: finds L1 (depth=1), then tries root (cache miss, stops). depth=1 < 3 → allow.
    // Actual depth is 2 (root + L1). The walk is permissive on cache miss.
    // This scenario cannot occur under the current cache lifecycle (no eviction) —
    // this test documents the fail-open behavior as a defensive specification.
    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    registry.getOrCreate(l2Id, "test-tenant");
    PlanItem item = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(l2Id).orElseThrow()).addPlanItem(item);

    handler.onSubCaseSchedule(selfReferenceEvent(l2, maxDepth)).await().indefinitely();

    assertThat(item.getStatus())
        .as("cache miss → walk stops early → lower depth → fail-open (permissive)")
        .isEqualTo(PlanItemStatus.DELEGATED);
  }

  @Test
  void total_counting_across_non_matching_ancestors() {
    int maxDepth = 2;

    // Build chain: A₁ → B → A₂ (spawning parent)
    UUID a1Id = UUID.randomUUID();
    CaseInstance a1 = buildInstance(a1Id, null, NS, NAME, VERSION);
    caseInstanceCache.put(a1);

    UUID bId = UUID.randomUUID();
    CaseInstance b = buildInstance(bId, a1Id, "other-ns", "other-case", "1.0.0");
    caseInstanceCache.put(b);

    UUID a2Id = UUID.randomUUID();
    CaseInstance a2 = buildInstance(a2Id, bId, NS, NAME, VERSION);
    caseInstanceCache.put(a2);

    // A₂'s walk: B (skip), A₁ (depth=1). With consecutive counting this would be 0 (parent is B).
    // But A₂'s parent meta matches the SubCase identity (self-reference), so the guard fires.
    // Total depth = 1 (A₁). 1 < 2 → allow.
    UUID childId = UUID.randomUUID();
    when(caseHubRuntime.startCase(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(childId));

    registry.getOrCreate(a2Id, "test-tenant");
    PlanItem item = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(a2Id).orElseThrow()).addPlanItem(item);

    handler.onSubCaseSchedule(selfReferenceEvent(a2, maxDepth)).await().indefinitely();

    assertThat(item.getStatus())
        .as("total counting finds A₁ across B — depth=1 < maxDepth=2 → allowed")
        .isEqualTo(PlanItemStatus.DELEGATED);

    // Now test the boundary: same chain but maxDepth=1 → 1 >= 1 → fault
    UUID a2bId = UUID.randomUUID();
    CaseInstance a2b = buildInstance(a2bId, bId, NS, NAME, VERSION);
    caseInstanceCache.put(a2b);

    registry.getOrCreate(a2bId, "test-tenant");
    PlanItem item2 = PlanItem.create("spawn-self", "unknown", 0);
    ((DefaultCasePlanModel) registry.get(a2bId).orElseThrow()).addPlanItem(item2);

    handler.onSubCaseSchedule(selfReferenceEvent(a2b, 1)).await().indefinitely();

    assertThat(item2.getStatus())
        .as("total counting: A₁ across B gives depth=1, 1 >= 1 → faulted")
        .isEqualTo(PlanItemStatus.FAULTED);
  }
}
