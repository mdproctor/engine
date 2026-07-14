# Wire CaseContextStoreFactory through CaseHubRuntimeImpl

**Date:** 2026-07-14
**Issue:** casehubio/engine#725
**Depends on:** casehubio/engine#419 (landed)
**Design source:** [CaseContextStore SPI design](2026-07-13-case-context-store-design.md) — §Production site migration, §Recovery Model, §CaseDefinition Integration

## Summary

Complete the CaseContextStoreFactory wiring gap: `CaseHubRuntimeImpl.startCase()` resolves
the factory from `CaseDefinition.getContextStoreFactory()` via `StrategyResolver` instead
of hardcoding `InMemoryCaseContextStoreFactory`.

## Current State

`CaseContextStoreFactory` SPI (engine#419) is complete:
- Interface in `api/context/`
- `InMemoryCaseContextStoreFactory` (`@DefaultBean @ApplicationScoped`) in `runtime/internal/context/`
- `CaseContextImpl(CaseContextStoreFactory, UUID)` constructor
- `EngineStrategyResolver` discovers `CaseContextStoreFactory` beans
- `CaseDefinition.contextStoreFactory` field (nullable String, builder support)

**Gap:** `CaseHubRuntimeImpl.startCase()` creates `new CaseContextImpl(toContextMap(inputData))`
which uses `InMemoryCaseContextStoreFactory.INSTANCE`. It never consults the definition's
`contextStoreFactory` field or `StrategyResolver`.

## Design

### 1. Inject StrategyResolver into CaseHubRuntimeImpl

```java
@Inject StrategyResolver strategyResolver;
```

### 2. Generate UUID early

Currently UUID is generated inside `CaseHubReactor.buildInstance()` (implicitly, via
`CaseInstance` construction). The factory-aware `CaseContextImpl` constructor requires
the caseId at creation time (stores use it as a key).

Generate UUID at the top of each `startCase()` overload and thread it through to the
reactor so `buildInstance()` uses the same UUID for the `CaseInstance`.

### 3. Resolve factory and create context

```java
CaseContextStoreFactory factory = strategyResolver.resolve(
    CaseContextStoreFactory.class, definition.getContextStoreFactory());
UUID caseId = UUID.randomUUID();
var context = new CaseContextImpl(factory, caseId);
Map<String, Object> inputMap = toContextMap(inputData);
if (!inputMap.isEmpty()) {
    context.setAll(inputMap);
}
```

No new constructors needed. The existing `(factory, uuid)` constructor creates empty
layers via `initBuiltinLayers()`. `setAll()` populates the working layer.

### 4. Thread UUID through reactor

`CaseHubReactor.startCase()` gains a `UUID caseId` parameter. `buildInstance()` uses this
UUID instead of generating a new one. All five `startCase` overloads and `startCaseInternal`
carry the UUID.

### 5. YAML mapping

`CaseDefinitionYamlMapper` maps the factory under a `context:` section, consistent with the
prior spec and issue #725 scope item 5:

```yaml
context:
  storeFactory: auditing
```

Read from the raw YAML `JsonNode` (same pattern as `semanticData`), not from the generated
schema class — avoids a JSON schema update for a single string field:

```java
JsonNode contextNode = rawNode.get("context");
if (contextNode != null && contextNode.has("storeFactory")) {
    def.setContextStoreFactory(contextNode.get("storeFactory").asText());
}
```

Maps to `CaseDefinition.Builder.contextStoreFactory(String)`.

### 6. SubCase context creation

`SubCaseExecutionHandler` delegates child case creation to `CaseHubRuntime.startCase()` — it
does not create `CaseContextImpl` directly. The fix to `CaseHubRuntimeImpl.startCase()` in
§1–§3 automatically handles subcases. No change to `SubCaseExecutionHandler` is needed.

### 7. Scope boundary — recovery path

`DefaultWorkerExecutionRecoveryService.rebuildStateContext()` creates `new CaseContextImpl()`
and uses `CaseContextImpl.fromLayerDocument()` — both hardcode `InMemoryCaseContextStoreFactory`.
The prior spec (§Recovery Model) designed factory-aware recovery using `isDurable()` to branch
between `loadStore()` (durable) and EventLog replay (volatile).

**This spec does not address recovery.** The recovery path is a separate concern from startCase
wiring. This spec is safe for all currently existing factories (volatile/in-memory), where the
existing EventLog replay recovery is correct. A follow-up issue must be filed for recovery path
migration before any durable `CaseContextStoreFactory` implementation is deployed.

**Code-level enforcement:** the factory resolution in §3 rejects durable factories until
recovery wiring is complete:

```java
CaseContextStoreFactory factory = strategyResolver.resolve(
    CaseContextStoreFactory.class, definition.getContextStoreFactory());
if (factory.isDurable()) {
    throw new UnsupportedOperationException(
        "CaseContextStoreFactory '" + factory.id() + "' reports isDurable()=true but "
        + "recovery path is not yet wired — durable factories will silently lose case "
        + "state on JVM restart. Implement recovery migration (see prior spec §Recovery "
        + "Model) before deploying durable factories.");
}
```

This guard is removed by the recovery follow-up issue. An exception is correct here —
a durable factory without recovery wiring is a data-loss bug, not a degraded-mode
situation. A `LOG.warn` would allow silent deployment of a broken configuration.

### 8. Internal context creation — snapshot() and fromLayerDocument()

`CaseContextImpl.snapshot()` and `CaseContextImpl.fromLayerDocument()` create `new CaseContextImpl()`
with the in-memory factory. This is intentional — snapshots are detached copies that do not need
durable stores, and `fromLayerDocument()` is the EventLog replay path for volatile factories.
The prior spec (§Production site migration, line 691) confirms: "Internal — uses in-memory factory
(detached copies)." No change needed.

## Impact

- `CaseHubRuntimeImpl` — inject StrategyResolver, change all 5 startCase overloads
- `CaseHubReactor` — add UUID parameter to startCase methods and startCaseInternal
- `CaseHubReactor.buildInstance` — use provided UUID instead of generating one
- `CaseDefinitionYamlMapper` — add `context.storeFactory` mapping (raw-node reading)
- `SubCaseExecutionHandler` — **no change needed** (delegates through `CaseHubRuntime.startCase()`)
- `DefaultExpressionEngineRegistry` — **no change needed** (ephemeral evaluation context, stays in-memory)
- `DefaultWorkerExecutionRecoveryService` — **deferred** (follow-up issue for recovery path migration)

## Test Plan

### Unit tests

1. **Default factory**: startCase with no contextStoreFactory → InMemoryCaseContextStoreFactory used
2. **Named factory**: startCase with contextStoreFactory → correct factory resolved and used
3. **UUID threading**: caseId generated in startCase matches the CaseInstance UUID
4. **Input data populated**: initial data reaches the working layer via setAll()
5. **Empty input**: null/empty inputData creates empty working layer (no NPE)

### Integration test

6. **Custom factory end-to-end**: CaseDefinition with `contextStoreFactory: "auditing"` →
   case starts with the auditing factory's stores. Verify via a recording factory that
   logs createStore calls with correct layerName and caseId.

### SubCase factory verification

7. **SubCase factory delegation**: parent case spawns child case whose definition specifies
   `context.storeFactory: "auditing"` → verify child case uses auditing factory, not parent's
   factory or default. Confirms `SubCaseExecutionHandler` passes `childDefinition` to
   `CaseHubRuntime.startCase()`.

### YAML test

8. **YAML parsing**: `context:\n  storeFactory: "auditing"` in YAML → CaseDefinition.getContextStoreFactory() == "auditing"
9. **YAML absent**: no context.storeFactory key → getContextStoreFactory() == null (default factory used)
