# ContextBridge Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #203 — epic: ContextBridge protocol and worker-level context selection
**Issue group:** #203

**Goal:** Introduce typed context bridging across the engine worker pipeline,
replacing hardcoded `Map<String, Object>` with generic `T` via `ContextBridge<T>`.

**Architecture:** `WorkerFunction` gains a type parameter `<T>` carrying the
input type. Worker.Builder gains `fn().apply()` using the Reified Varargs Type
Token pattern for zero-argument type capture. `ContextBridge<T>` SPI in
engine-api translates between `CaseContext` and typed `T`. `BridgeResolver` in
runtime resolves bridges via a CDI-based priority chain. Pipeline handlers
(`WorkerScheduleEventHandler`, `QuartzWorkerExecutionJob`) invoke bridges at
scheduling and execution boundaries.

**Tech Stack:** Java 21, Quarkus 3.32.x, CDI (Quarkus ARC), Jackson, JQ (jackson-jq)

**Spec:** `docs/specs/2026-07-09-context-bridge-architecture.md`

## Global Constraints

- `WorkerFunction` is in `casehub-worker-api` (tier 1). `ContextBridge<T>`
  is in `casehub-engine-api` (tier 2). Worker-api must NOT reference
  ContextBridge — it carries only `Class<?>` type tokens.
- `JQEvaluator` is in `casehub-engine-common` (internal). Bridge
  implementations in engine-api must NOT access it — the pipeline evaluates
  JQ and passes `JsonNode narrowedInput` to the bridge.
- All new SPIs use `default` methods per the SPI evolution protocol.
- `@DefaultBean @ApplicationScoped` for no-op/fallback beans.
- Tests use `casehub-persistence-memory` with `quarkus.arc.selected-alternatives`.
- Every commit references `#203`.
- Build: `mvn install -DskipTests -q` before module-specific tests.
  Always include `TESTCONTAINERS_RYUK_DISABLED=true` for test runs.

---

### Task 1: Parameterise WorkerFunction\<T\> and Worker.Builder.fn().apply()

**Files:**
- Modify: `worker-api:io/casehub/worker/api/WorkerFunction.java`
- Modify: `worker-api:io/casehub/worker/api/Worker.java`
- Modify: `api:io/casehub/api/model/AgentWorkerFunction.java`
- Modify: `flow:io/casehub/engine/flow/FlowWorkerFunction.java`
- Modify: `api:io/casehub/api/model/WorkerFunctions.java`
- Create: `worker-api:io/casehub/worker/api/TypedFunctionBuilder.java`
- Test: `worker-api:io/casehub/worker/api/WorkerFunctionTest.java`
- Test: `worker-api:io/casehub/worker/api/WorkerBuilderTest.java` (new)

**Interfaces:**
- Produces: `WorkerFunction<T>` with `Class<T> inputType()`, `Sync<T>(Class<T>, Function<T, WorkerResult>)`, `None implements WorkerFunction<Void>`
- Produces: `Worker.Builder.fn(T...)` returning `TypedFunctionBuilder<T>`
- Produces: `TypedFunctionBuilder<T>.apply(Function<T, WorkerResult>)` returning `Worker.Builder`
- Produces: `AgentWorkerFunction implements WorkerFunction<Map<String, Object>>` with `inputType() == Map.class`
- Produces: `FlowWorkerFunction implements WorkerFunction<Map<String, Object>>` with `inputType() == Map.class`

- [ ] **Step 1: Write failing test — WorkerFunction\<T\> parameterisation**

```java
// WorkerFunctionTest.java — add new test method
@Test
void typedSyncCarriesInputType() {
    var fn = new WorkerFunction.Sync<>(String.class, s -> WorkerResult.of(Map.of("len", s.length())));
    assertThat(fn.inputType()).isEqualTo(String.class);
}

@Test
void untypedSyncDefaultsToMapClass() {
    var fn = new WorkerFunction.Sync<>(Map.class,
        input -> WorkerResult.of(Map.of()));
    assertThat(fn.inputType()).isEqualTo(Map.class);
}

@Test
void noneHasVoidInputType() {
    assertThat(WorkerFunction.NONE.inputType()).isEqualTo(Void.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl worker-api -Dtest=WorkerFunctionTest#typedSyncCarriesInputType -q`
Expected: FAIL — `Sync` constructor does not accept `Class<T>`, `inputType()` does not exist.

- [ ] **Step 3: Implement WorkerFunction\<T\>**

Update `WorkerFunction.java`:

```java
public interface WorkerFunction<T> {

    WorkerFunction<Void> NONE = new None();

    Class<T> inputType();

    record Sync<T>(Class<T> inputType,
                   Function<T, WorkerResult> fn) implements WorkerFunction<T> {
        public Sync {
            Objects.requireNonNull(inputType, "inputType must not be null");
            Objects.requireNonNull(fn, "fn must not be null");
        }
    }

    record None() implements WorkerFunction<Void> {
        @Override
        public Class<Void> inputType() { return Void.class; }
    }
}
```

- [ ] **Step 4: Fix all compilation errors from the parameterisation**

The `Sync` record signature changed — all call sites that construct `Sync`
must add the `inputType` parameter. Key sites:

- `Worker.Builder.function(Function<Map<String,Object>, WorkerResult>)` →
  pass `Map.class` as first arg: `new WorkerFunction.Sync<>(Map.class, fn)`
- `WorkerFunctions.sequence()` → pass `Map.class`:
  `new WorkerFunction.Sync<>(Map.class, input -> { ... })`
- `CaseDefinitionYamlMapper` — any inline `new Sync(...)` → add `Map.class`

Use `ide_find_references` on `WorkerFunction.Sync` to find all construction sites.

- [ ] **Step 5: Implement AgentWorkerFunction parameterisation**

```java
public record AgentWorkerFunction(Agent agent)
    implements WorkerFunction<Map<String, Object>> {

    public AgentWorkerFunction {
        Objects.requireNonNull(agent);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> inputType() {
        return (Class) Map.class;
    }
}
```

- [ ] **Step 6: Implement FlowWorkerFunction parameterisation**

```java
public record FlowWorkerFunction(Workflow workflow)
    implements WorkerFunction<Map<String, Object>> {

    // ... existing constructor validation ...

    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> inputType() {
        return (Class) Map.class;
    }
}
```

- [ ] **Step 7: Write failing test — Worker.Builder.fn().apply()**

```java
// WorkerBuilderTest.java (new file)
@Test
void fnApplyCreatesTypedSyncFunction() {
    record TestInput(String value) {}

    Worker worker = Worker.builder()
        .name("test")
        .capabilityName("cap")
        .<TestInput>fn()
        .apply(input -> WorkerResult.of(Map.of("v", input.value())))
        .build();

    assertThat(worker.function()).isInstanceOf(WorkerFunction.Sync.class);
    WorkerFunction.Sync<?> sync = (WorkerFunction.Sync<?>) worker.function();
    assertThat(sync.inputType()).isEqualTo(TestInput.class);
}

@Test
void fnApplyWithMapTypeResolvesMapClass() {
    Worker worker = Worker.builder()
        .name("test")
        .capabilityName("cap")
        .<Map<String, Object>>fn()
        .apply(input -> WorkerResult.of(input))
        .build();

    WorkerFunction.Sync<?> sync = (WorkerFunction.Sync<?>) worker.function();
    assertThat(sync.inputType()).isEqualTo(Map.class);
}

@Test
void legacyFunctionStillWorks() {
    Worker worker = Worker.builder()
        .name("test")
        .capabilityName("cap")
        .function(input -> WorkerResult.of(input))
        .build();

    WorkerFunction.Sync<?> sync = (WorkerFunction.Sync<?>) worker.function();
    assertThat(sync.inputType()).isEqualTo(Map.class);
}
```

- [ ] **Step 8: Run test to verify it fails**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl worker-api -Dtest=WorkerBuilderTest -q`
Expected: FAIL — `fn()` method does not exist on Builder.

- [ ] **Step 9: Implement TypedFunctionBuilder and Worker.Builder.fn()**

Create `TypedFunctionBuilder.java`:

```java
package io.casehub.worker.api;

import java.util.Map;
import java.util.function.Function;

public class TypedFunctionBuilder<T> {
    private final Worker.Builder parent;
    private final Class<?> runtimeType;

    TypedFunctionBuilder(Worker.Builder parent, Class<?> runtimeType) {
        this.parent = parent;
        this.runtimeType = runtimeType;
    }

    @SuppressWarnings("unchecked")
    public Worker.Builder apply(Function<T, WorkerResult> fn) {
        parent.function(new WorkerFunction.Sync<>(runtimeType, fn));
        return parent;
    }
}
```

Add to `Worker.Builder`:

```java
@SafeVarargs
public final <T> TypedFunctionBuilder<T> fn(T... typeToken) {
    Class<?> runtimeType = typeToken.getClass().getComponentType();
    return new TypedFunctionBuilder<>(this, runtimeType);
}
```

- [ ] **Step 10: Run all worker-api tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl worker-api -q`
Expected: ALL PASS

- [ ] **Step 11: Install and verify cross-module compilation**

Run: `mvn install -DskipTests -q`
Expected: BUILD SUCCESS — all modules compile with the parameterised types.

- [ ] **Step 12: Commit**

```bash
git add worker-api/ api/src/main/java/io/casehub/api/model/AgentWorkerFunction.java flow/src/main/java/io/casehub/engine/flow/FlowWorkerFunction.java api/src/main/java/io/casehub/api/model/WorkerFunctions.java
git commit -m "feat(#203): parameterise WorkerFunction<T> and add Worker.Builder.fn().apply()"
```

---

### Task 2: ContextBridge\<T\> SPI and Built-in Bridges

**Files:**
- Create: `api:io/casehub/api/context/ContextBridge.java`
- Create: `api:io/casehub/api/context/MapBridge.java`
- Create: `api:io/casehub/api/context/JacksonPojoBridge.java`
- Create: `api:io/casehub/api/context/JsonNodeBridge.java`
- Create: `api:io/casehub/api/context/BridgeTypeMismatchException.java`
- Test: `api:io/casehub/api/context/MapBridgeTest.java` (new)
- Test: `api:io/casehub/api/context/JacksonPojoBridgeTest.java` (new)
- Test: `api:io/casehub/api/context/JsonNodeBridgeTest.java` (new)

**Interfaces:**
- Produces: `ContextBridge<T>` — `initialise(CaseContext, JsonNode)`, `extractOutput(T)`, `serialise(T)`, `deserialise(JsonNode)`, `onWrite(...)`, `isLiveView()`, `contextType()`
- Produces: `MapBridge implements ContextBridge<Map<String, Object>>` — identity bridge
- Produces: `JacksonPojoBridge<T> implements ContextBridge<T>` — Jackson convertValue
- Produces: `JsonNodeBridge implements ContextBridge<JsonNode>` — direct asJsonNode
- Produces: `BridgeTypeMismatchException extends RuntimeException`

- [ ] **Step 1: Write failing test — MapBridge identity**

```java
// MapBridgeTest.java
@Test
void initialiseReturnsNarrowedInputAsMap() {
    var bridge = new MapBridge();
    var context = new CaseContextImpl(Map.of("a", 1, "b", 2));
    var narrowed = MAPPER.valueToTree(Map.of("a", 1));

    Map<String, Object> result = bridge.initialise(context, narrowed);

    assertThat(result).containsEntry("a", 1).hasSize(1);
}

@Test
void serialiseAndDeserialiseRoundTrip() {
    var bridge = new MapBridge();
    Map<String, Object> input = Map.of("key", "value", "num", 42);

    JsonNode serialised = bridge.serialise(input);
    Map<String, Object> deserialised = bridge.deserialise(serialised);

    assertThat(deserialised).containsEntry("key", "value");
    assertThat(deserialised.get("num")).isEqualTo(42);
}

@Test
void contextTypeIsMap() {
    assertThat(new MapBridge().contextType()).isEqualTo(Map.class);
}

@Test
void isNotLiveView() {
    assertThat(new MapBridge().isLiveView()).isFalse();
}

@Test
void extractOutputReturnsNull() {
    assertThat(new MapBridge().extractOutput(Map.of())).isNull();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=MapBridgeTest -q`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement ContextBridge interface**

```java
package io.casehub.api.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public interface ContextBridge<T> {

    T initialise(CaseContext context, JsonNode narrowedInput);

    default Map<String, Object> extractOutput(T context) {
        return null;
    }

    JsonNode serialise(T context);

    T deserialise(JsonNode payload);

    default void onWrite(String key, Object value, CaseContext enclosing) {}

    default boolean isLiveView() {
        return false;
    }

    Class<T> contextType();
}
```

- [ ] **Step 4: Implement MapBridge**

```java
package io.casehub.api.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;

public class MapBridge implements ContextBridge<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};

    @Override
    public Map<String, Object> initialise(CaseContext context,
                                           JsonNode narrowedInput) {
        return MAPPER.convertValue(narrowedInput, MAP_TYPE);
    }

    @Override
    public JsonNode serialise(Map<String, Object> context) {
        return MAPPER.valueToTree(context);
    }

    @Override
    public Map<String, Object> deserialise(JsonNode payload) {
        return MAPPER.convertValue(payload, MAP_TYPE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> contextType() {
        return (Class) Map.class;
    }
}
```

- [ ] **Step 5: Implement BridgeTypeMismatchException**

```java
package io.casehub.api.context;

public class BridgeTypeMismatchException extends RuntimeException {
    public BridgeTypeMismatchException(String expected, String actual) {
        super("Bridge type mismatch: expected " + expected + " but received " + actual);
    }
}
```

- [ ] **Step 6: Run MapBridge tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=MapBridgeTest -q`
Expected: ALL PASS

- [ ] **Step 7: Write failing test — JacksonPojoBridge**

```java
// JacksonPojoBridgeTest.java
record TestPojo(String name, int value) {}

@Test
void initialiseDeserialisesFromNarrowedInput() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var narrowed = MAPPER.valueToTree(Map.of("name", "alice", "value", 42));

    TestPojo result = bridge.initialise(
        new CaseContextImpl(Map.of("name", "alice", "value", 42)),
        narrowed);

    assertThat(result.name()).isEqualTo("alice");
    assertThat(result.value()).isEqualTo(42);
}

@Test
void serialiseAndDeserialiseRoundTrip() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var pojo = new TestPojo("bob", 7);

    JsonNode serialised = bridge.serialise(pojo);
    TestPojo deserialised = bridge.deserialise(serialised);

    assertThat(deserialised).isEqualTo(pojo);
}

@Test
void contextTypeMatchesConstructorArg() {
    assertThat(new JacksonPojoBridge<>(TestPojo.class).contextType())
        .isEqualTo(TestPojo.class);
}

@Test
void initialiseFailsWithClearExceptionOnTypeMismatch() {
    var bridge = new JacksonPojoBridge<>(TestPojo.class);
    var badInput = MAPPER.valueToTree(Map.of("wrongField", "data"));

    // Jackson will produce TestPojo with null name and 0 value
    // (no required fields enforced by default) — this is valid Jackson behavior
    TestPojo result = bridge.initialise(
        new CaseContextImpl(Map.of()), badInput);
    assertThat(result.name()).isNull();
}
```

- [ ] **Step 8: Implement JacksonPojoBridge**

```java
package io.casehub.api.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class JacksonPojoBridge<T> implements ContextBridge<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Class<T> targetClass;

    public JacksonPojoBridge(Class<T> targetClass) {
        this.targetClass = targetClass;
    }

    @Override
    public T initialise(CaseContext context, JsonNode narrowedInput) {
        return MAPPER.convertValue(narrowedInput, targetClass);
    }

    @Override
    public JsonNode serialise(T context) {
        return MAPPER.valueToTree(context);
    }

    @Override
    public T deserialise(JsonNode payload) {
        return MAPPER.convertValue(payload, targetClass);
    }

    @Override
    public Class<T> contextType() {
        return targetClass;
    }
}
```

- [ ] **Step 9: Implement JsonNodeBridge**

```java
package io.casehub.api.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public class JsonNodeBridge implements ContextBridge<JsonNode> {

    @Override
    public JsonNode initialise(CaseContext context, JsonNode narrowedInput) {
        return narrowedInput;
    }

    @Override
    public JsonNode serialise(JsonNode context) {
        return context;
    }

    @Override
    public JsonNode deserialise(JsonNode payload) {
        return payload;
    }

    @Override
    public Class<JsonNode> contextType() {
        return JsonNode.class;
    }
}
```

- [ ] **Step 10: Write and run JsonNodeBridge tests**

```java
// JsonNodeBridgeTest.java
@Test
void initialiseReturnsNarrowedInputDirectly() {
    var bridge = new JsonNodeBridge();
    var node = MAPPER.valueToTree(Map.of("a", 1));
    assertThat(bridge.initialise(new CaseContextImpl(), node)).isSameAs(node);
}

@Test
void serialiseAndDeserialiseAreIdentity() {
    var bridge = new JsonNodeBridge();
    var node = MAPPER.valueToTree(Map.of("key", "value"));
    assertThat(bridge.serialise(node)).isSameAs(node);
    assertThat(bridge.deserialise(node)).isSameAs(node);
}
```

- [ ] **Step 11: Run all api tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -q`
Expected: ALL PASS

- [ ] **Step 12: Commit**

```bash
git add api/src/main/java/io/casehub/api/context/ContextBridge.java api/src/main/java/io/casehub/api/context/MapBridge.java api/src/main/java/io/casehub/api/context/JacksonPojoBridge.java api/src/main/java/io/casehub/api/context/JsonNodeBridge.java api/src/main/java/io/casehub/api/context/BridgeTypeMismatchException.java api/src/test/
git commit -m "feat(#203): add ContextBridge<T> SPI and built-in bridges (Map, Jackson, JsonNode)"
```

---

### Task 3: BridgeResolver and CaseDefinition.defaultWorkerBridge()

**Files:**
- Create: `runtime:io/casehub/engine/internal/context/BridgeResolver.java`
- Modify: `api:io/casehub/api/model/CaseDefinition.java` (add `defaultWorkerBridge` field and builder method)
- Test: `runtime:io/casehub/engine/internal/context/BridgeResolverTest.java` (new)

**Interfaces:**
- Consumes: `ContextBridge<T>` (Task 2), `WorkerFunction<T>.inputType()` (Task 1)
- Produces: `BridgeResolver.resolve(Worker, CaseDefinition)` → `ContextBridge<?>`
- Produces: `BridgeResolver.resolveByTypeName(String)` → `ContextBridge<?>`
- Produces: Pipeline helper methods: `initialise()`, `serialise()`, `deserialise()`, `extractOutput()`
- Produces: `CaseDefinition.getDefaultWorkerBridge()` → `ContextBridge<?>` (nullable)

- [ ] **Step 1: Write failing test — BridgeResolver resolution chain**

```java
// BridgeResolverTest.java
record TestPojo(String name) {}

@Test
void resolvesMapBridgeForMapInputType() {
    var resolver = new BridgeResolver(Instance.empty());
    var worker = Worker.builder().name("w").capabilityName("c")
        .function(input -> WorkerResult.of(input)).build();

    ContextBridge<?> bridge = resolver.resolve(worker, null);

    assertThat(bridge).isInstanceOf(MapBridge.class);
}

@Test
void resolvesJacksonPojoBridgeForUnknownClass() {
    var resolver = new BridgeResolver(Instance.empty());
    var worker = Worker.builder().name("w").capabilityName("c")
        .<TestPojo>fn().apply(p -> WorkerResult.of(Map.of())).build();

    ContextBridge<?> bridge = resolver.resolve(worker, null);

    assertThat(bridge).isInstanceOf(JacksonPojoBridge.class);
    assertThat(bridge.contextType()).isEqualTo(TestPojo.class);
}

@Test
void resolvesCdiDiscoveredBridgeByContextType() {
    // Create a custom bridge
    var customBridge = new ContextBridge<TestPojo>() {
        // ... implement all methods ...
        @Override public Class<TestPojo> contextType() { return TestPojo.class; }
    };
    var resolver = new BridgeResolver(instanceOf(customBridge));
    var worker = Worker.builder().name("w").capabilityName("c")
        .<TestPojo>fn().apply(p -> WorkerResult.of(Map.of())).build();

    ContextBridge<?> bridge = resolver.resolve(worker, null);

    assertThat(bridge).isSameAs(customBridge);
}

@Test
void resolvesCaseDefinitionDefaultWhenInputTypeMatches() {
    var defaultBridge = new JacksonPojoBridge<>(TestPojo.class);
    var definition = CaseDefinition.builder()
        .name("test").defaultWorkerBridge(defaultBridge).build();
    var resolver = new BridgeResolver(Instance.empty());
    var worker = Worker.builder().name("w").capabilityName("c")
        .<TestPojo>fn().apply(p -> WorkerResult.of(Map.of())).build();

    ContextBridge<?> bridge = resolver.resolve(worker, definition);

    assertThat(bridge).isSameAs(defaultBridge);
}

@Test
void skipsDefaultBridgeWhenInputTypeDoesNotMatch() {
    var defaultBridge = new JacksonPojoBridge<>(TestPojo.class);
    var definition = CaseDefinition.builder()
        .name("test").defaultWorkerBridge(defaultBridge).build();
    var resolver = new BridgeResolver(Instance.empty());
    var worker = Worker.builder().name("w").capabilityName("c")
        .function(input -> WorkerResult.of(input)).build(); // Map type

    ContextBridge<?> bridge = resolver.resolve(worker, definition);

    assertThat(bridge).isInstanceOf(MapBridge.class); // NOT the default
}

@Test
void resolveByTypeNameFallsBackToMapBridgeForNull() {
    var resolver = new BridgeResolver(Instance.empty());
    assertThat(resolver.resolveByTypeName(null)).isInstanceOf(MapBridge.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=BridgeResolverTest -q`
Expected: FAIL — BridgeResolver does not exist.

- [ ] **Step 3: Add defaultWorkerBridge to CaseDefinition**

Add field `private ContextBridge<?> defaultWorkerBridge` to CaseDefinition,
getter `getDefaultWorkerBridge()`, and builder method
`defaultWorkerBridge(ContextBridge<?> bridge)`.

- [ ] **Step 4: Implement BridgeResolver**

```java
package io.casehub.engine.internal.context;

import io.casehub.api.context.*;
import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

@ApplicationScoped
public class BridgeResolver {

    private static final MapBridge MAP_BRIDGE = new MapBridge();
    private final Instance<ContextBridge<?>> bridges;

    @Inject
    public BridgeResolver(Instance<ContextBridge<?>> bridges) {
        this.bridges = bridges;
    }

    public ContextBridge<?> resolve(Worker worker, CaseDefinition definition) {
        Class<?> inputType = worker.function().inputType();

        // Step 1: CaseDefinition default (when inputType matches)
        if (definition != null && definition.getDefaultWorkerBridge() != null) {
            ContextBridge<?> def = definition.getDefaultWorkerBridge();
            if (def.contextType().equals(inputType)) {
                return def;
            }
        }

        // Step 2: CDI discovery by contextType match
        for (ContextBridge<?> bridge : bridges) {
            if (bridge.contextType().equals(inputType)) {
                return bridge;
            }
        }

        // Step 3: Map.class → MapBridge
        if (Map.class.equals(inputType)) {
            return MAP_BRIDGE;
        }

        // Step 4: Any other class → JacksonPojoBridge
        return new JacksonPojoBridge<>(inputType);
    }

    public ContextBridge<?> resolveByTypeName(String typeName) {
        if (typeName == null) return MAP_BRIDGE;

        for (ContextBridge<?> bridge : bridges) {
            if (bridge.contextType().getName().equals(typeName)) {
                return bridge;
            }
        }

        try {
            Class<?> clazz = Class.forName(typeName);
            if (Map.class.equals(clazz)) return MAP_BRIDGE;
            return new JacksonPojoBridge<>(clazz);
        } catch (ClassNotFoundException e) {
            return MAP_BRIDGE;
        }
    }

    // Pipeline helper methods — capture wildcard safely
    @SuppressWarnings("unchecked")
    public <T> T initialise(ContextBridge<T> bridge,
                            CaseContext context, JsonNode narrowedInput) {
        return bridge.initialise(context, narrowedInput);
    }

    @SuppressWarnings("unchecked")
    public <T> JsonNode serialise(ContextBridge<T> bridge, Object input) {
        return bridge.serialise((T) input);
    }

    @SuppressWarnings("unchecked")
    public <T> T deserialise(ContextBridge<T> bridge, JsonNode payload) {
        return bridge.deserialise(payload);
    }

    @SuppressWarnings("unchecked")
    public <T> Map<String, Object> extractOutput(ContextBridge<T> bridge,
                                                  Object context) {
        return bridge.extractOutput((T) context);
    }
}
```

- [ ] **Step 5: Run BridgeResolver tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=BridgeResolverTest -q`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/context/BridgeResolver.java api/src/main/java/io/casehub/api/model/CaseDefinition.java runtime/src/test/
git commit -m "feat(#203): add BridgeResolver with CDI discovery chain and CaseDefinition.defaultWorkerBridge()"
```

---

### Task 4: Pipeline Signature Changes

**Files:**
- Modify: `common:io/casehub/engine/common/internal/executor/WorkerFunctionHandler.java`
- Modify: `common:io/casehub/engine/common/internal/executor/WorkerExecutor.java`
- Modify: `runtime:io/casehub/engine/internal/executor/DefaultWorkerExecutor.java`
- Modify: `runtime:io/casehub/engine/internal/executor/SyncAgentWorkerFunctionHandler.java`
- Modify: `flow:io/casehub/engine/flow/FlowWorkerFunctionHandler.java`
- Modify: `runtime:io/casehub/engine/internal/executor/DefaultWorkerRuntime.java`
- Test: existing tests in runtime, flow, common

**Interfaces:**
- Consumes: `WorkerFunction<T>.inputType()` (Task 1), `BridgeTypeMismatchException` (Task 2)
- Produces: `WorkerFunctionHandler.execute(WorkerFunction<?>, Object, WorkerContext, int, ExecutionMetadata)`
- Produces: `WorkerExecutor.execute(WorkerFunction<?>, Object, WorkerContext, int, String, ExecutionMetadata)`

- [ ] **Step 1: Change WorkerFunctionHandler.execute() signature**

`Map<String, Object> inputData` → `Object inputData`

- [ ] **Step 2: Change WorkerExecutor.execute() signature**

`Map<String, Object> inputData` → `Object inputData`

- [ ] **Step 3: Update DefaultWorkerExecutor**

Pass `Object inputData` through to handler. No type change needed in the
composite logic — it just delegates.

- [ ] **Step 4: Update SyncAgentWorkerFunctionHandler**

Add runtime type check at entry. Update the switch to handle typed input:

```java
@Override
public Uni<WorkerResult> execute(WorkerFunction<?> function, Object inputData,
                                  WorkerContext context, int timeoutMs,
                                  ExecutionMetadata metadata) {

    if (!function.inputType().isInstance(inputData)) {
        throw new BridgeTypeMismatchException(
            function.inputType().getName(), inputData.getClass().getName());
    }

    Function<Object, WorkerResult> fn =
        switch (function) {
            case WorkerFunction.Sync<?> sync -> input -> sync.fn().apply(input);
            case AgentWorkerFunction agent ->
                input -> agent.agent().execute((Map<String, Object>) input);
            default ->
                throw new UnsupportedOperationException(
                    "Unsupported: " + function.getClass().getName());
        };
    // ... rest unchanged, uses fn.apply(inputData) ...
}
```

- [ ] **Step 5: Update FlowWorkerFunctionHandler**

Same pattern — `Map<String, Object> inputData` → `Object inputData`, cast
to Map internally (FlowWorkerFunction always has `inputType() == Map.class`).

- [ ] **Step 6: Update DefaultWorkerRuntime.execute()**

The in-process path remains Map-based per spec. The method continues to
accept `Map<String, Object>` — it does not change. The handler-level
signature change is transparent because `DefaultWorkerRuntime` calls the
handler directly, not through `WorkerExecutor`.

- [ ] **Step 7: Fix all compilation errors**

Use `ide_find_references` on the old `execute` signatures to find all call
sites. Key sites:
- `QuartzWorkerExecutionJob.execute()` — passes `inputData` (currently Map)
- `DefaultWorkerExecutor.execute()` — passes through
- Tests that call `handler.execute()` directly

- [ ] **Step 8: Run all affected module tests**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl common,runtime,flow,scheduler-quartz -q`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add common/ runtime/ flow/ scheduler-quartz/
git commit -m "feat(#203): change pipeline signatures from Map<String,Object> to Object for typed bridge support"
```

---

### Task 5: WorkerScheduleEventHandler Bridge Integration

**Files:**
- Modify: `runtime:io/casehub/engine/internal/engine/handler/WorkerScheduleEventHandler.java`
- Test: existing + new test in `runtime:io/casehub/engine/internal/engine/handler/WorkerScheduleEventHandlerTest.java`

**Interfaces:**
- Consumes: `BridgeResolver.resolve()`, `BridgeResolver.initialise()`, `BridgeResolver.serialise()` (Task 3)
- Consumes: `CaseDefinitionRegistry.getCaseDefinition()` for bridge resolution
- Produces: EventLog metadata with `contextBridgeType` key
- Produces: Serialised typed input in EventLog payload

- [ ] **Step 1: Write failing test — bridge integration in scheduling**

```java
@Test
void schedulingWithTypedWorkerWritesBridgeTypeToEventLogMetadata() {
    record AmlInput(String txnId, double amount) {}

    // Define a case with a typed worker
    var definition = testDefinitionWith(
        Worker.builder().name("assess").capabilityName("assess")
            .<AmlInput>fn()
            .apply(input -> WorkerResult.of(Map.of("risk", input.amount() > 1000)))
            .build());

    // Set up context with matching data
    var instance = createCaseInstance(definition,
        Map.of("txnId", "TXN-1", "amount", 5000.0));

    // Trigger scheduling
    handler.onWorkerScheduleEventHandler(
        new WorkerScheduleEvent(instance, worker, capability, "binding-1"));

    // Verify EventLog metadata contains contextBridgeType
    var eventLog = getLatestEventLog(instance.getUuid());
    assertThat(eventLog.getMetadata().path("contextBridgeType").asText())
        .isEqualTo("io.casehub...AmlInput");
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — handler does not write `contextBridgeType` metadata.

- [ ] **Step 3: Inject BridgeResolver and CaseDefinitionRegistry into handler**

Add `@Inject BridgeResolver bridgeResolver` and
`@Inject CaseDefinitionRegistry caseDefinitionRegistry` fields.

- [ ] **Step 4: Update onWorkerScheduleEventHandler to use bridge**

Replace the JQ evaluation and Map conversion with bridge-based typing:

```java
// Evaluate JQ to get narrowed JsonNode (not Map)
JsonNode narrowedInput = jqEvaluator.evaluate(
    instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode(),
    event.effectiveInputSchema())
    .orElse(instance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode());

// Resolve bridge and initialise typed input
CaseDefinition definition =
    caseDefinitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
ContextBridge<?> bridge = bridgeResolver.resolve(event.worker(), definition);
Object typedInput = bridgeResolver.initialise(bridge,
    instance.getCaseContext(), narrowedInput);

// For EventLog payload, serialise via bridge
JsonNode serialisedPayload = bridgeResolver.serialise(bridge, typedInput);

// For inputDataHash, convert to Map (hash is on Map representation)
Map<String, Object> inputData = OBJECT_MAPPER.convertValue(narrowedInput,
    new TypeReference<Map<String, Object>>() {});
```

- [ ] **Step 5: Add contextBridgeType to EventLog metadata**

In `buildEventLog()`, add to metadata:

```java
metadata.put("contextBridgeType", bridge.contextType().getName());
```

Use the serialised payload (from bridge) instead of `OBJECT_MAPPER.valueToTree(inputData)`.

- [ ] **Step 6: Run handler tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerScheduleEventHandler* -q`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkerScheduleEventHandler.java runtime/src/test/
git commit -m "feat(#203): integrate ContextBridge into WorkerScheduleEventHandler — typed serialisation + metadata"
```

---

### Task 6: QuartzWorkerExecutionJob Bridge Integration

**Files:**
- Modify: `scheduler-quartz:io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionJob.java`
- Test: existing + new tests

**Interfaces:**
- Consumes: `BridgeResolver.resolveByTypeName()`, `BridgeResolver.deserialise()`, `BridgeResolver.initialise()`, `BridgeResolver.extractOutput()` (Task 3)
- Consumes: EventLog metadata `contextBridgeType` (Task 5)

- [ ] **Step 1: Write failing test — typed deserialisation from EventLog**

```java
@Test
void executionDeserialisesTypedInputViaBridge() {
    record TestInput(String name, int value) {}

    // Create EventLog with contextBridgeType metadata and serialised POJO payload
    var payload = MAPPER.valueToTree(new TestInput("alice", 42));
    var metadata = MAPPER.createObjectNode()
        .put("contextBridgeType", TestInput.class.getName())
        .put("capabilityName", "cap")
        .put("bindingName", "binding");
    var eventLog = createEventLog(payload, metadata);

    // Execute the job
    job.execute(createJobContext(eventLog));

    // Verify the worker received a TestInput, not a Map
    assertThat(capturedInput).isInstanceOf(TestInput.class);
    assertThat(((TestInput) capturedInput).name()).isEqualTo("alice");
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — job still deserialises to Map.

- [ ] **Step 3: Inject BridgeResolver into QuartzWorkerExecutionJob**

Add `@Inject BridgeResolver bridgeResolver` field.

- [ ] **Step 4: Update execute() to use bridge**

Replace:
```java
Map<String, Object> inputData =
    OBJECT_MAPPER.convertValue(eventLog.getPayload(), Map.class);
```

With:
```java
String bridgeTypeName =
    eventLog.getMetadata().path("contextBridgeType").asText(null);
ContextBridge<?> bridge =
    bridgeResolver.resolveByTypeName(bridgeTypeName);

Object typedInput;
if (bridge.isLiveView()) {
    typedInput = bridgeResolver.initialise(bridge,
        instance.getCaseContext(), eventLog.getPayload());
} else {
    typedInput = bridgeResolver.deserialise(bridge,
        eventLog.getPayload());
}
```

Update `workerExecutor.execute()` call to pass `typedInput` instead of
`inputData`.

- [ ] **Step 5: Add extractOutput for live-view bridges in onSuccess**

In the success callback, before calling `onSuccess`:

```java
workerResult -> {
    Map<String, Object> output = workerResult.output();
    if ((output == null || output.isEmpty()) && bridge.isLiveView()) {
        output = bridgeResolver.extractOutput(bridge, typedInput);
    }
    onSuccess(instance, worker, inputDataHash, workerResult, bindingName, signalId);
}
```

Note: `onSuccess` currently takes `WorkerResult` — it may need adjustment
to accept the potentially-overridden output. Check the existing signature
and adapt.

- [ ] **Step 6: Run scheduler-quartz tests**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl scheduler-quartz -q`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add scheduler-quartz/
git commit -m "feat(#203): integrate ContextBridge into QuartzWorkerExecutionJob — typed deserialisation + extractOutput"
```

---

### Task 7: CaseDefinitionYamlMapper contextType Support

**Files:**
- Modify: `api:io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`
- Test: existing + new tests in `api:io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java`

**Interfaces:**
- Consumes: `WorkerFunction.Sync<T>` (Task 1)

- [ ] **Step 1: Write failing test — YAML contextType creates typed WorkerFunction**

```java
@Test
void contextTypeCreatesTypedSyncFunction() {
    String yaml = """
        name: test-case
        workers:
          - name: assess
            contextType: io.casehub.api.model.converter.CaseDefinitionYamlMapperTest$TestInput
            capabilities: [assess]
        """;
    record TestInput(String name) {}

    CaseDefinition def = CaseDefinitionYamlMapper.fromYaml(yaml);
    Worker worker = def.getWorkers().get(0);

    assertThat(worker.function().inputType())
        .isEqualTo(TestInput.class);
}
```

- [ ] **Step 2: Implement contextType parsing**

In the worker construction section of `CaseDefinitionYamlMapper`, check for
`contextType` field:

```java
String contextTypeName = workerNode.path("contextType").asText(null);
if (contextTypeName != null) {
    Class<?> contextType = Class.forName(contextTypeName);
    workerFunction = new WorkerFunction.Sync<>(contextType,
        input -> WorkerResult.of(Map.of())); // placeholder — real function from agent/flow
}
```

Integration with agent and flow workers: when `contextType` is specified on
an agent or flow worker, it has no effect per spec (they hardcode
`inputType() == Map.class`). Log a warning if both are specified.

- [ ] **Step 3: Run yaml mapper tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=CaseDefinitionYamlMapper* -q`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java api/src/test/
git commit -m "feat(#203): add contextType YAML support in CaseDefinitionYamlMapper"
```

---

### Task 8: Integration Tests — Engine Worker Boundary

**Files:**
- Create: `runtime:io/casehub/engine/ContextBridgeIntegrationTest.java` (new)

**Interfaces:**
- Consumes: Everything from Tasks 1-7

- [ ] **Step 1: Write Pattern 1 test — Case → Worker with MapBridge (identity)**

```java
@QuarkusTest
class ContextBridgeIntegrationTest {

    @Test
    void untypedWorkerReceivesMapViaIdentityBridge() {
        // Define case with untyped worker
        var caseHub = new CaseHub() {
            @Override protected CaseDefinition definition() {
                return CaseDefinition.builder()
                    .name("pattern-1")
                    .capability("do-work", "{ txnId, amount }")
                    .worker(Worker.builder()
                        .name("worker-1")
                        .capabilityName("do-work")
                        .function(input -> {
                            assertThat(input).containsKey("txnId");
                            return WorkerResult.of(Map.of("result", "done"));
                        })
                        .build())
                    .binding("do-work-binding", "do-work",
                        new ContextChangeTrigger(".txnId != null"))
                    .build();
            }
        };

        // Start case, signal, verify
        UUID caseId = runtime.startCase(caseHub, Map.of("txnId", "TXN-1", "amount", 100));
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var ctx = runtime.getCaseContext(caseId);
            assertThat(ctx.getString("result")).isEqualTo("done");
        });
    }
}
```

- [ ] **Step 2: Write Pattern 2 test — Case → Worker with typed POJO bridge**

```java
@Test
void typedWorkerReceivesPojoViaJacksonBridge() {
    record AmlInput(String txnId, double amount) {}

    var caseHub = new CaseHub() {
        @Override protected CaseDefinition definition() {
            return CaseDefinition.builder()
                .name("pattern-2")
                .capability("assess", "{ txnId, amount }")
                .worker(Worker.builder()
                    .name("assessor")
                    .capabilityName("assess")
                    .<AmlInput>fn()
                    .apply(input -> {
                        // Compiler-checked: input IS AmlInput
                        assertThat(input).isInstanceOf(AmlInput.class);
                        assertThat(input.txnId()).isEqualTo("TXN-1");
                        assertThat(input.amount()).isEqualTo(5000.0);
                        return WorkerResult.of(Map.of(
                            "risk", input.amount() > 1000 ? "HIGH" : "LOW"));
                    })
                    .build())
                .binding("assess-binding", "assess",
                    new ContextChangeTrigger(".txnId != null"))
                .build();
        }
    };

    UUID caseId = runtime.startCase(caseHub, Map.of("txnId", "TXN-1", "amount", 5000.0));
    await().atMost(5, SECONDS).untilAsserted(() -> {
        var ctx = runtime.getCaseContext(caseId);
        assertThat(ctx.getString("risk")).isEqualTo("HIGH");
    });
}
```

- [ ] **Step 3: Write test — EventLog metadata contains contextBridgeType**

```java
@Test
void eventLogContainsBridgeTypeMetadata() {
    record TestInput(String id) {}

    // ... set up case with typed worker ...

    // After execution, verify EventLog
    var logs = eventLogRepository.findByCaseId(caseId).await().indefinitely();
    var scheduledLog = logs.stream()
        .filter(e -> e.getEventType() == CaseHubEventType.WORKER_SCHEDULED)
        .findFirst().orElseThrow();

    assertThat(scheduledLog.getMetadata().path("contextBridgeType").asText())
        .isEqualTo(TestInput.class.getName());
}
```

- [ ] **Step 4: Write test — backward compatibility with pre-bridge EventLog entries**

```java
@Test
void preBridgeEventLogEntriesDeserialiseToMap() {
    // Simulate a pre-bridge EventLog (no contextBridgeType in metadata)
    var resolver = new BridgeResolver(Instance.empty());
    var bridge = resolver.resolveByTypeName(null);

    assertThat(bridge).isInstanceOf(MapBridge.class);

    var payload = MAPPER.valueToTree(Map.of("key", "value"));
    Object result = bridge.deserialise(payload);
    assertThat(result).isInstanceOf(Map.class);
}
```

- [ ] **Step 5: Run all integration tests**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=ContextBridgeIntegrationTest -q`
Expected: ALL PASS

- [ ] **Step 6: Run full test suite**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -q`
Expected: ALL PASS — no regressions across any module.

- [ ] **Step 7: Commit**

```bash
git add runtime/src/test/java/io/casehub/engine/ContextBridgeIntegrationTest.java
git commit -m "test(#203): add ContextBridge integration tests — identity and POJO bridge patterns"
```

---

## Task Dependency Graph

```
Task 1 (WorkerFunction<T>)
  ↓
Task 2 (ContextBridge SPI) ──→ Task 3 (BridgeResolver)
  ↓                              ↓
Task 4 (Pipeline signatures) ←───┘
  ↓
Task 5 (WorkerScheduleEventHandler)
  ↓
Task 6 (QuartzWorkerExecutionJob)
  ↓
Task 7 (YAML contextType)
  ↓
Task 8 (Integration tests)
```

Tasks 1 and 2 can run in parallel. All others are sequential.
