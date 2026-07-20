# ContextBridge Protocol Completion — Design Spec

Covers engine#692 (Connector boundary), engine#740 (DataRef\<T\>), engine#742 (ActionGate resolutionTypeName).

## Execution Order

1. **#742** — ActionGate resolutionTypeName threading (smallest, mechanical)
2. **#692** — Connector boundary wiring + InboundSignalBridge (medium, new component)
3. **#740** — DataRef\<T\> + DataRefResolver (medium, new concept)

Each is independent — no implementation dependency between them.

---

## 1. ActionGate resolutionTypeName Threading (#742)

### Problem

When a worker declares a `PlannedAction` and the classifier returns `GateRequired`, a gate WorkItem is created. On approval, `ActionGateApprovedEvent` carries `workItemResolution` (raw String) but no type information. The handler cannot validate the resolution against a declared type via ContextBridge.

### Design

Thread `resolutionType` from the classifier's `GateRequired` decision through the full gate lifecycle. The classifier knows what approval format it expects — most expect untyped notes (null), but domain-specific classifiers may require structured approval (e.g., a compliance sign-off POJO).

### Changes

| Type | Field | Form | Rationale |
|------|-------|------|-----------|
| `RiskDecision.GateRequired` | `@Nullable Class<?> resolutionType` | Class | API-level — classifier declares the expected type |
| `PendingActionGate` | `@Nullable Class<?> resolutionType` | Class | In-memory record on CaseInstance, holds the class directly |
| `ActionGateScheduleEvent` | `@Nullable String resolutionTypeName` | String | Event transport to work repo engine-adapter |
| `ActionGateApprovedEvent` | `@Nullable String resolutionTypeName` | String | Event transport back from work repo |

`Class<?>` at API boundaries where the classifier knows the type at compile time. `String` at event transport boundaries (events must be serializable across module boundaries).

### Validation and Consumption at Approval

`ActionGateApprovedHandler.onActionGateApproved()`:
- When `resolutionTypeName` is non-null: resolve via `BridgeResolver.resolveByTypeNameStrict(resolutionTypeName)`, then call `bridge.deserialise()` to validate and deserialize the `workItemResolution` payload. The deserialized resolution value is included in the `actionGateApproved` context entry under the `resolution` key, making it accessible to downstream workers via bindings.
- Validation failure: log error and discard the gate (deferred output not applied, case stalls — same failure semantics as a corrupted WorkItem resolution).
- When `resolutionTypeName` is null: no validation, raw `workItemResolution` passed through as-is (current behavior). The `resolution` key is set to the raw string value.

The context write becomes:
```java
instance.getCaseContext().set("actionGateApproved", Map.of(
    "actionType", gate.plannedAction().actionType(),
    "workerId", gate.workerId(),
    "approvedBy", event.approvedBy() != null ? event.approvedBy() : "unknown",
    "gateId", gate.gateId(),
    "resolution", deserializedResolution));
```

This ensures the classifier's structured resolution type is not validated and then discarded — the typed approval data reaches downstream workers through the same context path as all other gate metadata.

### Threading Path

```
ActionRiskClassifier.classify()
  → RiskDecision.GateRequired(resolutionType=ComplianceSignOff.class)
  → WorkflowExecutionCompletedHandler.handleGate()
    → PendingActionGate(resolutionType=ComplianceSignOff.class)
    → ActionGateScheduleEvent(resolutionTypeName="...ComplianceSignOff")
    → [work repo: ActionGateWorkItemHandler creates WorkItem with resolutionTypeName]
    → [human approves]
    → ActionGateApprovedEvent(resolutionTypeName="...ComplianceSignOff")
    → ActionGateApprovedHandler validates via BridgeResolver
```

---

## 2. Connector Boundary Wiring + InboundSignalBridge (#692)

### Problem

`casehub-connectors` provides inbound message reception (Slack, Teams, Discord, email, custom webhooks) and fires `InboundMessage` CDI events. The engine has no observer for these events — inbound connector data cannot reach case signals. The ContextBridge protocol has no presence at the connector boundary.

### Architecture

The connector boundary is NOT a `BindingTarget`. Bindings are reactive (trigger on context changes) and outbound (the case pushes work out). Connectors are inbound — external data arrives. The trigger direction is reversed.

The correct model is an **inbound signal bridge** — analogous in role to the existing `InboundWorkItemBridge` (in `casehub-engine-inbound`) but routing to typed case signals instead of WorkItems. Both bridge external data into engine concepts and live in `casehub-engine-inbound`, but differ architecturally: `InboundWorkItemBridge` uses qhorus `MessageObserver` with SPI-driven `InboundWorkItemPolicy`; `InboundSignalBridge` uses CDI `@ObservesAsync` with declarative `InboundSignalMapping` on `CaseDefinition`.

```
External system
  → casehub-connectors (WebhookInboundConnector)
  → InboundMessage (CDI @ObservesAsync)
  → InboundSignalBridge (new, in casehub-engine-inbound)
    → composite JsonNode from InboundMessage fields
    → correlation JQ → CaseCorrelationResolver → case UUID
    → payload JQ → ContextBridge.deserialise() → typed T
    → CaseHubRuntime.signal(caseId, SignalType<T>, typedPayload)
```

### New Types

#### InboundSignalMapping (engine-api, `io.casehub.api.model`)

Declared on `CaseDefinition`. Maps an inbound connector message to a typed case signal.

```java
public record InboundSignalMapping(
    String signalName,
    String connectorType,
    ExpressionEvaluator correlation,
    ExpressionEvaluator payload,
    @Nullable String correlationResolver
) {}
```

- `signalName` — must match a declared `SignalType` on the same `CaseDefinition`
- `connectorType` — filter: only messages from this connector type (e.g., `"slack"`, `"aml-system"`)
- `correlation` — JQ expression evaluated against the InboundMessage composite; extracts a correlation value
- `payload` — JQ expression evaluated against the InboundMessage composite; extracts the typed payload portion
- `correlationResolver` — strategy ID for `CaseCorrelationResolver` (nullable → default `"uuid"`)

Builder (String convenience methods auto-wrap into `JQExpressionEvaluator`, JQ being the default expression language):
```java
CaseDefinition.builder()
    .signal(SignalType.of("aml-alert", AmlAlert.class))
    .inboundMapping(InboundSignalMapping.builder()
        .signalName("aml-alert")
        .connectorType("aml-system")
        .correlation(".metadata.caseRef")       // auto-wrapped to JQExpressionEvaluator
        .payload(".content | fromjson")          // auto-wrapped to JQExpressionEvaluator
        .correlationResolver("uuid")
        .build())
```

YAML:
```yaml
signals:
  - name: aml-alert
    contextType: io.casehub.aml.AmlAlert

inboundMappings:
  - signal: aml-alert
    connectorType: aml-system
    correlation: '.metadata.caseRef'
    payload: '.content | fromjson'
    correlationResolver: uuid
```

#### CaseCorrelationResolver (engine-api, `io.casehub.api.spi`)

SPI for resolving a correlation value to a case UUID. Follows `NamedStrategy` convention.

```java
public interface CaseCorrelationResolver extends NamedStrategy {
    Uni<UUID> resolve(String correlationValue, String tenancyId);
}
```

Built-in: `UuidCorrelationResolver` (id=`"uuid"`, `@DefaultBean @ApplicationScoped`) — parses the correlation value as a UUID directly. Fails with `IllegalArgumentException` if not a valid UUID.

Resolved via `EngineStrategyResolver` — adding this SPI requires updating `EngineStrategyResolver`'s constructor (same pattern as every other strategy SPI).

#### InboundSignalBridge (casehub-engine-inbound, `io.casehub.engine.inbound`)

`@ApplicationScoped`. Observes `@ObservesAsync InboundMessage`. Processing:

1. Build composite JsonNode from InboundMessage: `{content, connectorType, connectorId, externalSenderId, externalChannelRef, metadata, receivedAt, tenancyId, attachments}`. All fields from `InboundMessage` are included — `tenancyId` is needed for case correlation; `attachments` may be referenced by signal payloads carrying file/media data.
2. Look up matching mappings from the in-memory index by `connectorType` (O(1)).
3. For each matching mapping:
   a. Evaluate `correlation` JQ against the composite → correlation value (String)
   b. Extract `tenancyId` directly from the `InboundMessage` (not from JQ output). Pass both to `CaseCorrelationResolver.resolve(correlationValue, tenancyId)` via `EngineStrategyResolver`.
   c. Resolve case UUID from the correlation result.
   d. **Case activation:** if the case is not in `CaseInstanceCache` (external signals may target persisted but inactive cases), activate it via `CaseHubReactor.activateCase(caseId)` to load from the event store. If the case does not exist or is in a terminal state (COMPLETED, CANCELLED, FAULTED), log a warning and skip this mapping.
   e. Evaluate `payload` JQ against the composite → payload JsonNode
   f. Look up `SignalType` from the definition's signals list by `signalName`
   g. `BridgeResolver.resolveByType(signalType.payloadType())` → `bridge.deserialise(payloadJson)` → typed T
   h. `CaseHubRuntime.signal(caseId, signalType, typedPayload)`
4. Exceptions caught per-mapping — one failed mapping does not block others. Logged with connector context.

Inert when no `InboundSignalMapping`s are registered.

#### Index construction

Builds an in-memory index `Map<String, List<MappingEntry>>` keyed by `connectorType`. The index is populated by observing `CaseDefinitionRegisteredEvent` (CDI event fired by `CaseDefinitionRegistry` after each successful registration). This avoids requiring an iteration API on the registry — new definitions are indexed incrementally as they register.

#### CaseDefinitionRegistry API additions

`CaseDefinitionRegistry` gains:

- `Collection<CaseDefinition> allDefinitions()` — returns all registered definitions. Used for initial index build at `@PostConstruct` time (definitions registered before the bridge starts).
- `CaseDefinitionRegisteredEvent` — CDI event fired by `DefaultCaseDefinitionRegistry` after `registerCaseDefinition()` completes. Carries the registered `CaseDefinition`. Enables incremental index updates without polling.

#### Case activation for external signals

`CaseHubRuntimeImpl.signal(UUID, SignalType, T)` requires the case to be in `CaseInstanceCache`. For externally-triggered signals, the case may exist in the event store but not be loaded. `InboundSignalBridge` handles this explicitly: it checks the cache, and if absent, activates the case via `CaseHubReactor` before calling `signal()`. This avoids changing the general `signal()` contract — internal callers can still assume cases are active. A follow-on issue (engine#TBD) tracks making `signal()` auto-activate, which would simplify all external signal paths.

### CaseDefinition Additions

`CaseDefinition` gains:

- `List<InboundSignalMapping> inboundMappings` (empty by default)
- Builder: `.inboundMapping(InboundSignalMapping)`
- Validation at build time: every `InboundSignalMapping.signalName` must reference a declared `SignalType` in the same definition

`CaseDefinitionYamlMapper` parses the `inboundMappings:` YAML block.

### Dependency

`casehub-engine-inbound/pom.xml` adds `casehub-connectors-core` (compile scope). This module has zero casehubio dependencies — clean dependency. Brings `InboundMessage`, `InboundConnectorTypes` onto the engine-inbound classpath.

### What This Does NOT Include

- No HTTP endpoint creation in the engine (connectors own reception)
- No changes to `casehub-connectors`
- No `ConnectorTarget` binding type
- The architecture spec's YAML projection (`connectors: webhooks: - path: ...` in `2026-07-09-context-bridge-architecture.md` §5 Connectors) is superseded — the engine does not create HTTP endpoints. `inboundMappings` replaces this. Follow-on: update the architecture spec to reflect this change (tracked as engine#TBD).

---

## 3. DataRef\<T\> Linked Data Reference Protocol (#740)

### Problem

The ContextBridge protocol assumes inline data — domain objects are embedded in case context, EventLog entries, and WorkItem payloads. The bridge serialises and deserialises the full object at storage boundaries.

For large data (medical records), sensitive data (PII — GDPR duplication risk), or living data (documents that evolve), the context should store a reference and resolve the object on demand.

Consumers can already write custom bridges that store references and resolve them, but every consumer reinvents it — no standard reference format, no shared resolution infrastructure, no way for the engine to distinguish a reference from inline data.

### Design

Three new types plus BridgeResolver integration.

#### DataRef\<T\> (engine-api, `io.casehub.api.context`)

Standard reference to externally-stored domain data.

```java
public record DataRef<T>(String source, String key, String typeName) {

    public static final String DISCRIMINATOR = "$dataRef";

    public static <T> DataRef<T> of(String source, String key, Class<T> type) {
        return new DataRef<>(source, key, type.getName());
    }

    public static boolean isRef(JsonNode node) {
        return node != null && node.has(DISCRIMINATOR);
    }

    public static DataRef<?> fromJson(JsonNode node) {
        JsonNode ref = node.get(DISCRIMINATOR);
        return new DataRef<>(
            ref.get("source").asText(),
            ref.get("key").asText(),
            ref.get("type").asText());
    }

    public JsonNode toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode ref = mapper.createObjectNode();
        ref.put("source", source);
        ref.put("key", key);
        ref.put("type", typeName);
        root.set(DISCRIMINATOR, ref);
        return root;
    }
}
```

**Security:** `DataRef` stores the type name as a `String`, not a `Class<?>`. No `Class.forName()` call occurs at deserialization time. Type resolution is deferred to `DataRefRegistry.resolve()`, which validates the type name against registered `DataRefResolver` sources before any class loading. This prevents class loading attacks from user-controlled JSON payloads (the `$dataRef.type` field originates from context JSON at storage boundaries).

**Reserved key:** `$dataRef` is a reserved top-level JSON key in the ContextBridge protocol. Domain objects used with ContextBridge must not contain `$dataRef` as a top-level key. The `$` prefix follows the JSON Schema convention for meta-properties (`$ref`, `$id`, `$schema`), reducing collision probability with domain data.

JSON representation — `$dataRef` discriminator makes references unambiguous anywhere in context:

```json
{
  "$dataRef": {
    "source": "document-store",
    "key": "doc-123",
    "type": "io.casehub.clinical.MedicalRecord"
  }
}
```

`source` identifies which resolver handles this reference (follows NamedStrategy id convention). `key` is opaque to the engine — the resolver knows how to interpret it.

#### DataRefResolver (engine-api, `io.casehub.api.spi`)

SPI for resolving references to domain objects.

```java
public interface DataRefResolver extends NamedStrategy {
    <T> T resolve(DataRef<T> ref);
}
```

Blocking SPI. CDI-discovered. Each resolver declares `id()` matching the `source` field on DataRef values it handles. No `@DefaultBean` — if no resolver exists for a source, resolution fails fast.

#### DataRefRegistry (engine-common, `io.casehub.engine.common.internal.context`)

CDI bean that discovers resolvers and routes resolution.

```java
@ApplicationScoped
public class DataRefRegistry {
    @Inject Instance<DataRefResolver> resolvers;

    public <T> T resolve(DataRef<T> ref) {
        return resolvers.stream()
            .filter(r -> r.id().equals(ref.source()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No DataRefResolver for source: " + ref.source()))
            .resolve(ref);
    }
}
```

### BridgeResolver Integration — Deferred Resolution at Execution Time

`BridgeResolver` gains DataRef awareness. The resolution strategy is **deferred**: DataRef references pass through at scheduling time and are resolved at execution time (deserialise path). This design serves three purposes:

1. **Preserves reference semantics in EventLog.** The EventLog stores the `$dataRef` reference, not the resolved object. Large/sensitive data remains external.
2. **Avoids blocking on the event loop.** Scheduling runs on the Vert.x event bus; `DataRefResolver.resolve()` involves external I/O. Execution runs on Quartz worker threads where blocking is safe.
3. **Ensures fresh resolution.** The resolved data reflects the state at execution time, not scheduling time — important for living data (documents that evolve).

```java
public <T> Object initialise(ContextBridge<T> bridge, CaseContext context, JsonNode narrowedInput) {
    if (DataRef.isRef(narrowedInput)) {
        // Pass through: DataRef is stored as a reference in EventLog
        return DataRef.fromJson(narrowedInput);
    }
    return bridge.initialise(context, narrowedInput);
}

@SuppressWarnings("unchecked")
public <T> JsonNode serialise(ContextBridge<T> bridge, Object input) {
    if (input instanceof DataRef<?> ref) {
        // Serialise the reference directly — no bridge involvement
        return ref.toJson(objectMapper);
    }
    return bridge.serialise((T) input);
}

public <T> Object deserialise(ContextBridge<T> bridge, JsonNode payload) {
    if (DataRef.isRef(payload)) {
        // Resolve at execution time — runs on Quartz worker thread
        return dataRefRegistry.resolve(DataRef.fromJson(payload));
    }
    return bridge.deserialise(payload);
}
```

`DataRefResolver.resolve()` is a blocking SPI. This is safe because resolution only occurs in the `deserialise()` path, which runs on Quartz worker threads (`QuartzWorkerExecutionJob`), not on the Vert.x event loop.

### How Data Enters as a DataRef

Workers or signals place DataRef values in context:

```java
.function(input -> WorkerResult.of(Map.of(
    "medicalRecord", DataRef.of("document-store", "doc-123", MedicalRecord.class)
        .toJson(mapper))))
```

When a downstream binding fires and JQ narrows the context to the `medicalRecord` field, at scheduling time BridgeResolver sees the `$dataRef` discriminator and passes the reference through to EventLog. At execution time, `BridgeResolver.deserialise()` resolves it via `DataRefRegistry`, and the worker receives the full `MedicalRecord` object.

### Resolution Timing

Deferred to execution — resolve at `deserialise()` time in `QuartzWorkerExecutionJob`. The bridge receives the fully resolved object, never a proxy or lazy handle. Lazy resolution (proxy-based, resolve on access) is a future optimization deferred until a concrete performance need arises.

**Known limitation — no caching across repeated resolutions:** when the same DataRef appears in context consumed by multiple downstream bindings (e.g., three workers all read `medicalRecord` from shared context), each triggers an independent `DataRefResolver.resolve()` call. Request-scoped caching (keyed by `source + key` within a single case processing cycle) is a future optimization deferred until profiling identifies redundant resolution as a bottleneck.

### What This Branch Delivers

- `DataRef<T>` record in engine-api
- `DataRefResolver` SPI in engine-api
- `DataRefRegistry` in engine-common
- BridgeResolver DataRef awareness (transparent eager resolution)
- Tests with a test resolver

### What This Branch Does NOT Deliver

- Work repo DataRef support (follow-on issue)
- Lazy resolution
- Built-in resolvers beyond test infrastructure

---

## Cross-Cutting Concerns

### EngineStrategyResolver Updates

`EngineStrategyResolver` constructor gains `Instance<CaseCorrelationResolver>` injection for #692. Same pattern as every other strategy SPI addition.

### CLAUDE.md Updates

All three issues add new types and conventions that should be documented in CLAUDE.md sections:
- #742: Update ActionRiskClassifier SPI section with `resolutionType` field
- #692: New section for InboundSignalBridge, InboundSignalMapping, CaseCorrelationResolver
- #740: New section for DataRef, DataRefResolver, DataRefRegistry, BridgeResolver integration

### Test Strategy

| Issue | Test approach |
|-------|--------------|
| #742 | Unit test: `GateRequired` with `resolutionType` threads to `ActionGateApprovedHandler`. Integration test: gate approval with typed resolution validates via bridge. |
| #692 | Unit test: `InboundSignalBridge` processes `InboundMessage` → typed signal. Unit test: `InboundSignalMapping` validation. Integration test: full path from `InboundMessage` to case context update. Unit test: `UuidCorrelationResolver`. |
| #740 | Unit test: `DataRef` serialization/deserialization. Unit test: `DataRefRegistry` resolution. Unit test: `BridgeResolver` transparent DataRef resolution. Integration test: worker output with DataRef → downstream worker receives resolved object. |
