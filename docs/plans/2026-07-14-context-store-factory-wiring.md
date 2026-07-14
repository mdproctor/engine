# CaseContextStoreFactory Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #725 — wire CaseContextStoreFactory resolution through CaseHubRuntimeImpl
**Issue group:** #725, #695

**Goal:** Complete the CaseContextStoreFactory SPI wiring so case definitions can specify
a custom context store factory and the engine resolves it at startCase time.

**Architecture:** Inject `StrategyResolver` into `CaseHubRuntimeImpl`, generate UUID early,
resolve the factory from `CaseDefinition.getContextStoreFactory()`, create `CaseContextImpl`
with the resolved factory, and thread the UUID through to `CaseHubReactor.buildInstance()`.
YAML mapping reads `context.storeFactory` from the raw node.

**Tech Stack:** Java 21, Quarkus CDI, casehub-engine-api, casehub-engine (runtime)

## Global Constraints

- `contextStoreFactory` is a nullable String on `CaseDefinition` — null means default (in-memory)
- YAML key is `context.storeFactory` (nested under `context:` block)
- Durable factories (`isDurable()=true`) are rejected at startCase with `UnsupportedOperationException`
  until recovery path migration is complete
- `snapshot()` and `fromLayerDocument()` intentionally use in-memory factory — no change
- `SubCaseExecutionHandler` delegates to `CaseHubRuntime.startCase()` — no change needed

---

### Task 1: YAML Mapping — context.storeFactory

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`
- Test: `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java`

**Interfaces:**
- Consumes: `CaseDefinition.setContextStoreFactory(String)` (already exists)
- Produces: YAML `context:\n  storeFactory: "auditing"` → `CaseDefinition.getContextStoreFactory() == "auditing"`

- [ ] **Step 1: Write failing tests**

Add two tests to `CaseDefinitionYamlMapperTest`:

```java
@Test
void load_contextStoreFactory_setsFactory() throws IOException {
    String yaml =
        """
        namespace: test
        name: Factory Case
        version: 1.0.0
        context:
          storeFactory: auditing
        spec:
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getContextStoreFactory()).isEqualTo("auditing");
}

@Test
void load_noContextBlock_storeFactoryNull() throws IOException {
    String yaml =
        """
        namespace: test
        name: No Context Case
        version: 1.0.0
        spec:
          capabilities: []
          workers: []
          bindings: []
        """;

    InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    CaseDefinition def = CaseDefinitionYamlMapper.load(is);

    assertThat(def.getContextStoreFactory()).isNull();
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl api -Dtest=CaseDefinitionYamlMapperTest#load_contextStoreFactory_setsFactory+load_noContextBlock_storeFactoryNull -DfailIfNoTests=false`

Expected: first test FAILS (contextStoreFactory is null — no mapping exists). Second test PASSES (null is the default).

- [ ] **Step 3: Implement the mapping**

In `CaseDefinitionYamlMapper.convertToApiModel()`, add after the `labels` block (~line 266)
and before the capabilities conversion:

```java
// context.storeFactory — read from raw node (not in generated schema)
JsonNode contextNode = rawNode.get("context");
if (contextNode != null && contextNode.has("storeFactory")) {
    def.setContextStoreFactory(contextNode.get("storeFactory").asText());
}
```

Use `ide_insert_member` is not appropriate here — this is inline code in an existing method.
Use `Edit` on the mapper file to add the block after the labels section.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -pl api -Dtest=CaseDefinitionYamlMapperTest#load_contextStoreFactory_setsFactory+load_noContextBlock_storeFactoryNull -DfailIfNoTests=false`

Expected: both PASS

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java
git add api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java
git commit -m "feat(#725): YAML mapping for context.storeFactory

Refs #725"
```

---

### Task 2: Thread UUID and resolve factory in CaseHubRuntimeImpl + CaseHubReactor

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubRuntimeImpl.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubReactor.java`

**Interfaces:**
- Consumes: `StrategyResolver.resolve(CaseContextStoreFactory.class, String)`, `CaseDefinition.getContextStoreFactory()`
- Produces: All `startCase` overloads resolve the factory and create `CaseContextImpl(factory, uuid)` with pre-generated UUID

- [ ] **Step 1: Modify CaseHubRuntimeImpl — inject StrategyResolver and resolve factory**

Add `@Inject` field:

```java
@Inject StrategyResolver strategyResolver;
```

Replace all five `startCase` overloads. Each follows the same pattern — resolve factory,
generate UUID, create context, pass to reactor. The no-inputData overload:

```java
@Override
public CompletionStage<UUID> startCase(CaseDefinition definition) {
    CaseContextStoreFactory factory = strategyResolver.resolve(
        CaseContextStoreFactory.class, definition.getContextStoreFactory());
    if (factory.isDurable()) {
        throw new UnsupportedOperationException(
            "CaseContextStoreFactory '" + factory.id() + "' reports isDurable()=true but "
            + "recovery path is not yet wired — durable factories will silently lose case "
            + "state on JVM restart. Implement recovery migration before deploying durable factories.");
    }
    UUID caseId = UUID.randomUUID();
    return reactor.startCase(definition, new CaseContextImpl(factory, caseId), caseId);
}
```

The inputData overload:

```java
@Override
public CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData) {
    CaseContextStoreFactory factory = strategyResolver.resolve(
        CaseContextStoreFactory.class, definition.getContextStoreFactory());
    if (factory.isDurable()) {
        throw new UnsupportedOperationException(
            "CaseContextStoreFactory '" + factory.id() + "' reports isDurable()=true but "
            + "recovery path is not yet wired — durable factories will silently lose case "
            + "state on JVM restart. Implement recovery migration before deploying durable factories.");
    }
    UUID caseId = UUID.randomUUID();
    CaseContextImpl context = new CaseContextImpl(factory, caseId);
    Map<String, Object> inputMap = toContextMap(inputData);
    if (!inputMap.isEmpty()) {
        context.setAll(inputMap);
    }
    return reactor.startCase(definition, context, caseId);
}
```

Apply the same pattern to the remaining three overloads (with parentCaseId, with semanticData,
with both). Each gets factory resolution, durable guard, UUID generation, context creation
with setAll for inputData, and passes caseId to reactor.

Extract the factory resolution + durable guard into a private method to keep it DRY:

```java
private CaseContextStoreFactory resolveFactory(CaseDefinition definition) {
    CaseContextStoreFactory factory = strategyResolver.resolve(
        CaseContextStoreFactory.class, definition.getContextStoreFactory());
    if (factory.isDurable()) {
        throw new UnsupportedOperationException(
            "CaseContextStoreFactory '" + factory.id() + "' reports isDurable()=true but "
            + "recovery path is not yet wired — durable factories will silently lose case "
            + "state on JVM restart. Implement recovery migration before deploying durable factories.");
    }
    return factory;
}

private CaseContextImpl createContext(CaseContextStoreFactory factory, UUID caseId, Object inputData) {
    CaseContextImpl context = new CaseContextImpl(factory, caseId);
    Map<String, Object> inputMap = toContextMap(inputData);
    if (!inputMap.isEmpty()) {
        context.setAll(inputMap);
    }
    return context;
}
```

Add imports: `io.casehub.api.context.CaseContextStoreFactory`, `io.casehub.platform.api.routing.StrategyResolver`.

- [ ] **Step 2: Modify CaseHubReactor — accept UUID parameter**

All `startCase` overloads and `startCaseInternal` gain a `UUID caseId` parameter:

```java
CompletionStage<UUID> startCase(CaseDefinition definition, MutableCaseContext context, UUID caseId) {
    return startCaseInternal(definition, context, caseId, null, null, null);
}
```

Update all four `startCase` overloads + `startCaseInternal` signature:

```java
private CompletionStage<UUID> startCaseInternal(
    CaseDefinition definition,
    MutableCaseContext context,
    UUID caseId,
    UUID parentCaseId,
    PropagationContext parentPropCtx,
    Map<String, Object> semanticData) {
```

In `buildInstance`, also add `UUID caseId` parameter and replace `UUID.randomUUID()` at line 238:

```java
instance.setUuid(caseId);
```

- [ ] **Step 3: Build to verify compilation**

Run: `mvn compile -pl runtime -DskipTests -q`

Expected: BUILD SUCCESS (no compilation errors)

- [ ] **Step 4: Run existing tests to check for regressions**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -q`

Expected: existing tests pass. Some tests may need updating if they call reactor.startCase
directly — check and fix any that fail.

- [ ] **Step 5: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubRuntimeImpl.java
git add runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubReactor.java
git commit -m "feat(#725): wire CaseContextStoreFactory through CaseHubRuntimeImpl

Inject StrategyResolver, resolve factory from CaseDefinition, generate
UUID early, thread through to CaseHubReactor.buildInstance().
Durable factories rejected until recovery path migration.

Refs #725"
```

---

### Task 3: Unit tests for factory resolution and UUID threading

**Files:**
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/CaseHubRuntimeImplTest.java`

**Interfaces:**
- Consumes: `CaseHubRuntimeImpl.startCase()` overloads from Task 2

- [ ] **Step 1: Write tests**

`CaseHubRuntimeImpl` is `@ApplicationScoped` with `@Inject` dependencies. For unit tests
without CDI, use constructor injection or a test helper. Since reactor is package-private,
test via the `@QuarkusTest` integration path in the runtime module.

Create a focused test class:

```java
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.internal.context.InMemoryCaseContextStoreFactory;
import io.casehub.platform.api.routing.StrategyResolver;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CaseHubRuntimeImplTest {

    // Recording factory that logs createStore calls
    static class RecordingFactory implements CaseContextStoreFactory {
        final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<UUID> caseIds = new CopyOnWriteArrayList<>();

        @Override
        public String id() { return "recording"; }

        @Override
        public CaseContextStore createStore(String layerName, UUID caseId) {
            calls.add(layerName);
            caseIds.add(caseId);
            return InMemoryCaseContextStoreFactory.INSTANCE.createStore(layerName, caseId);
        }
    }

    @Test
    void resolveFactory_defaultFactory_returnsInMemory() {
        // null contextStoreFactory on definition → StrategyResolver returns default
        var resolver = testResolver(new InMemoryCaseContextStoreFactory());
        var factory = resolver.resolve(CaseContextStoreFactory.class, null);
        assertThat(factory.id()).isEqualTo("in-memory");
    }

    @Test
    void resolveFactory_namedFactory_returnsNamed() {
        var recording = new RecordingFactory();
        var resolver = testResolver(recording);
        var factory = resolver.resolve(CaseContextStoreFactory.class, "recording");
        assertThat(factory.id()).isEqualTo("recording");
    }

    @Test
    void resolveFactory_durableFactory_throws() {
        var durable = new CaseContextStoreFactory() {
            @Override public String id() { return "durable-test"; }
            @Override public CaseContextStore createStore(String l, UUID c) { return null; }
            @Override public boolean isDurable() { return true; }
        };

        assertThatThrownBy(() -> {
            if (durable.isDurable()) {
                throw new UnsupportedOperationException(
                    "CaseContextStoreFactory '" + durable.id() + "' reports isDurable()=true");
            }
        }).isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("isDurable()=true");
    }

    @Test
    void createContext_withFactory_usesFactoryForAllLayers() {
        var recording = new RecordingFactory();
        UUID caseId = UUID.randomUUID();
        var context = new io.casehub.engine.internal.context.CaseContextImpl(recording, caseId);

        // Factory should have been called for WORKING, SEMANTIC, EPISODIC
        assertThat(recording.calls).containsExactlyInAnyOrder("working", "semantic", "episodic");
        assertThat(recording.caseIds).containsOnly(caseId);
    }

    @Test
    void createContext_withInputData_populatesWorkingLayer() {
        var factory = InMemoryCaseContextStoreFactory.INSTANCE;
        UUID caseId = UUID.randomUUID();
        var context = new io.casehub.engine.internal.context.CaseContextImpl(factory, caseId);
        context.setAll(Map.of("key1", "value1", "key2", 42));

        assertThat(context.get("key1")).isEqualTo("value1");
        assertThat(context.get("key2")).isEqualTo(42);
    }

    @Test
    void createContext_emptyInputData_noError() {
        var factory = InMemoryCaseContextStoreFactory.INSTANCE;
        UUID caseId = UUID.randomUUID();
        var context = new io.casehub.engine.internal.context.CaseContextImpl(factory, caseId);
        context.setAll(Map.of());

        assertThat(context.isEmpty()).isTrue();
    }

    private StrategyResolver testResolver(CaseContextStoreFactory factory) {
        return io.casehub.engine.internal.routing.EngineStrategyResolver.forTest(
            java.util.List.of(
                new io.casehub.engine.internal.routing.EngineStrategyResolver.TestHandle<>(
                    factory, true)));
    }
}
```

- [ ] **Step 2: Run tests**

Run: `mvn test -pl runtime -Dtest=CaseHubRuntimeImplTest -DfailIfNoTests=false`

Expected: all PASS

- [ ] **Step 3: Commit**

```bash
git add runtime/src/test/java/io/casehub/engine/internal/engine/CaseHubRuntimeImplTest.java
git commit -m "test(#725): unit tests for factory resolution and context creation

Refs #725"
```

---

### Task 4: Integration test — end-to-end custom factory

**Files:**
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/ContextStoreFactoryWiringTest.java`

**Interfaces:**
- Consumes: Full startCase pipeline from Tasks 1-2

- [ ] **Step 1: Write the integration test**

This is a `@QuarkusTest` that defines a CaseHub with `contextStoreFactory: "recording"`,
starts a case, and verifies the recording factory was used.

```java
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.context.CaseContextStore;
import io.casehub.api.context.CaseContextStoreFactory;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.internal.context.InMemoryCaseContextStoreFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ContextStoreFactoryWiringTest {

    @ApplicationScoped
    static class RecordingCaseContextStoreFactory implements CaseContextStoreFactory {
        static final CopyOnWriteArrayList<String> layers = new CopyOnWriteArrayList<>();
        static final CopyOnWriteArrayList<UUID> caseIds = new CopyOnWriteArrayList<>();

        @Override
        public String id() { return "recording"; }

        @Override
        public CaseContextStore createStore(String layerName, UUID caseId) {
            layers.add(layerName);
            if (caseId != null) caseIds.add(caseId);
            return InMemoryCaseContextStoreFactory.INSTANCE.createStore(layerName, caseId);
        }
    }

    @Inject CaseHubRuntime runtime;

    @BeforeEach
    void reset() {
        RecordingCaseContextStoreFactory.layers.clear();
        RecordingCaseContextStoreFactory.caseIds.clear();
    }

    @Test
    void startCase_withRecordingFactory_usesCorrectFactory() throws Exception {
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test")
            .name("recording-test")
            .version("1.0.0")
            .contextStoreFactory("recording")
            .build();

        UUID caseId = runtime.startCase(def, Map.of("key", "value")).toCompletableFuture().get();

        assertThat(caseId).isNotNull();
        assertThat(RecordingCaseContextStoreFactory.layers)
            .containsExactlyInAnyOrder("working", "semantic", "episodic");
        assertThat(RecordingCaseContextStoreFactory.caseIds).isNotEmpty();
        assertThat(RecordingCaseContextStoreFactory.caseIds.get(0)).isEqualTo(caseId);
    }

    @Test
    void startCase_noFactory_usesDefaultInMemory() throws Exception {
        CaseDefinition def = CaseDefinition.builder()
            .namespace("test")
            .name("default-test")
            .version("1.0.0")
            .build();

        UUID caseId = runtime.startCase(def).toCompletableFuture().get();

        assertThat(caseId).isNotNull();
        // Recording factory should NOT have been called
        assertThat(RecordingCaseContextStoreFactory.layers).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=ContextStoreFactoryWiringTest -DfailIfNoTests=false`

Expected: both PASS

- [ ] **Step 3: Run full runtime test suite for regressions**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -q`

Expected: all tests PASS

- [ ] **Step 4: Commit**

```bash
git add runtime/src/test/java/io/casehub/engine/internal/engine/ContextStoreFactoryWiringTest.java
git commit -m "test(#725): integration test for CaseContextStoreFactory wiring

Recording factory verifies createStore called with correct layers and caseId.
Default factory path verifies recording factory NOT called.

Refs #725"
```

---

### Task 5: File follow-up issue for recovery path migration

**Files:** None (GitHub issue only)

- [ ] **Step 1: Create follow-up issue**

```bash
gh issue create --repo casehubio/engine \
  --title "feat: wire CaseContextStoreFactory through recovery path" \
  --body "## Background

engine#725 wired CaseContextStoreFactory through CaseHubRuntimeImpl.startCase(). A durable
factory guard rejects isDurable()=true factories until the recovery path is updated.

## Gap

DefaultWorkerExecutionRecoveryService.rebuildStateContext() creates new CaseContextImpl()
and uses CaseContextImpl.fromLayerDocument() — both hardcode InMemoryCaseContextStoreFactory.

## Scope

1. Resolve the factory from the recovered CaseDefinition in the recovery path
2. Use loadStore() for durable factories, EventLog replay for volatile
3. Remove the durable guard from CaseHubRuntimeImpl.resolveFactory()

## Design source

[CaseContextStore SPI design](docs/specs/2026-07-13-case-context-store-design.md) §Recovery Model

## Depends on

- engine#725 (this issue)"
```

- [ ] **Step 2: Record the issue number**

Note the created issue number for CLAUDE.md update.

- [ ] **Step 3: Commit spec updates if needed**

No file changes — issue created only.
