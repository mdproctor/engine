# CaseContext Panels Design

**Issues:** engine#80 (memory stratification), engine#81 (hierarchical blackboard panels)  
**Epic:** engine#445 (full Drools integration — typed blackboard memory)  
**Date:** 2026-06-09  
**Status:** Approved (rev 6)

---

## Overview

`CaseContext` is currently a flat `Map<String, Object>`. Everything — worker output, engine signals, domain initialisation data, execution history — shares one unstructured namespace. This design introduces **panels**: named, policy-aware partitions of `CaseContext` that give structure to the blackboard.

Issue #80 delivers three built-in panels with specific memory semantics (working, semantic, episodic). Issue #81 delivers user-defined panels with panel-scoped change events. Both are designed together because they share the same panel infrastructure; implementation is sequential (#80 first, #81 on top).

---

## Core Abstractions

### ReadablePanel and WritablePanel

Panels are NOT a subtype of `CaseContext`. They are a distinct hierarchy:

```java
// api/context/ReadablePanel.java
public interface ReadablePanel {
    String panelName();
    boolean isReadOnly();

    Object get(String key);
    <T> T getAs(String key, Class<T> type);
    <T> T getOrDefault(String key, T defaultValue);
    boolean contains(String key);
    Set<String> getKeys();
    Map<String, Object> getData();
    Map<String, Object> getAll(String... keys);
    Object getPath(String path);
    String getPathAsString(String path);
    boolean isEmpty();
    int size();
    long getVersion();
    JsonNode asJsonNode();
    ReadablePanel snapshot();
}

// api/context/WritablePanel.java
public interface WritablePanel extends ReadablePanel {
    WritablePanel set(String key, Object value);
    WritablePanel setAll(Map<String, Object> values);
    WritablePanel setPath(String path, Object value);
    WritablePanel remove(String key);
    WritablePanel clear();           // clears this panel only
    WritablePanel merge(ReadablePanel other);
    Object computeIfAbsent(String key, Function<String, Object> mappingFunction);
    Object putIfAbsent(String key, Object value);
    boolean compareAndSet(String key, Object expected, Object newValue);
    WritablePanel update(String key, Function<Object, Object> updateFunction);
    Optional<JsonNode> applyAndDiff(String path, Object value);
    void applyDiff(JsonNode diff);
    JsonNode diff(ReadablePanel other);
}
```

**Why separate hierarchies:** `ContextPanel extends CaseContext` with read-only panels throwing `UnsupportedOperationException` on write methods is an LSP violation. Any method receiving `CaseContext` can silently receive a read-only panel and blow up. A `PanelView` with `set()` has the same problem. The type-level split is the correct resolution: callers of `CaseContext.panel(name)` always receive `ReadablePanel`, which guarantees no surprise on any method they call.

User-defined panels implement `WritablePanel`. No additional interface — the engine imposes no ordering semantics on panels. Panel evaluation ordering is the Drools integration's concern, expressed through its own configuration when designed.

### `CaseContext` interface change

One new method:

```java
ReadablePanel panel(String name);
```

All existing flat methods (`get`, `set`, `asJsonNode`, etc.) are retained as working-panel convenience delegates. `panel(WORKING)` casts to `WritablePanel` internally — the flat API is the primary path for worker writes.

**`asJsonNode()` changes semantics**: returns the full panel document rather than just working data:

```json
{
  "working":  { "result": "done", "score": 42 },
  "semantic": { "domain": "fraud-check", "customerId": "C-123" },
  "episodic": {
    "workers": [{"name": "extractor", "runs": 2, "lastOutcome": "COMPLETED"}],
    "milestones": ["data-ready"],
    "goals": [],
    "memory": [...]
  },
  "inferences": { "featureScore": 0.87 }
}
```

This is the breaking change that restructures JQ expression syntax (see Migration).

**`getVersion()` on root context**: delegates to the working panel's version counter. Each panel maintains its own version independently. Engine writes to the episodic panel do not increment the working panel version and do not interfere with idempotency detection in `applyAndDiff`.

**Multi-panel operation semantics** (explicit decisions for methods that previously operated on the whole flat map):

| Method | Panel-aware behaviour |
|---|---|
| `get(key)`, `set(key, v)` etc. | Delegate to working panel — unchanged for callers |
| `asJsonNode()` | Returns full panel document `{"working":{...},"semantic":{...},...}` |
| `getVersion()` | Returns working panel version |
| `snapshot()` | Snapshots ALL panels; result is a full panel document snapshot |
| `applyAndDiff(path, value)` | Operates on working panel only; patch is working-panel-relative (format unchanged: `/result`, not `/working/result`); this preserves EventLog patch replay correctness |
| `applyDiff(patch)` | Applies working-panel-relative patches to working panel only; does not touch semantic or episodic |
| `diff(other)` | Diffs working panels only |
| `merge(other)` | Merges `other`'s working panel data into this working panel |
| `clear()` | Clears working panel only; never touches semantic or episodic |

**`MapCaseFile` (migration shim)**: Its `snapshot()` currently does `new MapCaseFile(base.getData())` which drops non-working panels via `getData()` delegation. Must be updated to propagate all panels to the returned snapshot. The rest of `MapCaseFile` is unaffected — it uses the flat API which correctly delegates to working.

### `CaseContextImpl` internal restructure

```java
// Before
private final Map<String, Object> data = new LinkedHashMap<>();

// After — internal panels map; each entry is a panel implementation
private final Map<String, WritablePanelImpl> panels = new ConcurrentHashMap<>();
// flat API delegates to panels.get(ContextPanel.WORKING)
```

Each `WritablePanelImpl` holds its own `Map<String, Object> data` + `ReadWriteLock` + `version` counter. Read-only panels (semantic, episodic) are wrapped in `ReadOnlyPanelView` which implements `ReadablePanel` and delegates reads, throwing `UnsupportedOperationException` only if a caller somehow obtains a direct reference — which the type system now prevents for external callers.

New static factory for panel-aware reconstruction:

```java
public static CaseContextImpl fromPanelDocument(JsonNode panelDoc) { ... }
```

Used exclusively by recovery (see below).

### Panel name constants

```java
// api/context/ContextPanel.java  (replaces the ContextPanel extends CaseContext concept)
public final class ContextPanel {
    public static final String WORKING  = "working";
    public static final String SEMANTIC = "semantic";
    public static final String EPISODIC = "episodic";
    private ContextPanel() {}
}
```

---

## Built-in Panels (#80)

### Working panel (`"working"`)

Read-write. The default panel. All existing flat API calls (`context.set("key", value)`, `context.get("key")`) operate here without change. Workers write their output here. The engine writes its signals here (`actionGateRejected`, `workItemEscalated`, `actionGateApproved`, `actionGateExpired`). Milestone tracking data written by `DefaultWorkerExecutionRecoveryService` (`milestones.{name}.lifecycleStatus` etc.) lives here.

### Semantic panel (`"semantic"`)

Read-only. Populated once at case start from two sources merged in order:
1. Case definition `semanticData` block (static defaults for this case type)
2. Call-site `semanticData` parameter on `CaseHubRuntime.startCase()` (instance-specific; overrides definition defaults on conflict)

Marked read-only after initialisation — any attempt to write is prevented by the `ReadablePanel` type returned by `panel(SEMANTIC)`. Workers read it as `.semantic.key` in JQ expressions.

**New YAML field:**
```yaml
semanticData:
  threshold: 0.8
  domain: "fraud-check"
```

**New DSL builder method:**
```java
CaseDefinition.builder()
    .semanticData(Map.of("threshold", 0.8, "domain", "fraud-check"))
```

### Episodic panel (`"episodic"`)

`ReadablePanel` to external callers. Written by the engine only via engine-internal `WritablePanel` access. Two sub-sections:

**Intra-case** (always present): structured summary from `EventLog`.
- At case start: rebuilt from existing `EventLog` entries (handles recovered cases) via `CaseContextImpl.fromPanelDocument()`
- Updated by engine after: worker completes (`WORKER_EXECUTION_FINISHED`), milestone reached, goal reached
- Updates fire `casehub.context.changed.episodic` only — not the full `CONTEXT_CHANGED`
- Structure:
```json
{
  "workers": [
    {"name": "extractor", "runs": 2, "lastOutcome": "COMPLETED", "lastTimestamp": "..."}
  ],
  "milestones": ["data-ready"],
  "goals": []
}
```

**Inter-case** (optional): snapshot from `ReactiveCaseMemoryStore` at case start.
- Present only if: case definition declares `episodic.memory` AND `ReactiveCaseMemoryStore` is non-NoOp
- `ReactiveCaseMemoryStore.query(MemoryQuery): Uni<List<Memory>>` chains directly into the reactive `startCase()` flow — no blocking wrapper needed
- `NoOpCaseMemoryStore @DefaultBean` (bridged via `BlockingToReactiveBridge`) means zero cost when unconfigured
- `tenantId` for the query is supplied by the engine from `CurrentPrincipal.tenancyId()` — not declared in the case definition
- Populated once at case start; not updated during the case run

**New YAML field:**
```yaml
episodic:
  memory:
    domain: "fraud-check"            # String; becomes new MemoryDomain("fraud-check")
    entityId: ".semantic.customerId" # JQ expr against semantic panel; string → List.of(), array → direct
    recent: 10                       # default: 10; maps to MemoryQuery.withLimit(n)
```

`entityId` is a JQ expression evaluated against the semantic panel (populated first). A string result is wrapped in `List.of(value)` for `MemoryQuery.entityIds`. An array result passes through directly (max 25 per `MemoryQuery` contract). The engine projects `{text, attributes}` from each `Memory` record into the panel — `memoryId`, `entityId`, `domain`, `tenantId`, `caseId`, `createdAt` are operational metadata excluded from the projection:

```json
"memory": [
  {
    "text": "Customer C-123 had two prior fraud investigations in 2025",
    "attributes": {"outcome": "HIGH_RISK", "confidence": "0.9200"}
  },
  {
    "text": "Score threshold breached Q4 2025",
    "attributes": {"actor-id": "analyst-1", "valid-from": "2025-10-01"}
  }
]
```

`attributes` keys follow `MemoryAttributeKeys` conventions (`outcome`, `confidence`, `actor-id`, `actor-role`, `valid-from`, `valid-until`) but are not constrained to them.

JQ access patterns for workers:
```
.episodic.memory[].text
.episodic.memory[].attributes.outcome
.episodic.memory[] | select(.attributes.outcome == "HIGH_RISK")
.episodic.memory | map(select(.attributes.confidence != null))
```

**`MemoryQuery` construction** — uses existing factory methods from `io.casehub.platform.api.memory.MemoryQuery`. The query is cross-case by design: it retrieves memories for the entity across ALL prior cases. No `withCaseId()` — adding the current case's UUID would return empty results since the case just started and has no stored memories yet.

```java
// Single entity (JQ result is a string)
MemoryQuery query = MemoryQuery
    .forEntity(entityId, new MemoryDomain(domain), currentPrincipal.tenancyId())
    .withLimit(recent);

// Multiple entities (JQ result is an array)
MemoryQuery query = MemoryQuery
    .forEntities(entityIds, new MemoryDomain(domain), currentPrincipal.tenancyId())
    .withLimit(recent);
```

`MemoryOrder.CHRONOLOGICAL` is the factory default. `question` (semantic search) and `since` (time filter) are not exposed in YAML for this initial integration.

**Recovery:** the `memory` array is persisted in the CASE_STARTED EventLog payload as part of the full panel document. `fromPanelDocument()` reconstructs it from the stored snapshot. `ReactiveCaseMemoryStore` is NOT re-queried on recovery — the stored snapshot is authoritative.

---

## Population Lifecycle

**Order at case start (sequential):**

1. Working panel created (empty, or with `inputData` from call site converted via initial `inputProjection` if declared)
2. Semantic panel: definition `semanticData` merged, then call-site `semanticData` merged over top. `ReadablePanel` view exposed externally.
3. Episodic panel — inter-case: if `episodic.memory` declared AND `ReactiveCaseMemoryStore` non-NoOp, evaluate `entityId` JQ against semantic panel, call `ReactiveCaseMemoryStore.query()` as a `Uni` step in the reactive chain, inject `memory` section. `ReadablePanel` view exposed.
4. Episodic panel — intra-case: rebuild from `EventLog` if recovered case via `fromPanelDocument()`; empty for new cases.
5. `CaseStartedEventHandler` stores `instance.getCaseContext().asJsonNode()` (full panel document) as CASE_STARTED payload.
6. First `CONTEXT_CHANGED` fires.

### `CaseHubRuntime.startCase()` — new overloads

```java
// Existing — unchanged
CompletionStage<UUID> startCase(CaseDefinition definition);
CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData);
CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData,
    UUID parentCaseId, PropagationContext propagationContext);

// New — with semantic augmentation
CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData,
    Map<String, Object> semanticData);
CompletionStage<UUID> startCase(CaseDefinition definition, Object inputData,
    Map<String, Object> semanticData,
    UUID parentCaseId, PropagationContext propagationContext);
```

`semanticData` is `Map<String, Object>`, nullable — null is treated as empty (no call-site augmentation).

**Sub-case semantic isolation:** child cases started via `SubCase` bindings use only their own case definition's `semanticData` defaults. If a parent needs to pass semantic context to a child, it does so via `SubCase.inputMapping` (JQ expression targeting the child's `inputData`), and the child's definition maps that `inputData` into its own `semanticData` via a YAML `semanticData` block referencing `inputData`. Semantic panels are not automatically inherited across sub-case boundaries.

### Recovery — panel-aware reconstruction

`DefaultWorkerExecutionRecoveryService.rebuildStateContext()` currently does:

```java
caseContext = new CaseContextImpl(payloadAsMap(caseStartedEvent.getPayload()));
```

After the change, the CASE_STARTED payload is a panel document. This line becomes:

```java
caseContext = CaseContextImpl.fromPanelDocument(caseStartedEvent.getPayload());
```

`fromPanelDocument()` reads `{"working":{...},"semantic":{...},"episodic":{...}}` and reconstructs each panel. The semantic panel is reconstructed as read-only. The episodic panel is reconstructed as read-only. Subsequent event replays (signals, worker output, milestones) use the flat API which targets the working panel — no changes needed in those paths.

Signal patches (`SIGNAL_RECEIVED` events) stored in EventLog contain working-panel-relative JSON Patches (`/result`, not `/working/result`). `applyDiff()` during recovery applies these to the working panel. Old patches remain valid — format is not changed.

### Episodic panel updates during execution

Engine writes to the episodic panel after:
- `WORKER_EXECUTION_FINISHED` — updates the worker's entry in `episodic.workers`
- `MILESTONE_REACHED` — appends to `episodic.milestones`
- `GOAL_REACHED` — appends to `episodic.goals`

These publish `casehub.context.changed.episodic` only — not `CONTEXT_CHANGED`. No binding re-evaluation on episodic updates.

---

## User-Defined Panels (#81)

### Panel declaration in `CaseDefinition`

**YAML:**
```yaml
panels:
  - name: "raw"
  - name: "extracted"
  - name: "inferences"
  - name: "conclusions"
```

**DSL builder:**
```java
CaseDefinition.builder()
    .panel("raw")
    .panel("extracted")
    .panel("inferences")
    .panel("conclusions")
```

All user-defined panels are read-write by default. No `abstractionLevel` — the engine provides named panels as named key-value stores and imposes no ordering semantics.

### Panel-scoped change events

```java
public final class EventBusAddresses {
    public static final String CONTEXT_CHANGED = "casehub.context.changed";

    public static String panelChanged(String panelName) {
        return "casehub.context.changed." + panelName;
    }
}
```

Firing rules by panel type:

| Panel | Fires `CONTEXT_CHANGED` | Fires `casehub.context.changed.{name}` |
|---|---|---|
| Working | Yes | Yes (`casehub.context.changed.working`) |
| User-defined | Yes | Yes (`casehub.context.changed.{name}`) |
| Semantic | No | Yes, on initialisation only |
| Episodic | No | Yes, on intra-case updates only |

Working and user-defined panels fire both addresses. Bindings without `listenPanel` subscribe to `CONTEXT_CHANGED` and re-evaluate on any of these. Bindings with `listenPanel` use single-handler filtering (see below).

### `CaseContextChangedEvent` record change

```java
// Before
public record CaseContextChangedEvent(CaseInstance instance, JsonNode contextSnapshot)

// After
public record CaseContextChangedEvent(CaseInstance instance, CaseContext contextSnapshot, String changedPanel)
```

`contextSnapshot` type changes from `JsonNode` to `CaseContext`. Each construction site passes `instance.getCaseContext().snapshot()` — a deep copy of all panels at that instant. This eliminates the existing asymmetry in `CaseContextChangedEventHandler` where `rules()` uses the snapshot but `milestones()` and `goals()` use the live context directly. With `CaseContext`, the handler passes the snapshot to all three: `expressionEngineRegistry.evaluate(filter, contextSnapshot)` works for both JQ (registry calls `contextSnapshot.asJsonNode()` internally) and Lambda (`Predicate<CaseContext>`) evaluators.

`changedPanel` is `ContextPanel.WORKING` for all current engine events. Panel-scoped addresses (`casehub.context.changed.episodic`) are published directly without going through `CaseContextChangedEvent`.

**All construction sites that break (14 sites — all pass `instance.getCaseContext().snapshot()` as contextSnapshot and `ContextPanel.WORKING` as changedPanel):**

| Class | Line | New argument |
|---|---|---|
| `CaseStartedEventHandler` | 80 | `ContextPanel.WORKING` |
| `SignalReceivedEventHandler` | 119 | `ContextPanel.WORKING` |
| `CaseStatusChangedHandler` | ~118 | `ContextPanel.WORKING` |
| `MilestoneActivatedEventHandler` | 141 | `ContextPanel.WORKING` |
| `MilestoneCompletedEventHandler` | 127 | `ContextPanel.WORKING` |
| `MilestoneSLAViolatedEventHandler` | 111 | `ContextPanel.WORKING` |
| `WorkflowExecutionCompletedHandler` | 174 | `ContextPanel.WORKING` |
| `ActionGateRejectedHandler` | 120 | `ContextPanel.WORKING` |
| `ActionGateExpiredHandler` | 105 | `ContextPanel.WORKING` |
| `WorkItemLifecycleAdapter` | 140 | `ContextPanel.WORKING` |
| `WorkItemLifecycleAdapter` | 216 | `ContextPanel.WORKING` |
| `PlanItemCompletionApplier` | 99 | `ContextPanel.WORKING` |
| `CaseContextChangedEventHandlerRoutingTest` | 159, 177, 195, 228 | `ContextPanel.WORKING` |
| `MilestoneLifecycleTest` | 243 | `ContextPanel.WORKING` |

### Binding panel subscription — single handler with field filtering

`CaseContextChangedEventHandler` keeps a single `@ConsumeEvent(CONTEXT_CHANGED)` handler. `@ConsumeEvent` is a compile-time annotation — dynamic per-panel subscriptions via annotation are impossible.

Instead, the handler reads `event.changedPanel()` and skips bindings whose `listenPanel()` doesn't match:

```java
for (Binding binding : bindings) {
    if (binding.getOn() instanceof ContextChangeTrigger cct) {
        String listenPanel = cct.getListenPanel();
        if (listenPanel != null && !listenPanel.equals(event.changedPanel())) {
            continue;   // binding targets a different panel
        }
        // evaluate filter expression...
    }
}
```

Panel-scoped addresses (`casehub.context.changed.{name}`) are still published for external consumers (Claudony, monitoring, future Drools rule-firing triggers) but the binding handler does not subscribe to them.

**Design risk:** The panel event model is designed for JQ binding subscription. Issue #446 (Drools/WorkingMemoryBridge) will need to consume panel events for typed fact insertion. Before #446 is implemented, validate that `casehub.context.changed.{name}` addresses serve as adequate Drools re-fire triggers. Tracked as engine#465.

### `ContextChangeTrigger.listenPanel`

```java
public class ContextChangeTrigger implements Trigger {
    private final ExpressionEvaluator filter;
    private final String listenPanel;   // null = all panels (current behaviour)
}
```

**YAML:**
```yaml
bindings:
  - name: "run-inference"
    on:
      contextChange:
        filter: ".extracted.featureScore > 0.5"
        listenPanel: "extracted"
```

Bindings without `listenPanel` are unchanged — no migration, no behaviour change.

---

## JQ Expression Changes

`asJsonNode()` now returns the full panel document. All callers automatically receive panel-structured JSON — no individual call site changes needed.

**All JQ string expressions that reference working panel keys must be updated:**

```
# Before
.result == null and .actionGateRejected == null

# After
.working.result == null and .working.actionGateRejected == null
```

**Lambda expressions (`Predicate<CaseContext>`) are not affected.** `context.get("key")` still delegates to working panel.

**Parse-time warning:** `CaseDefinitionYamlMapper` warns (not errors) when a JQ string expression starts with `.` but not with a known panel prefix. Allows staged migration.

### Affected expression locations

| Location | Change |
|---|---|
| `ContextChangeTrigger` filter | `.key` → `.working.key` |
| `Binding.when()` guard | `.key` → `.working.key` |
| `Worker.inputSchema` | `.key` → `.working.key` |
| `HumanTaskTarget.inputMapping` | `.key` → `.working.key` |
| `HumanTaskTarget.outputMapping` | `.key` → `.working.key` |
| `SubCase.inputMapping` | `.key` → `.working.key` |
| `Goal` condition (JQ string) | `.key` → `.working.key` |
| `Milestone` completionCriteria (JQ string) | `.key` → `.working.key` |
| Milestone state in JQ (e.g. milestone tracking) | `.milestones.{name}.lifecycleStatus` → `.working.milestones.{name}.lifecycleStatus` |
| Lambda `Predicate<CaseContext>` | No change |
| `episodic.memory.entityId` | `.semantic.key` (new, no migration) |

---

## Schema Changes Summary

| Field | Location | Required | Default |
|---|---|---|---|
| `semanticData` | Case definition top-level | No | — |
| `episodic.memory.domain` | Case definition top-level | Yes (if `memory` block present) | — |
| `episodic.memory.entityId` | Case definition top-level | Yes (if `memory` block present; JQ expr against semantic panel) | — |
| `episodic.memory.recent` | Case definition top-level | No | 10 |
| `panels[].name` | Case definition top-level | Yes (if block present) | — |
| `binding.on.contextChange.listenPanel` | Per binding | No | null (all panels) |

All new fields optional. Existing case definitions load unchanged.

---

## Platform Coherence

- **Memory SPI — all types exist, no new dependencies:**

| Type | Package | Jar |
|---|---|---|
| `CaseMemoryStore` (blocking SPI) | `io.casehub.platform.api.memory` | `casehub-platform-api` |
| `GraphCaseMemoryStore extends CaseMemoryStore` | `io.casehub.platform.api.memory` | `casehub-platform-api` |
| `Memory`, `MemoryQuery`, `MemoryDomain`, `MemoryOrder`, `MemoryAttributeKeys` | `io.casehub.platform.api.memory` | `casehub-platform-api` |
| `ReactiveCaseMemoryStore` | `io.casehub.platform.memory` | `casehub-platform` |
| `BlockingToReactiveBridge @DefaultBean` | `io.casehub.platform.memory` | `casehub-platform` |
| `NoOpCaseMemoryStore @DefaultBean` (implements `GraphCaseMemoryStore`) | `io.casehub.platform.memory` | `casehub-platform` |

  `casehub-platform` is already in the engine's transitive dependency tree (confirmed). CDI no-op chain: `ReactiveCaseMemoryStore` injection → `BlockingToReactiveBridge` → injects `CaseMemoryStore` → resolves to `NoOpCaseMemoryStore` → `query()` returns `List.of()` → `memory` key absent from episodic panel. No engine-side no-op needed.
- **No Flyway changes** — `CaseContext` is not persisted. `CaseInstanceEntity` unchanged.
- **EventLog payload format** — CASE_STARTED payload is now a panel document. Signal patch format unchanged (working-panel-relative `/key` paths). All patches replayed against working panel during recovery.

---

## Testing Plan

| Test class | What it covers |
|---|---|
| `CaseContextImplTest` (extend) | Panel creation, flat API delegates to working, `asJsonNode()` panel document shape, `getVersion()` = working panel version |
| `ReadablePanelTest` (new) | Read-only enforcement: no write methods compile or succeed on `ReadablePanel` type |
| `WritablePanelTest` (new) | Write methods, version tracking, `clear()` scoped to this panel only |
| `PanelDocumentSnapshotTest` (new) | `snapshot()` on root captures all panels; `MapCaseFile.snapshot()` propagates panels |
| `ApplyAndDiffPanelTest` (new) | `applyAndDiff()` targets working panel; patch is working-panel-relative; `applyDiff()` replays correctly |
| `SemanticPanelPopulationTest` (new) | Definition defaults merged with call-site override; read-only after init; order precedence |
| `EpisodicPanelIntraCaseTest` (new) | Engine updates after worker completion, milestone, goal; EventLog rebuild on recovery |
| `EpisodicPanelInterCaseTest` (new) | `@QuarkusTest` with `ReactiveCaseMemoryStore @Alternative @Priority(1)` recording mock; query uses `entityId` JQ against semantic panel; `tenantId` = `CurrentPrincipal.tenancyId()`; no `caseId` in query (cross-case by design); string JQ result → `List.of(entityId)`; array JQ result → multi-entity query; `{text, attributes}` projected into panel — no `memoryId`/`tenantId`/`caseId` in panel; `memory` key absent when store returns `List.of()`; memory restored from CASE_STARTED EventLog on recovery — `ReactiveCaseMemoryStore` not called again |
| `RecoveryPanelAwareTest` (new) | `fromPanelDocument()` reconstructs all panels; recovery replays signal patches to working panel |
| `PanelScopedEventTest` (new) | `changedPanel` field correct; bindings with `listenPanel` skip non-matching events |
| `JQExpressionPanelTest` (new) | `.working.key`, `.semantic.key`, `.episodic.workers`, `.working.milestones.{name}.lifecycleStatus` in filter expressions |
| `CaseDefinitionYamlMapperTest` (extend) | New YAML fields parse; unmigrated expressions produce warning; existing definitions load unchanged |
| `CaseContextChangedEventTest` (extend) | Three-arg record constructor; `contextSnapshot` is `CaseContext` (not `JsonNode`); `changedPanel` field on all 14 construction sites; snapshot consistency — milestones/goals use snapshot not live context |

---

## Implementation Sequence

**#80 (this branch, first):**
1. `ReadablePanel` + `WritablePanel` + `UserDefinedPanel` interfaces
2. `WritablePanelImpl` + `ReadOnlyPanelView` implementations
3. `CaseContextImpl` internal panel map restructure; `fromPanelDocument()` factory; flat API delegates to working
4. `asJsonNode()` panel document; `getVersion()` → working panel; `snapshot()` all panels; `applyAndDiff()` working-panel-relative; `clear()` working only; `merge()` working only; `MapCaseFile.snapshot()` fix
5. `ContextPanel` constants class
6. `CaseContextChangedEvent` — change `contextSnapshot` from `JsonNode` to `CaseContext`, add `String changedPanel`; update all 14 construction sites to pass `instance.getCaseContext().snapshot()` as contextSnapshot; update `CaseContextChangedEventHandler.rules()/milestones()/goals()` signatures from `JsonNode` to `CaseContext`; use snapshot consistently in all three (eliminates live-context asymmetry in milestones/goals)
7. `EventBusAddresses.panelChanged()`; panel-scoped event firing in `CaseContextChangedEventHandler` and episodic update paths
8. Semantic panel: YAML `semanticData` field; DSL builder; `CaseHubRuntime.startCase()` new overloads; population at case start
9. Episodic panel intra-case: EventLog rebuild + lifecycle updates
10. Episodic panel inter-case: `episodic.memory` YAML field; `entityId` JQ evaluation + coercion to `List<String>`; `MemoryQuery` via `forEntity()`/`forEntities()` factory methods (no `withCaseId()`); `ReactiveCaseMemoryStore.query()` chained as `Uni` in `buildInstance()`; `{text, attributes}` projection into `episodic.memory[]`
11. `DefaultWorkerExecutionRecoveryService` — `fromPanelDocument()` for CASE_STARTED reconstruction
12. JQ expression migration across all engine tests and case definitions
13. `CaseDefinitionYamlMapper` parse-time warning for unmigrated expressions

**#81 (follow-on, same branch):**
1. User-defined panel declaration: YAML + DSL builder (no abstractionLevel; user panels are plain `WritablePanel` instances)
2. `ContextChangeTrigger.listenPanel`: YAML + DSL builder
3. `CaseContextChangedEventHandler` `listenPanel` field-based filtering
4. Panel-scoped event address publishing for user-defined panels
5. Panel-scoped event tests

---

## Open Issues

- engine#464 — revisit "panel" terminology after implementation (candidates: layer, region)
- engine#465 — validate panel event model (`casehub.context.changed.{name}`) serves Drools re-fire triggers before #446 implementation
