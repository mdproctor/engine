# Bounded Recursive Sub-Case Spawning — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hard circular self-reference guard in `SubCaseExecutionHandler` with a bounded depth limit, enabling recursive CasePlanModels where a case can spawn sub-cases of itself up to a configurable maximum depth.

**Architecture:** `SubCase` gains a `maxRecursionDepth` field (int, default 0). The handler computes depth by walking the `parentCaseId` chain via `CaseInstanceCache`, counting all same-definition ancestors (total counting). If depth >= limit, the spawn is faulted.

**Tech Stack:** Java 21, Quarkus 3.32, JUnit 5, Mockito, AssertJ, jsonschema2pojo (YAML schema → generated model)

## Global Constraints

- `maxRecursionDepth` defaults to 0 — all existing case definitions behave identically (hard block on self-reference)
- YAML schema caps maxRecursionDepth at 20; API model has no upper bound
- Depth uses total counting (all same-definition ancestors, not just consecutive)
- Version-strict matching: (namespace, name, version) — same as existing guard
- Build: `mvn install -DskipTests -q` before module-specific tests; always `TESTCONTAINERS_RYUK_DISABLED=true` prefix
- Tests: `*Test.java` naming (surefire); never `*IT.java`

## File Map

| File | Action | Responsibility |
|---|---|---|
| `schema/src/main/resources/schema/CaseDefinition.yaml` | Modify | Add `maxRecursionDepth` property to SubCase definition |
| `api/src/main/java/io/casehub/api/model/SubCase.java` | Modify | Add field, builder method, accessor, validation |
| `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` | Modify | Map `maxRecursionDepth` in `convertSubCase()` |
| `blackboard/src/main/java/io/casehub/blackboard/subcase/SubCaseExecutionHandler.java` | Modify | Inject `CaseInstanceCache`, replace guard with bounded depth check |
| `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseRecursionDepthTest.java` | Create | New test class — 6 test cases for depth behavior |
| `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseExecutionHandlerTest.java` | Modify | Update setUp for `CaseInstanceCache`, update circular_dependency test |

---

### Task 1: Schema and API model — add `maxRecursionDepth` to SubCase

**Files:**
- Modify: `schema/src/main/resources/schema/CaseDefinition.yaml:594-637` (SubCase definition)
- Modify: `api/src/main/java/io/casehub/api/model/SubCase.java:24-175`
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java:460-478`

**Interfaces:**
- Consumes: nothing (foundation task)
- Produces: `SubCase.maxRecursionDepth(): int` (accessor), `SubCase.Builder.maxRecursionDepth(int): Builder`

- [ ] **Step 1: Add `maxRecursionDepth` to the YAML schema**

In `schema/src/main/resources/schema/CaseDefinition.yaml`, add to the SubCase properties block (after `outputMapping` at line 637):

```yaml
      maxRecursionDepth:
        type: integer
        minimum: 0
        maximum: 20
        default: 0
        description: "Maximum self-referencing depth. 0 = no recursion (default). N = allow N levels."
```

- [ ] **Step 2: Regenerate the schema model**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn generate-sources -pl schema -q
```

Verify the generated `io.casehub.model.SubCase` now has `getMaxRecursionDepth()` / `setMaxRecursionDepth(Integer)`.

- [ ] **Step 3: Add the field to `SubCase.java`**

In `api/src/main/java/io/casehub/api/model/SubCase.java`:

Add the field after `onThresholdReached` (line 35):

```java
  private final int maxRecursionDepth;
```

In the constructor (line 37), add after the `onThresholdReached` assignment (line 52):

```java
    if (b.maxRecursionDepth < 0) {
      throw new IllegalArgumentException("maxRecursionDepth must be >= 0, got: " + b.maxRecursionDepth);
    }
    this.maxRecursionDepth = b.maxRecursionDepth;
```

Add accessor after `onThresholdReached()` (line 97):

```java
  public int maxRecursionDepth() {
    return maxRecursionDepth;
  }
```

In the `Builder` inner class, add the field after `onThresholdReached` (line 114):

```java
    private int maxRecursionDepth = 0;
```

Add the builder method after `onThresholdReached(OnThresholdReached)` (line 168):

```java
    public Builder maxRecursionDepth(int v) {
      maxRecursionDepth = v;
      return this;
    }
```

- [ ] **Step 4: Map the field in `CaseDefinitionYamlMapper.convertSubCase()`**

In `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`, add to the builder chain in `convertSubCase()` (line 477, before `.build()`):

```java
        .maxRecursionDepth(
            schemaModel.getMaxRecursionDepth() != null
                ? schemaModel.getMaxRecursionDepth()
                : 0)
```

- [ ] **Step 5: Build and verify compilation**

```bash
mvn install -DskipTests -q
```

Expected: BUILD SUCCESS. No compilation errors.

- [ ] **Step 6: Run existing SubCase tests to verify no regressions**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest="io.casehub.api.model.converter.CaseDefinitionYamlMapperTest" -q
```

Expected: All existing tests pass. Default `maxRecursionDepth = 0` means no behavioral change.

- [ ] **Step 7: Commit**

```bash
git add schema/src/main/resources/schema/CaseDefinition.yaml \
       api/src/main/java/io/casehub/api/model/SubCase.java \
       api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java
git commit -m "feat(#573): add maxRecursionDepth to SubCase model and YAML schema

Refs #573"
```

---

### Task 2: Replace the circular guard with bounded depth check

**Files:**
- Modify: `blackboard/src/main/java/io/casehub/blackboard/subcase/SubCaseExecutionHandler.java`

**Interfaces:**
- Consumes: `SubCase.maxRecursionDepth(): int` (from Task 1)
- Produces: `SubCaseExecutionHandler` with `CaseInstanceCache` dependency, `computeSameDefinitionDepth(CaseInstance, SubCase, int): int` (private)

- [ ] **Step 1: Add `CaseInstanceCache` dependency**

In `SubCaseExecutionHandler.java`, add the import:

```java
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
```

Add the field after `registry` (line 59):

```java
  private final CaseInstanceCache caseInstanceCache;
```

Update the constructor (line 62) — add `CaseInstanceCache caseInstanceCache` as the last parameter:

```java
  @Inject
  public SubCaseExecutionHandler(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry caseDefinitionRegistry,
      CaseInstanceRepository caseInstanceRepository,
      EventLogRepository eventLogRepository,
      PendingWorkRegistry pendingWorkRegistry,
      SubCaseGroupRepository subCaseGroupRepository,
      BlackboardRegistry registry,
      CaseInstanceCache caseInstanceCache) {
```

Add the assignment at the end of the constructor body:

```java
    this.caseInstanceCache = caseInstanceCache;
```

- [ ] **Step 2: Add the depth computation method**

Add after `faultPlanItem()` (after line 172):

```java
  private int computeSameDefinitionDepth(CaseInstance parent, SubCase subCase, int maxDepth) {
    int depth = 0;
    UUID ancestorId = parent.getParentCaseId();
    while (ancestorId != null && depth < maxDepth) {
      CaseInstance ancestor = caseInstanceCache.get(ancestorId);
      if (ancestor == null) {
        break;
      }
      CaseMetaModel meta = ancestor.getCaseMetaModel();
      if (meta != null
          && subCase.namespace().equals(meta.getNamespace())
          && subCase.name().equals(meta.getName())
          && subCase.version().equals(meta.getVersion())) {
        depth++;
      }
      ancestorId = ancestor.getParentCaseId();
    }
    return depth;
  }
```

- [ ] **Step 3: Replace the circular guard (lines 85-95)**

Replace this block:

```java
    CaseMetaModel parentMeta = parent.getCaseMetaModel();
    if (parentMeta != null
        && subCase.namespace().equals(parentMeta.getNamespace())
        && subCase.name().equals(parentMeta.getName())
        && subCase.version().equals(parentMeta.getVersion())) {
      LOG.errorf(
          "SubCase circular dependency: case %s cannot spawn itself (%s/%s/%s)",
          parent.getUuid(), subCase.namespace(), subCase.name(), subCase.version());
      faultPlanItem(parent.getUuid(), bindingName);
      return Uni.createFrom().voidItem();
    }
```

With:

```java
    CaseMetaModel parentMeta = parent.getCaseMetaModel();
    boolean selfReference =
        parentMeta != null
            && subCase.namespace().equals(parentMeta.getNamespace())
            && subCase.name().equals(parentMeta.getName())
            && subCase.version().equals(parentMeta.getVersion());

    if (selfReference) {
      int maxDepth = subCase.maxRecursionDepth();
      int depth = computeSameDefinitionDepth(parent, subCase, maxDepth);
      if (depth >= maxDepth) {
        LOG.warnf(
            "SubCase recursion depth %d reached limit %d for case %s (%s/%s/%s)",
            depth,
            maxDepth,
            parent.getUuid(),
            subCase.namespace(),
            subCase.name(),
            subCase.version());
        faultPlanItem(parent.getUuid(), bindingName);
        return Uni.createFrom().voidItem();
      }
    }
```

- [ ] **Step 4: Build to verify compilation**

```bash
mvn install -DskipTests -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add blackboard/src/main/java/io/casehub/blackboard/subcase/SubCaseExecutionHandler.java
git commit -m "feat(#573): replace circular guard with bounded depth check

Inject CaseInstanceCache, walk parentCaseId chain counting all
same-definition ancestors (total counting). Short-circuits at
maxRecursionDepth. Default 0 preserves current hard-block behavior.

Refs #573"
```

---

### Task 3: Update existing tests and add recursion depth tests

**Files:**
- Modify: `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseExecutionHandlerTest.java`
- Create: `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseRecursionDepthTest.java`

**Interfaces:**
- Consumes: `SubCase.maxRecursionDepth(): int`, `SubCaseExecutionHandler` constructor with `CaseInstanceCache` parameter (from Tasks 1-2)
- Produces: test coverage for all depth behaviors

- [ ] **Step 1: Update `SubCaseExecutionHandlerTest` for new constructor parameter**

In `SubCaseExecutionHandlerTest.java`, add the import:

```java
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.internal.engine.cache.CaseInstanceCacheImpl;
```

Add a field after `plan` (line 60):

```java
  private CaseInstanceCache caseInstanceCache;
```

In `setUp()`, initialize the cache before the handler construction (before line 90):

```java
    caseInstanceCache = new CaseInstanceCacheImpl();
```

Update the handler construction (lines 90-98) to pass the cache as the last argument:

```java
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
```

- [ ] **Step 2: Run existing tests to verify they still pass**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -Dtest="io.casehub.blackboard.subcase.SubCaseExecutionHandlerTest" -q
```

Expected: All 7 existing tests pass. The `circular_dependency_marks_plan_item_faulted` test still works because the SubCase it builds has `maxRecursionDepth = 0` (the default), which faults immediately on self-reference.

- [ ] **Step 3: Write the `SubCaseRecursionDepthTest` class**

Create `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseRecursionDepthTest.java`:

```java
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

  private CaseInstance buildInstance(UUID id, UUID parentCaseId, String ns, String name, String ver) {
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

    // Build chain: root → L1 → L2 → L3 (spawning parent)
    UUID rootId = UUID.randomUUID();
    CaseInstance root = buildInstance(rootId, null, NS, NAME, VERSION);
    caseInstanceCache.put(root);

    UUID l1Id = UUID.randomUUID();
    CaseInstance l1 = buildInstance(l1Id, rootId, NS, NAME, VERSION);
    caseInstanceCache.put(l1);

    UUID l2Id = UUID.randomUUID();
    CaseInstance l2 = buildInstance(l2Id, l1Id, NS, NAME, VERSION);
    caseInstanceCache.put(l2);

    // L2 has depth=2 ancestors (root, L1). 2 < 3 → spawn should succeed.
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
        SubCase.builder()
            .namespace(NS)
            .name(NAME)
            .version(VERSION)
            .maxRecursionDepth(0)
            .build();
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
```

- [ ] **Step 4: Run the new tests**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -Dtest="io.casehub.blackboard.subcase.SubCaseRecursionDepthTest" -q
```

Expected: All 6 tests pass.

- [ ] **Step 5: Run the full blackboard subcase test suite**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -Dtest="io.casehub.blackboard.subcase.*" -q
```

Expected: All tests pass (existing + new).

- [ ] **Step 6: Commit**

```bash
git add blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseRecursionDepthTest.java \
       blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseExecutionHandlerTest.java
git commit -m "test(#573): add recursion depth tests, update existing tests for cache

Six test cases: depth-0 hard block, depth-N allows N levels,
(N+1)th spawn faults, non-self-reference bypass, defensive cache
miss, total counting across non-matching ancestors.

Refs #573"
```

---

### Task 4: Full test suite verification

**Files:** None modified — verification only.

**Interfaces:**
- Consumes: all changes from Tasks 1-3
- Produces: confidence that no regressions exist

- [ ] **Step 1: Install all modules**

```bash
mvn install -DskipTests -q
```

- [ ] **Step 2: Run blackboard module tests**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -q
```

Expected: All tests pass.

- [ ] **Step 3: Run api module tests**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -q
```

Expected: All tests pass.

- [ ] **Step 4: Run runtime module tests (SubCase integration tests live here)**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -q
```

Expected: All tests pass. The `SubCaseOutputMappingRecoveryTest` and any other integration tests that construct `SubCaseExecutionHandler` transitively must still compile and pass.

- [ ] **Step 5: If any failures, fix and commit**

Fix regressions, run the failing tests again, commit with:

```bash
git commit -m "fix(#573): address test regressions from bounded recursion change

Refs #573"
```
