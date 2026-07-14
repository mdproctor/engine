# Typed Signal and SubCase Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #691 — typed context for Signal boundary via ContextBridge
**Issue group:** #691, #690

**Goal:** Make signals first-class typed platform concepts and SubCase
context passing expression-engine-aware, extending the ContextBridge
protocol to two more boundaries.

**Architecture:** `SignalType<T>` record declares typed signals at the
platform level. `CaseDefinition` declares accepted signals. Typed signal
overload on `CaseHubRuntime` passes POJOs through the event bus (no
serialisation on internal paths). `SubCaseMapping` sealed interface
replaces `String` input/output mappings on `SubCase`, supporting both JQ
expressions and typed lambdas. `BridgeResolver` gains `resolveByType(Class<?>)`
as the canonical resolution method.

**Tech Stack:** Java 21, Quarkus 3.32, Vert.x EventBus, Jackson, jq
(jackson-jq)

## Global Constraints

- Pre-release platform: breaking changes are free
- Serialisation boundary rule: `bridge.serialise()` only at storage/wire
  boundaries, never on internal JVM transfers
- Expression engine abstraction: mappings dispatch by type, not hardcoded JQ
- IntelliJ MCP mandatory for all .java file operations
- TDD: failing test before implementation, always
- Every commit references an issue (`Refs #691` or `Refs #690`)

---

### Task 1: SignalType record and CaseDefinition.signals field

**Files:**
- Create: `api/src/main/java/io/casehub/api/model/SignalType.java`
- Modify: `api/src/main/java/io/casehub/api/model/CaseDefinition.java` — add `signals` field, getter, setter, builder method
- Test: `api/src/test/java/io/casehub/api/model/SignalTypeTest.java`
- Test: `api/src/test/java/io/casehub/api/model/CaseDefinitionSignalTest.java`

**Interfaces:**
- Produces: `SignalType<T>` record with `name()`, `payloadType()`, `of(String, Class)`, `untyped(String)`. `CaseDefinition.getSignals(): List<SignalType<?>>`, `Builder.signal(SignalType<?>)`.

- [ ] **Step 1: Write SignalType unit tests**

```java
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalTypeTest {

  record PaymentEvent(String txnId, double amount) {}

  @Test
  void of_createsTypedSignal() {
    SignalType<PaymentEvent> signal = SignalType.of("payment-received", PaymentEvent.class);
    assertThat(signal.name()).isEqualTo("payment-received");
    assertThat(signal.payloadType()).isEqualTo(PaymentEvent.class);
  }

  @Test
  void untyped_createsMapSignal() {
    SignalType<Map<String, Object>> signal = SignalType.untyped("generic");
    assertThat(signal.name()).isEqualTo("generic");
    assertThat(signal.payloadType()).isEqualTo(Map.class);
  }

  @Test
  void nullName_throws() {
    assertThatThrownBy(() -> SignalType.of(null, String.class))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullPayloadType_throws() {
    assertThatThrownBy(() -> SignalType.of("test", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void equality_byNameAndType() {
    var a = SignalType.of("x", String.class);
    var b = SignalType.of("x", String.class);
    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
mvn test -pl api -Dtest=SignalTypeTest -Dsurefire.failIfNoSpecifiedTests=false -q
```

Expected: compilation failure — `SignalType` does not exist.

- [ ] **Step 3: Implement SignalType record**

Create `api/src/main/java/io/casehub/api/model/SignalType.java`:

```java
package io.casehub.api.model;

import java.util.Map;
import java.util.Objects;

public record SignalType<T>(String name, Class<T> payloadType) {

  public SignalType {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(payloadType, "payloadType");
  }

  public static <T> SignalType<T> of(String name, Class<T> payloadType) {
    return new SignalType<>(name, payloadType);
  }

  @SuppressWarnings("unchecked")
  public static SignalType<Map<String, Object>> untyped(String name) {
    return new SignalType<>(name, (Class<Map<String, Object>>) (Class<?>) Map.class);
  }
}
```

- [ ] **Step 4: Run SignalType tests — verify pass**

```bash
mvn test -pl api -Dtest=SignalTypeTest -q
```

- [ ] **Step 5: Write CaseDefinition.signals tests**

```java
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CaseDefinitionSignalTest {

  @Test
  void builder_signal_addsToList() {
    CaseDefinition def = CaseDefinition.builder()
        .namespace("test").name("sig-test").version("1.0")
        .signal(SignalType.of("alert", String.class))
        .signal(SignalType.of("update", Integer.class))
        .build();
    assertThat(def.getSignals()).hasSize(2);
    assertThat(def.getSignals().get(0).name()).isEqualTo("alert");
    assertThat(def.getSignals().get(1).name()).isEqualTo("update");
  }

  @Test
  void builder_noSignals_emptyList() {
    CaseDefinition def = CaseDefinition.builder()
        .namespace("test").name("no-sig").version("1.0")
        .build();
    assertThat(def.getSignals()).isEmpty();
  }

  @Test
  void builder_duplicateSignalName_throws() {
    assertThatThrownBy(() -> CaseDefinition.builder()
        .namespace("test").name("dup").version("1.0")
        .signal(SignalType.of("x", String.class))
        .signal(SignalType.of("x", Integer.class))
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate signal name");
  }
}
```

- [ ] **Step 6: Add signals field to CaseDefinition**

Use `ide_insert_member` to add the field after `contextStoreFactory`:
```java
private List<SignalType<?>> signals = List.of();
```

Add getter/setter:
```java
public List<SignalType<?>> getSignals() { return signals; }
public void setSignals(List<SignalType<?>> signals) { this.signals = signals != null ? List.copyOf(signals) : List.of(); }
```

Add to Builder — field, method, and build() wiring:
```java
// Builder field
private List<SignalType<?>> signals = new ArrayList<>();

// Builder method
public Builder signal(SignalType<?> signal) {
  this.signals.add(signal);
  return this;
}

// In build() — add duplicate name validation
Set<String> signalNames = new HashSet<>();
for (SignalType<?> s : signals) {
  if (!signalNames.add(s.name())) {
    throw new IllegalArgumentException("Duplicate signal name: " + s.name());
  }
}
def.signals = List.copyOf(signals);
```

- [ ] **Step 7: Run all tests — verify pass**

```bash
mvn test -pl api -Dtest="SignalTypeTest,CaseDefinitionSignalTest" -q
```

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/SignalType.java api/src/test/java/io/casehub/api/model/SignalTypeTest.java api/src/test/java/io/casehub/api/model/CaseDefinitionSignalTest.java
git add -u
git commit -m "feat(#691): SignalType record and CaseDefinition.signals field

Refs #691"
```

---

### Task 2: BridgeResolver.resolveByType() — canonical resolution method

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/common/internal/context/BridgeResolver.java` — add `resolveByType(Class<?>)`, refactor `resolveByTypeName()` to delegate
- Test: `common/src/test/java/io/casehub/engine/common/internal/context/BridgeResolverTest.java` (create or extend)

**Interfaces:**
- Consumes: `ContextBridge<T>`, `MapBridge`, `JacksonPojoBridge<T>` (existing)
- Produces: `BridgeResolver.resolveByType(Class<?> payloadType): ContextBridge<?>`

- [ ] **Step 1: Write resolveByType tests**

```java
package io.casehub.engine.common.internal.context;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.context.MapBridge;
import io.casehub.api.context.JacksonPojoBridge;
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BridgeResolverTest {

  record TestPojo(String value) {}

  @Test
  void resolveByType_mapClass_returnsMapBridge() {
    var resolver = new BridgeResolver(emptyBridges());
    var bridge = resolver.resolveByType(Map.class);
    assertThat(bridge).isInstanceOf(MapBridge.class);
  }

  @Test
  void resolveByType_unknownPojo_returnsJacksonBridge() {
    var resolver = new BridgeResolver(emptyBridges());
    var bridge = resolver.resolveByType(TestPojo.class);
    assertThat(bridge).isInstanceOf(JacksonPojoBridge.class);
  }

  @Test
  void resolveByTypeName_delegatesToResolveByType() {
    var resolver = new BridgeResolver(emptyBridges());
    var byType = resolver.resolveByType(Map.class);
    var byName = resolver.resolveByTypeName(Map.class.getName());
    assertThat(byType.getClass()).isEqualTo(byName.getClass());
  }

  @Test
  void resolveByTypeName_null_returnsMapBridge() {
    var resolver = new BridgeResolver(emptyBridges());
    assertThat(resolver.resolveByTypeName(null)).isInstanceOf(MapBridge.class);
  }

  @SuppressWarnings("unchecked")
  private static Instance<io.casehub.api.context.ContextBridge<?>> emptyBridges() {
    Instance<io.casehub.api.context.ContextBridge<?>> mock = Mockito.mock(Instance.class);
    Mockito.when(mock.iterator()).thenReturn(java.util.Collections.emptyIterator());
    return mock;
  }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl common -Dtest=BridgeResolverTest -q
```

Expected: compilation failure — `resolveByType` does not exist.

- [ ] **Step 3: Implement resolveByType and refactor resolveByTypeName**

Add `resolveByType(Class<?>)` to `BridgeResolver` using `ide_insert_member`:

```java
public ContextBridge<?> resolveByType(Class<?> payloadType) {
  for (ContextBridge<?> bridge : bridges) {
    if (bridge.contextType().equals(payloadType)) {
      return bridge;
    }
  }
  if (Map.class.equals(payloadType)) {
    return MAP_BRIDGE;
  }
  return new JacksonPojoBridge<>(payloadType);
}
```

Refactor `resolveByTypeName` using `ide_replace_member`:

```java
public ContextBridge<?> resolveByTypeName(String typeName) {
  if (typeName == null) return MAP_BRIDGE;
  try {
    return resolveByType(Class.forName(typeName));
  } catch (ClassNotFoundException e) {
    return MAP_BRIDGE;
  }
}
```

- [ ] **Step 4: Run tests — verify pass**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl common -Dtest=BridgeResolverTest -q
```

- [ ] **Step 5: Run full common + api test suite — verify no regressions**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api,common -q
```

- [ ] **Step 6: Commit**

```bash
git add -u
git commit -m "refactor(#691): BridgeResolver.resolveByType() — canonical resolution method

resolveByTypeName() now delegates via Class.forName(). Resolution chain
defined once in resolveByType().

Refs #691"
```

---

### Task 3: TypedSignalReceivedEvent and event bus address

**Files:**
- Create: `common/src/main/java/io/casehub/engine/common/internal/event/TypedSignalReceivedEvent.java`
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/EventBusAddresses.java` — add `TYPED_SIGNAL_RECEIVED`
- Create: `api/src/main/java/io/casehub/api/model/SignalRejectedException.java`
- Test: `common/src/test/java/io/casehub/engine/common/internal/event/TypedSignalReceivedEventTest.java`

**Interfaces:**
- Produces: `TypedSignalReceivedEvent(UUID caseId, String signalName, Object payload, Class<?> payloadType, String payloadTypeName, String tenancyId)`. `EventBusAddresses.TYPED_SIGNAL_RECEIVED`. `SignalRejectedException extends RuntimeException`.

- [ ] **Step 1: Write TypedSignalReceivedEvent test**

```java
package io.casehub.engine.common.internal.event;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedSignalReceivedEventTest {

  record Payment(String id, double amount) {}

  @Test
  void recordCarriesAllFields() {
    UUID caseId = UUID.randomUUID();
    Payment payload = new Payment("p1", 99.99);
    var event = new TypedSignalReceivedEvent(
        caseId, "payment-received", payload, Payment.class,
        Payment.class.getName(), "tenant-1");

    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.signalName()).isEqualTo("payment-received");
    assertThat(event.payload()).isSameAs(payload);
    assertThat(event.payloadType()).isEqualTo(Payment.class);
    assertThat(event.payloadTypeName()).isEqualTo(Payment.class.getName());
    assertThat(event.tenancyId()).isEqualTo("tenant-1");
  }
}
```

- [ ] **Step 2: Run test — verify fail**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl common -Dtest=TypedSignalReceivedEventTest -q
```

- [ ] **Step 3: Implement TypedSignalReceivedEvent, SignalRejectedException, event bus address**

Create `common/src/main/java/io/casehub/engine/common/internal/event/TypedSignalReceivedEvent.java`:

```java
package io.casehub.engine.common.internal.event;

import java.util.UUID;

public record TypedSignalReceivedEvent(
    UUID caseId,
    String signalName,
    Object payload,
    Class<?> payloadType,
    String payloadTypeName,
    String tenancyId) {}
```

Create `api/src/main/java/io/casehub/api/model/SignalRejectedException.java`:

```java
package io.casehub.api.model;

public class SignalRejectedException extends RuntimeException {
  public SignalRejectedException(String message) {
    super(message);
  }
}
```

Add to `EventBusAddresses.java` using `ide_insert_member`:

```java
public static final String TYPED_SIGNAL_RECEIVED = "casehub.engine.typed-signal-received";
```

- [ ] **Step 4: Run test — verify pass**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl common -Dtest=TypedSignalReceivedEventTest -q
```

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/io/casehub/engine/common/internal/event/TypedSignalReceivedEvent.java api/src/main/java/io/casehub/api/model/SignalRejectedException.java common/src/test/java/io/casehub/engine/common/internal/event/TypedSignalReceivedEventTest.java
git add -u
git commit -m "feat(#691): TypedSignalReceivedEvent, SignalRejectedException, event bus address

Refs #691"
```

---

### Task 4: CaseHubRuntime typed signal overload + CaseHubRuntimeImpl + CaseHubReactor

**Files:**
- Modify: `api/src/main/java/io/casehub/api/engine/CaseHubRuntime.java` — add typed signal default method
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubRuntimeImpl.java` — override typed signal, inject `CaseDefinitionRegistry`, validate + publish
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubReactor.java` — add `signalTyped()` method
- Test: `runtime/src/test/java/io/casehub/engine/internal/engine/TypedSignalValidationTest.java`

**Interfaces:**
- Consumes: `SignalType<T>` (Task 1), `BridgeResolver.resolveByType()` (Task 2), `TypedSignalReceivedEvent` (Task 3), `CaseDefinitionRegistry`, `CaseInstanceCache`
- Produces: `CaseHubRuntime.signal(UUID, SignalType<T>, T)` — typed signal API

- [ ] **Step 1: Write validation tests for CaseHubRuntimeImpl**

```java
package io.casehub.engine.internal.engine;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.SignalType;
import io.casehub.api.model.SignalRejectedException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedSignalValidationTest {

  record Payment(String id) {}
  record Alert(String msg) {}

  @Test
  void typedSignal_declaredName_accepted() {
    // Integration test — will need CaseHubRuntime with declared signals
    // Test that signal(caseId, SignalType.of("payment", Payment.class), payload) succeeds
    // when definition declares SignalType.of("payment", Payment.class)
  }

  @Test
  void typedSignal_undeclaredName_rejected() {
    // Test that signal with name not in declared signals throws SignalRejectedException
  }

  @Test
  void typedSignal_wrongPayloadType_rejected() {
    // Definition declares SignalType.of("payment", Payment.class)
    // Caller sends SignalType.of("payment", Alert.class)
    // Should throw SignalRejectedException
  }

  @Test
  void typedSignal_nullPayload_rejected() {
    // Should throw immediately — null payload on typed signal is meaningless
  }

  @Test
  void typedSignal_noSignalsDeclared_accepted() {
    // Backward compat: definition without declared signals accepts all typed signals
  }

  @Test
  void untypedSignal_withDeclaredSignals_stillAccepted() {
    // Untyped path signal(caseId, path, value) is never validated against declarations
  }
}
```

These are skeleton tests — the actual implementation will be `@QuarkusTest` integration tests in a later step. For the unit test, we test the validation logic directly.

- [ ] **Step 2: Add typed signal default method to CaseHubRuntime**

Use `ide_insert_member` to add after the `signalAndAwaitSync` method:

```java
default <T> CompletionStage<Void> signal(UUID caseId, SignalType<T> signalType, T payload) {
  throw new UnsupportedOperationException("Typed signals not supported by this runtime");
}
```

- [ ] **Step 3: Add signalTyped to CaseHubReactor**

Use `ide_insert_member` to add after `signalBulk`:

```java
Uni<Void> signalTyped(UUID caseId, String signalName, Object payload,
    Class<?> payloadType, String payloadTypeName) {
  String tenancyId = requireInstance(caseId).tenancyId;
  return eventBus
      .<Void>request(
          TYPED_SIGNAL_RECEIVED,
          new TypedSignalReceivedEvent(
              caseId, signalName, payload, payloadType, payloadTypeName, tenancyId))
      .replaceWithVoid();
}
```

- [ ] **Step 4: Override typed signal in CaseHubRuntimeImpl**

Inject `CaseDefinitionRegistry` (if not already injected). Add override using `ide_insert_member`:

```java
@Override
public <T> CompletionStage<Void> signal(UUID caseId, SignalType<T> signalType, T payload) {
  Objects.requireNonNull(payload, "Typed signal payload must not be null");
  CaseInstance instance = caseInstanceCache.get(caseId);
  if (instance == null) {
    throw new IllegalArgumentException("CaseInstance not found: " + caseId);
  }
  CaseMetaModel meta = instance.getCaseMetaModel();
  if (meta != null) {
    CaseDefinition definition = caseDefinitionRegistry.getCaseDefinition(meta);
    if (definition != null && !definition.getSignals().isEmpty()) {
      var declared = definition.getSignals().stream()
          .filter(s -> s.name().equals(signalType.name()))
          .findFirst()
          .orElse(null);
      if (declared == null) {
        throw new SignalRejectedException(
            "Signal '" + signalType.name() + "' not declared on definition " + meta.getName());
      }
      if (!declared.payloadType().equals(signalType.payloadType())) {
        throw new SignalRejectedException(
            "Signal '" + signalType.name() + "' declared with type "
            + declared.payloadType().getName() + " but received " + signalType.payloadType().getName());
      }
    }
  }
  return reactor.signalTyped(
      caseId, signalType.name(), payload, signalType.payloadType(),
      signalType.payloadType().getName()).subscribeAsCompletionStage();
}
```

- [ ] **Step 5: Run tests — verify pass**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=TypedSignalValidationTest -q
```

- [ ] **Step 6: Commit**

```bash
git add -u
git add runtime/src/test/java/io/casehub/engine/internal/engine/TypedSignalValidationTest.java
git commit -m "feat(#691): CaseHubRuntime.signal(SignalType) — typed signal API with validation

Validates signal name and payload type against CaseDefinition.signals.
Null payload rejected. Untyped path unchanged. CaseHubReactor publishes
TypedSignalReceivedEvent on the event bus.

Refs #691"
```

---

### Task 5: SignalReceivedEventHandler — typed signal handler

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/SignalReceivedEventHandler.java` — add `onTypedSignalReceived` handler
- Test: `runtime/src/test/java/io/casehub/engine/internal/engine/handler/TypedSignalHandlerTest.java`

**Interfaces:**
- Consumes: `TypedSignalReceivedEvent` (Task 3), `BridgeResolver.resolveByType()` (Task 2)
- Produces: Signal payload written to working layer at `.signals.{signalName}`, EventLog entry with `signalTypeName` and `payloadType` metadata, `CONTEXT_CHANGED` published

- [ ] **Step 1: Write typed signal handler test**

A `@QuarkusTest` that sends a typed signal through the full pipeline and asserts:
- Payload appears at `.signals.{signalName}` in the working layer
- EventLog entry has `signalTypeName` in metadata
- `CONTEXT_CHANGED` fires (binding evaluates)

```java
// TypedSignalHandlerTest extends a CaseHub with declared signals
// Uses Awaitility to await context update
```

- [ ] **Step 2: Add handler method to SignalReceivedEventHandler**

Inject `BridgeResolver`. Add `@ConsumeEvent(TYPED_SIGNAL_RECEIVED, blocking = true)`:

```java
@ConsumeEvent(value = EventBusAddresses.TYPED_SIGNAL_RECEIVED, blocking = true)
public Uni<Void> onTypedSignalReceived(TypedSignalReceivedEvent event) {
  // 1. Resolve CaseInstance
  // 2. Write payload to .signals.{signalName} in working layer
  // 3. Resolve bridge via BridgeResolver.resolveByType(event.payloadType())
  // 4. Serialise for EventLog: bridge.serialise(payload) → metadata
  // 5. Write EventLog entry with signalTypeName, payloadType in metadata
  // 6. Publish CONTEXT_CHANGED
}
```

- [ ] **Step 3: Run test — verify pass**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=TypedSignalHandlerTest -q
```

- [ ] **Step 4: Commit**

```bash
git add -u
git add runtime/src/test/java/io/casehub/engine/internal/engine/handler/TypedSignalHandlerTest.java
git commit -m "feat(#691): SignalReceivedEventHandler — typed signal handler

Writes typed payload to .signals.{signalName} in working layer.
Serialises via bridge for EventLog storage only. Publishes CONTEXT_CHANGED.

Refs #691"
```

---

### Task 6: SubCaseMapping sealed interface

**Files:**
- Create: `api/src/main/java/io/casehub/api/model/SubCaseMapping.java`
- Modify: `api/src/main/java/io/casehub/api/model/SubCase.java` — change `inputMapping`/`outputMapping` from `String` to `SubCaseMapping`, add String overload on builder for backward compat
- Test: `api/src/test/java/io/casehub/api/model/SubCaseMappingTest.java`

**Interfaces:**
- Produces: `SubCaseMapping` sealed interface with `Expression(String)` and `Lambda(Function<CaseContext, Object>)` permits. `SubCase.inputMapping(): SubCaseMapping`, `SubCase.outputMapping(): SubCaseMapping`, `SubCase.Builder.inputMapping(String)` (backward compat), `SubCase.Builder.inputMapping(SubCaseMapping)`.

- [ ] **Step 1: Write SubCaseMapping tests**

```java
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.context.CaseContext;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SubCaseMappingTest {

  @Test
  void expression_wrapsString() {
    SubCaseMapping mapping = SubCaseMapping.of("{ id: .caseId }");
    assertThat(mapping).isInstanceOf(SubCaseMapping.Expression.class);
    assertThat(((SubCaseMapping.Expression) mapping).expression())
        .isEqualTo("{ id: .caseId }");
  }

  @Test
  void lambda_wrapsFunction() {
    Function<CaseContext, Object> fn = ctx -> Map.of("x", "y");
    SubCaseMapping mapping = SubCaseMapping.of(fn);
    assertThat(mapping).isInstanceOf(SubCaseMapping.Lambda.class);
    assertThat(((SubCaseMapping.Lambda) mapping).fn()).isSameAs(fn);
  }

  @Test
  void expression_nullString_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of((String) null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void expression_blankString_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lambda_nullFunction_throws() {
    assertThatThrownBy(() -> SubCaseMapping.of((Function<CaseContext, Object>) null))
        .isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run tests — verify fail**

```bash
mvn test -pl api -Dtest=SubCaseMappingTest -q
```

- [ ] **Step 3: Implement SubCaseMapping**

Create `api/src/main/java/io/casehub/api/model/SubCaseMapping.java`:

```java
package io.casehub.api.model;

import io.casehub.api.context.CaseContext;
import java.util.Objects;
import java.util.function.Function;

public sealed interface SubCaseMapping
    permits SubCaseMapping.Expression, SubCaseMapping.Lambda {

  record Expression(String expression) implements SubCaseMapping {
    public Expression {
      Objects.requireNonNull(expression, "expression");
      if (expression.isBlank()) throw new IllegalArgumentException("expression must not be blank");
    }
  }

  record Lambda(Function<CaseContext, Object> fn) implements SubCaseMapping {
    public Lambda {
      Objects.requireNonNull(fn, "fn");
    }
  }

  static SubCaseMapping of(String expression) {
    return new Expression(expression);
  }

  static SubCaseMapping of(Function<CaseContext, Object> fn) {
    return new Lambda(fn);
  }
}
```

- [ ] **Step 4: Change SubCase.inputMapping/outputMapping to SubCaseMapping**

Use `ide_edit_member` to change the fields:
- `private final String inputMapping` → `private final SubCaseMapping inputMapping`
- `private final String outputMapping` → `private final SubCaseMapping outputMapping`

Change accessor return types:
- `public String inputMapping()` → `public SubCaseMapping inputMapping()`
- `public String outputMapping()` → `public SubCaseMapping outputMapping()`

Change Builder fields and add overloaded methods:
- `private String inputMapping` → `private SubCaseMapping inputMapping`
- Keep `public Builder inputMapping(String v)` — wrap with `SubCaseMapping.of(v)` for backward compat
- Add `public Builder inputMapping(SubCaseMapping v)` — direct assignment
- Same for outputMapping

Change constructor mapping: `this.inputMapping = b.inputMapping != null ? b.inputMapping : SubCaseMapping.of(".");`

- [ ] **Step 5: Fix all compilation errors from callers**

Use `ide_diagnostics` to find compilation errors. Key call sites:
- `CaseContextChangedEventHandler.publishSubCaseSchedule()` — calls `subCase.inputMapping()` expecting `String`, now gets `SubCaseMapping`
- `CaseDefinitionYamlMapper` — passes `String` to `inputMapping()`
- `SubCaseExecutionHandler` — uses `subCase.inputMapping()` indirectly
- `SubCaseCompletionService` — reads outputMapping from EventLog as String
- Tests: `SubCaseTest`, `SubCaseIntegrationTest`, etc.

Fix each caller — the String overload on the builder handles most test cases. Runtime callers need the dispatch logic (Task 7).

- [ ] **Step 6: Run tests — verify pass**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -q
```

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/SubCaseMapping.java api/src/test/java/io/casehub/api/model/SubCaseMappingTest.java
git add -u
git commit -m "feat(#690): SubCaseMapping sealed interface — Expression + Lambda

SubCase.inputMapping/outputMapping change from String to SubCaseMapping.
String builder overload preserved for backward compatibility.

Refs #690"
```

---

### Task 7: SubCase dispatch — expression-engine-aware input mapping

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java` — refactor `publishSubCaseSchedule()` to dispatch on `SubCaseMapping` type
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/SubCaseScheduleEvent.java` — change `childInitialContext` from `Map<String, Object>` to `Object`, add `contextBridgeType`
- Test: `runtime/src/test/java/io/casehub/engine/internal/engine/handler/SubCaseInputMappingTest.java`

**Interfaces:**
- Consumes: `SubCaseMapping` (Task 6), `ExpressionEngineRegistry.transform()`, `JQExpressionEvaluator`
- Produces: Updated `SubCaseScheduleEvent(CaseInstance, SubCase, Object, String, String)`, refactored `publishSubCaseSchedule()`

- [ ] **Step 1: Write SubCase input mapping dispatch tests**

```java
// Test Expression path: JQ string → ExpressionEngineRegistry → Map result
// Test Lambda path: function called directly → Object result
// Test Expression failure: invalid JQ → PlanItem faulted (not silent Map.of())
```

- [ ] **Step 2: Change SubCaseScheduleEvent**

Use `ide_edit_member` to change the record:

```java
public record SubCaseScheduleEvent(
    CaseInstance parentInstance,
    SubCase subCase,
    Object childInitialContext,
    String contextBridgeType,
    String bindingName) {}
```

Fix all callers (the constructor signature changed — add `null` for `contextBridgeType` at existing call sites).

- [ ] **Step 3: Refactor publishSubCaseSchedule**

Replace `evalJqAsMap()` call with `SubCaseMapping` dispatch:

```java
private Uni<Void> publishSubCaseSchedule(
    final CaseInstance caseInstance,
    final SubCase subCase,
    final String bindingName) {

  Object childContext;
  SubCaseMapping mapping = subCase.inputMapping();
  switch (mapping) {
    case SubCaseMapping.Expression expr -> {
      var evaluator = new JQExpressionEvaluator(expr.expression());
      var results = expressionEngineRegistry.transform(
          evaluator, caseInstance.getCaseContext().layer(ContextLayer.WORKING).asJsonNode());
      if (results == null || results.isEmpty()) {
        LOG.errorf("SubCase inputMapping produced empty result for binding '%s' on case %s",
            bindingName, caseInstance.getUuid());
        faultPlanItem(caseInstance, bindingName);
        return Uni.createFrom().voidItem();
      }
      childContext = MAPPER.convertValue(results.get(0), MAP_TYPE);
    }
    case SubCaseMapping.Lambda lambda -> {
      try {
        childContext = lambda.fn().apply(caseInstance.getCaseContext());
      } catch (Exception e) {
        LOG.errorf(e, "SubCase inputMapping lambda failed for binding '%s' on case %s",
            bindingName, caseInstance.getUuid());
        faultPlanItem(caseInstance, bindingName);
        return Uni.createFrom().voidItem();
      }
    }
  }

  eventBus.publish(EventBusAddresses.SUBCASE_SCHEDULE,
      new SubCaseScheduleEvent(caseInstance, subCase, childContext, null, bindingName));
  return Uni.createFrom().voidItem();
}
```

- [ ] **Step 4: Run tests — verify pass**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=SubCaseInputMappingTest -q
```

- [ ] **Step 5: Run full blackboard + runtime suite — non-regression**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime,casehub-blackboard -q
```

- [ ] **Step 6: Commit**

```bash
git add -u
git add runtime/src/test/java/io/casehub/engine/internal/engine/handler/SubCaseInputMappingTest.java
git commit -m "feat(#690): SubCase input mapping — expression-engine-aware dispatch

publishSubCaseSchedule() dispatches on SubCaseMapping type: Expression
goes through ExpressionEngineRegistry.transform(), Lambda calls directly.
Input mapping failure faults the PlanItem (not silent Map.of()).
SubCaseScheduleEvent carries Object childInitialContext.

Refs #690"
```

---

### Task 8: SubCase output mapping — expression-engine-aware + Lambda recovery

**Files:**
- Modify: `blackboard/src/main/java/io/casehub/blackboard/subcase/SubCaseCompletionService.java` — refactor `applyOutputMapping()` and `applyOutputMappingToParent()` to dispatch on `SubCaseMapping` type, inject `CaseDefinitionRegistry` for Lambda recovery
- Modify: `blackboard/src/main/java/io/casehub/blackboard/subcase/SubCaseExecutionHandler.java` — store `bindingName` in SUBCASE_STARTED EventLog metadata
- Test: `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseOutputMappingTest.java`

**Interfaces:**
- Consumes: `SubCaseMapping` (Task 6), `CaseDefinitionRegistry`, `ExpressionEngineRegistry.transform()`
- Produces: Refactored output mapping path with Lambda recovery via binding name lookup

- [ ] **Step 1: Write output mapping tests**

```java
// Test Expression output: JQ against child working layer → parent context updated
// Test Lambda output: function called → parent context updated
// Test Lambda recovery: reads bindingName from EventLog metadata → looks up definition → finds Lambda
// Test output mapping failure: logged, parent continues (not blocking)
```

- [ ] **Step 2: Store bindingName in SubCaseExecutionHandler EventLog metadata**

In `handleUngrouped()` and `handleGrouped()`, add `bindingName` to the SUBCASE_STARTED EventLog metadata alongside `outputMapping`, `childCaseId`, `waitForCompletion`.

- [ ] **Step 3: Refactor applyOutputMapping in SubCaseCompletionService**

Inject `CaseDefinitionRegistry`. Change `applyOutputMapping()`:

```java
private Map<String, Object> applyOutputMapping(
    EventLog startedEntry, UUID childCaseId, UUID parentCaseId) {
  String bindingName = startedEntry.getMetadata().has("bindingName")
      ? startedEntry.getMetadata().get("bindingName").asText()
      : null;

  // Try Expression path first (string in metadata)
  String outputMappingExpr = startedEntry.getMetadata().has("outputMapping")
      ? startedEntry.getMetadata().get("outputMapping").asText()
      : null;

  SubCaseMapping mapping;
  if (outputMappingExpr != null) {
    mapping = SubCaseMapping.of(outputMappingExpr);
  } else if (bindingName != null) {
    // Lambda recovery: look up definition → find binding → get SubCase → get outputMapping
    mapping = lookupMappingFromDefinition(parentCaseId, bindingName);
  } else {
    return null;
  }

  if (mapping == null) return null;

  CaseInstance parent = caseInstanceCache.get(parentCaseId);
  if (parent == null) return null;

  return applyMappingToParent(childCaseId, parent, mapping);
}
```

- [ ] **Step 4: Run tests — verify pass**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl casehub-blackboard -Dtest=SubCaseOutputMappingTest -q
```

- [ ] **Step 5: Full blackboard regression**

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl casehub-blackboard -q
```

- [ ] **Step 6: Commit**

```bash
git add -u
git add blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseOutputMappingTest.java
git commit -m "feat(#690): SubCase output mapping — expression-engine-aware + Lambda recovery

applyOutputMapping dispatches on SubCaseMapping type. Lambda recovery reads
bindingName from EventLog metadata, looks up CaseDefinition, finds the
SubCase mapping. SubCaseExecutionHandler stores bindingName in metadata.

Refs #690"
```

---

### Task 9: YAML schema + CaseDefinitionYamlMapper — signals and SubCaseMapping

**Files:**
- Modify: `api/src/main/resources/schema/CaseDefinition.yaml` — add `signals` array with `name` and `contextType`
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` — parse signals, wrap SubCase mappings in `SubCaseMapping.Expression`
- Test: `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java` — add signal parsing tests

**Interfaces:**
- Consumes: `SignalType` (Task 1), `SubCaseMapping.Expression` (Task 6)
- Produces: YAML `signals:` array parsed to `List<SignalType<?>>` on `CaseDefinition`

- [ ] **Step 1: Write YAML signal parsing tests**

```java
// Test: YAML with signals array → CaseDefinition.getSignals() populated
// Test: YAML without signals → getSignals() returns empty list
// Test: YAML with invalid contextType → fail-fast at parse time
```

- [ ] **Step 2: Update YAML schema**

Add `signals` to `CaseDefinition.yaml`:

```yaml
signals:
  type: array
  items:
    type: object
    properties:
      name:
        type: string
      contextType:
        type: string
    required: [name, contextType]
```

- [ ] **Step 3: Update CaseDefinitionYamlMapper**

Parse `signals` list → create `SignalType.of(name, Class.forName(contextType))`.
Wrap SubCase `inputMapping`/`outputMapping` strings in `SubCaseMapping.Expression`.

- [ ] **Step 4: Run tests — verify pass**

```bash
mvn install -DskipTests -q && mvn test -pl api -Dtest=CaseDefinitionYamlMapperTest -q
```

- [ ] **Step 5: Commit**

```bash
git add -u
git commit -m "feat(#691, #690): YAML schema + mapper — signals array and SubCaseMapping wrapping

CaseDefinitionYamlMapper parses signals: array with name + contextType.
SubCase inputMapping/outputMapping strings wrapped in SubCaseMapping.Expression.

Refs #691, #690"
```

---

### Task 10: Integration tests — end-to-end typed signal and SubCase

**Files:**
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/TypedSignalIntegrationTest.java`
- Create: `blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseLambdaMappingIntegrationTest.java`

**Interfaces:**
- Consumes: All previous tasks
- Produces: End-to-end integration test coverage

- [ ] **Step 1: TypedSignalIntegrationTest**

`@QuarkusTest` with a `CaseHub` subclass declaring:
- `SignalType.of("payment", PaymentEvent.class)` signal
- A binding that triggers on `.signals.payment`
- A worker that reads the typed signal data

Test: send typed signal → binding fires → worker receives context with signal data → case completes.

- [ ] **Step 2: SubCaseLambdaMappingIntegrationTest**

`@QuarkusTest` with a parent `CaseHub` spawning a child via `SubCase` with lambda input mapping.

Test: parent triggers → lambda mapping evaluates → child starts with typed context → child completes → output mapping (lambda) returns data to parent.

- [ ] **Step 3: SerializationDetectingBridge contract test**

Test that on internal paths (same-JVM signal, same-JVM SubCase), `bridge.serialise()` and `bridge.deserialise()` are NEVER called on the transfer path.

- [ ] **Step 4: Run full test suite**

```bash
mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api,common,runtime,casehub-blackboard -q
```

- [ ] **Step 5: Commit**

```bash
git add runtime/src/test/java/io/casehub/engine/internal/engine/TypedSignalIntegrationTest.java blackboard/src/test/java/io/casehub/blackboard/subcase/SubCaseLambdaMappingIntegrationTest.java
git commit -m "test(#691, #690): end-to-end integration tests — typed signal + SubCase lambda mapping

TypedSignalIntegrationTest: full pipeline from signal() to worker execution.
SubCaseLambdaMappingIntegrationTest: lambda input/output mapping through
SubCase boundary. SerializationDetectingBridge verifies no serialisation
on internal transfer paths.

Refs #691, #690"
```

---

### Task 11: CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md` — add Signal and SubCase bridge documentation

**Interfaces:**
- Consumes: All previous tasks (documenting what was built)

- [ ] **Step 1: Add SignalType and SubCaseMapping documentation to CLAUDE.md**

Add sections covering:
- `SignalType<T>` record and `CaseDefinition.signals`
- `CaseHubRuntime.signal(UUID, SignalType<T>, T)` typed signal API
- `SubCaseMapping` sealed interface (Expression + Lambda)
- Serialisation boundary rule for signals and SubCase
- `BridgeResolver.resolveByType()` as canonical method

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(#691, #690): CLAUDE.md — SignalType, SubCaseMapping, typed boundaries

Refs #691, #690"
```
