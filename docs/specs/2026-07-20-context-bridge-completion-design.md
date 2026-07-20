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

### Validation at Approval

`ActionGateApprovedHandler.onActionGateApproved()`:
- When `resolutionTypeName` is non-null: resolve via `BridgeResolver.resolveByTypeNameStrict(resolutionTypeName)`, then call `bridge.deserialise()` to validate the `workItemResolution` payload.
- Validation failure: log error and discard the gate (deferred output not applied, case stalls — same failure semantics as a corrupted WorkItem resolution).
- When `resolutionTypeName` is null: no validation, raw `workItemResolution` passed through as-is (current behavior).

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

The correct model is an **inbound signal bridge** — symmetric to the existing `InboundWorkItemBridge` (in `casehub-engine-inbound`) but routing to typed case signals instead of WorkItems.

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

Builder:
```java
CaseDefinition.builder()
    .signal(SignalType.of("aml-alert", AmlAlert.class))
    .inboundMapping(InboundSignalMapping.builder()
        .signalName("aml-alert")
        .connectorType("aml-system")
        .correlation(".metadata.caseRef")
        .payload(".content | fromjson")
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

1. Build composite JsonNode from InboundMessage: `{content, connectorType, connectorId, externalSenderId, externalChannelRef, metadata, receivedAt}`
2. Iterate all `CaseDefinition`s registered in `CaseDefinitionRegistry` that have `inboundMappings` with matching `connectorType`
3. For each matching mapping:
   a. Evaluate `correlation` JQ against the composite → correlation value (String)
   b. Resolve case UUID via `CaseCorrelationResolver` (from `EngineStrategyResolver`)
   c. Evaluate `payload` JQ against the composite → payload JsonNode
   d. Look up `SignalType` from the definition's signals list by `signalName`
   e. `BridgeResolver.resolveByType(signalType.payloadType())` → `bridge.deserialise(payloadJson)` → typed T
   f. `CaseHubRuntime.signal(caseId, signalType, typedPayload)`
4. Exceptions caught per-mapping — one failed mapping does not block others. Logged with connector context.

Inert when no `InboundSignalMapping`s are registered (same pattern as `InboundWorkItemBridge` being inert with no policy).

Performance: builds an in-memory index `Map<String, List<MappingEntry>>` keyed by `connectorType` at startup (listening to `CaseDefinitionRegistry` registrations). Message dispatch is O(1) lookup by connectorType, not iteration over all definitions.

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
- The spec's original YAML projection (`connectors: webhooks: - path: ...`) is superseded — the engine does not create HTTP endpoints. `inboundMappings` replaces this.

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
public record DataRef<T>(String source, String key, Class<T> type) {

    public static final String DISCRIMINATOR = "$dataRef";

    public static <T> DataRef<T> of(String source, String key, Class<T> type) {
        return new DataRef<>(source, key, type);
    }

    public static boolean isRef(JsonNode node) {
        return node != null && node.has(DISCRIMINATOR);
    }

    public static DataRef<?> fromJson(JsonNode node) {
        JsonNode ref = node.get(DISCRIMINATOR);
        String source = ref.get("source").asText();
        String key = ref.get("key").asText();
        String typeName = ref.get("type").asText();
        try {
            return new DataRef<>(source, key, Class.forName(typeName));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("DataRef type not found: " + typeName, e);
        }
    }

    public JsonNode toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode ref = mapper.createObjectNode();
        ref.put("source", source);
        ref.put("key", key);
        ref.put("type", type.getName());
        root.set(DISCRIMINATOR, ref);
        return root;
    }
}
```

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

### BridgeResolver Integration — Transparent Eager Resolution

`BridgeResolver` gains DataRef awareness. Before calling `bridge.initialise()` or `bridge.deserialise()`, it checks whether the input is a DataRef. If so, it resolves eagerly and returns the resolved object. Bridges never see DataRef — they receive the resolved object.

```java
public <T> Object initialise(ContextBridge<T> bridge, CaseContext context, JsonNode narrowedInput) {
    if (DataRef.isRef(narrowedInput)) {
        return dataRefRegistry.resolve(DataRef.fromJson(narrowedInput));
    }
    return bridge.initialise(context, narrowedInput);
}

public <T> Object deserialise(ContextBridge<T> bridge, JsonNode payload) {
    if (DataRef.isRef(payload)) {
        return dataRefRegistry.resolve(DataRef.fromJson(payload));
    }
    return bridge.deserialise(payload);
}
```

### How Data Enters as a DataRef

Workers or signals place DataRef values in context:

```java
.function(input -> WorkerResult.of(Map.of(
    "medicalRecord", DataRef.of("document-store", "doc-123", MedicalRecord.class)
        .toJson(mapper))))
```

When a downstream binding fires and JQ narrows the context to the `medicalRecord` field, BridgeResolver sees the `$dataRef` discriminator, resolves it via `DataRefRegistry`, and the worker receives the full `MedicalRecord` object.

### Resolution Timing

Eager only — resolve at bridge time. The bridge receives the fully resolved object, never a proxy or lazy handle. Lazy resolution (proxy-based, resolve on access) is a future optimization deferred until a concrete performance need arises.

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
