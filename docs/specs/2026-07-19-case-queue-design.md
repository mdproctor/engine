# Case Queue Design — engine#730

## Summary

Case queue operational layer built on platform's subject view toolkit and labelling
infrastructure. Cases are labellable subjects — mutable labels on `CaseInstance`
determine queue membership via `SubjectViewOrchestrator.evaluateAndTrack()`. Label
rules (using platform's `LabelRule` with `CompiledExpression`) apply labels based on
case context state. The engine adds the operational layer: claim, release, escalate,
SLA deadlines.

**Two layers:**

| Layer | Owner | What it does |
|-------|-------|-------------|
| Visibility | Platform (`SubjectViewOrchestrator`) | Which cases appear in which queue. Label pattern matching, ADDED/REMOVED/CHANGED events. |
| Operations | Engine (`CaseQueueService`) | What happens to cases in a queue. Claim, release, escalate, SLA deadlines. |

## Design Decisions

1. **Use platform's `LabelRule` directly** — it was built for this use case. Work's
   `FilterEngine` predates `LabelRule` and will migrate to it. Engine should not
   replicate the old design.

2. **`Set<String>` labels on CaseInstance** — all rule-derived in v1. No
   MANUAL/INFERRED distinction. The orchestrator boundary contract is
   `Set<String>` label paths — internal representation is engine's private concern.
   Promote to a richer model if manual labelling becomes a requirement.
   Platform#187 did not ship a `Labellable` interface (issue #730 originally
   referenced it). CaseInstance uses `Set<String>` directly — this matches the
   orchestrator's `evaluateAndTrack()` contract without an interface.

3. **Evaluate on every `CaseLifecycleEvent`** — `LabelRule.evaluate()` is a pure
   function (microseconds of JQ evaluation per rule). The downstream path is
   idempotent: if labels didn't change, skip orchestrator call. Selective triggering
   would require dependency analysis of JQ expressions — wrong trade-off.

4. **Separate module `casehub-engine-queue`** — `@Alternative @Priority(10)`,
   activated by classpath. Keeps platform-view dependencies out of engine runtime.
   Same pattern as `casehub-blackboard`.

5. **Expression compilation** — `CompiledExpression<Map<String, Object>, Boolean>` is
   the platform SPI. JQ is one implementation via `JQExpressionEngine`.
   `MapAdaptedJQExpression` handles `Map<String, Object>` adaptation automatically
   when compiling with `contextType = Map.class`. No separate bridge needed.

6. **`@ObservesAsync` is the only option** — engine fires `CaseLifecycleEvent` via
   `Event.fireAsync()` from Vert.x event-bus handlers (CDI spec: `fireAsync()`
   delivers only to `@ObservesAsync` observers). Unlike work's `@Observes` +
   `@Transactional` pattern, engine cannot use synchronous observation. Crash
   recovery is handled via startup reconciliation (§Crash Recovery).

7. **Queue views are an operational concern, separate from case definitions** —
   label rules define what labels a case gets (data classification). Queue views
   define what label patterns constitute queues (operational routing). Multiple case
   types can share a queue (e.g., all `priority/high` cases regardless of
   definition). Queue views are per-tenant; case definitions are global.

8. **Clean-slate with ordered action application** — on each evaluation, existing
   labels are cleared and all matching rules' actions are applied in rule order.
   `LabelAction.Add` adds to the set, `LabelAction.Remove` removes from the set.
   Remove enables intra-pass negation: a later rule can conditionally negate a
   label added by an earlier rule. This is the full `LabelAction` sealed interface
   contract — engine uses it as designed.

## Data Model

### CaseInstance (engine-common)

Gains `Set<String> labels` — mutable, empty default. No `Labellable` interface —
platform#187 did not ship it (see Design Decision 2). `LabelRuleEvaluator` from
issue #730 also did not ship; `LabelRule.evaluate()` is a static method on the
record itself.

```java
public class CaseInstance {
    // ... existing fields ...
    private Set<String> labels = new LinkedHashSet<>();

    public Set<String> getLabels() { return labels; }
    public void setLabels(Set<String> labels) { this.labels = labels; }
}
```

Persistence:
- **JPA:** `@ElementCollection` on `CaseInstanceEntity`, stored in `case_instance_label` table.
- **In-memory:** `Set<String>` on `CaseInstance` directly (already there).

### CaseDefinition (engine-api)

Gains `List<LabelRule> labelRules` — platform's `LabelRule` directly from
`io.casehub.platform.api.label`.

```java
public class CaseDefinition {
    // ... existing fields ...
    private List<LabelRule> labelRules = List.of();

    public List<LabelRule> getLabelRules() { return labelRules; }
    public void setLabelRules(List<LabelRule> labelRules) {
        this.labelRules = List.copyOf(labelRules);
    }
}
```

Builder:
```java
CaseDefinition.builder()
    .labelRule(new LabelRule(
        "high-priority",
        expressionEngine.compile(".severity == \"HIGH\"", Map.class, Boolean.class),
        List.of(new LabelAction.Add("priority/high"))))
    .build();
```

### YAML Schema

```yaml
labelRules:
  - name: high-priority
    when: '.severity == "HIGH"'
    actions:
      - add: "priority/high"
  - name: entity-resolution-triage
    when: '.entityResolution.status == "pending"'
    actions:
      - add: "triage/entity-resolution"
  - name: entity-resolution-resolved
    when: '.entityResolution.status == "resolved"'
    actions:
      - remove: "triage/entity-resolution"
      - add: "resolved/entity-resolution"
```

The `when` string is compiled to `CompiledExpression<Map<String, Object>, Boolean>` at
definition load time via `ExpressionEngineRegistry` (JQ default). Parsed by
`CaseDefinitionYamlMapper` in the same pass as other YAML elements.

**Action semantics:** `LabelAction` is a sealed interface with `Add` and `Remove`
variants. On each evaluation, existing labels are cleared (clean-slate) and all
matching rules' actions are applied in rule order: `Add` adds to the accumulating
set, `Remove` removes from it. This enables intra-pass negation — a later rule can
negate a label added by an earlier rule in the same pass. See Design Decision 8.

## Module Structure

### casehub-engine-queue

New Maven module alongside `casehub-blackboard`, `casehub-engine-flow`, etc.

**Dependencies:**
- `casehub-engine-common` (CaseInstance, CaseLifecycleEvent, CaseDefinitionRegistry)
- `casehub-engine-api` (CaseDefinition, LabelRule via platform-api transitive)
- `casehub-platform-view` (SubjectViewOrchestrator, SubjectViewEvaluator)
- `casehub-platform-api` (SubjectViewSpec, ViewMembershipTracker, LabelRule, LabelAction)

**Test dependencies:**
- `casehub-persistence-memory`
- `casehub-platform-view-inmem` (InMemoryViewMembershipTracker)

**Activation:** `@Alternative @Priority(10)` on key CDI beans. Consumers add
`casehub-engine-queue` to their POM — same convention as `casehub-blackboard`.

## Label Evaluation

### CaseLabelEvaluator

`@ApplicationScoped` CDI bean in `casehub-engine-queue`. The core observer that bridges
case lifecycle events to queue membership.

```java
@ApplicationScoped
public class CaseLabelEvaluator {

    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject ReactiveCaseInstanceRepository caseInstanceRepository;
    @Inject SubjectViewOrchestrator views;
    @Inject Event<CaseQueueEvent> queueEvents;

    // Per-case lock prevents concurrent @ObservesAsync handlers from racing
    // on the same CaseInstance's version (OptimisticLockException). Only one
    // evaluation per case runs at a time; others queue on the lock.
    private final ConcurrentHashMap<UUID, ReentrantLock> caseLocks = new ConcurrentHashMap<>();

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        var lock = caseLocks.computeIfAbsent(event.caseId(), k -> new ReentrantLock());
        lock.lock();
        try {
            // 1. Re-read CaseInstance from repository (not cache) to get
            //    latest version — prevents OptimisticLockException after
            //    a concurrent handler incremented the version
            // 2. CaseInstance.getCaseMetaModel() → CaseMetaModel
            // 3. definitionRegistry.getCaseDefinition(metaModel) → CaseDefinition
            // 4. If no label rules → return early
            // 5. Convert event.contextSnapshot() (JsonNode) to Map<String, Object>
            // 6. Call LabelRule.evaluate(rules, context) → List<LabelAction>
            // 7. Clear existing labels (clean-slate), apply actions in order:
            //    Add → add to set, Remove → remove from set
            // 8. Compare before/after label sets
            // 9. If labels changed:
            //    a. Persist updated labels: caseInstanceRepository.update(instance, tenancyId)
            //       .await().indefinitely() — safe on CDI managed executor thread
            //    b. views.evaluateAndTrack(caseId, tenancyId, labels)
            //    c. Map each SubjectViewEvent to CaseQueueEvent (via explicit switch),
            //       fire via CDI Event<CaseQueueEvent>.fire()
            // 10. On terminal status → clear labels, evaluateAndTrack (produces REMOVED),
            //     CaseQueueEntryManager handles entry cleanup via REMOVED events
            // 11. If terminal status → caseLocks.remove(event.caseId()) to prevent
            //     unbounded map growth (no further events for this case)
        } finally {
            lock.unlock();
        }
    }
}
```

**`@ObservesAsync` rationale:** Engine fires `CaseLifecycleEvent` via
`Event.fireAsync()` from Vert.x event-bus handlers. CDI spec mandates that
`fireAsync()` delivers only to `@ObservesAsync` observers — `@Observes` would
not receive these events. Work uses `@Observes` + `@Transactional` because
`WorkItemLifecycleEvent` is fired via `Event.fire()` synchronously. The async
model means no shared transaction with the originating mutation; crash recovery
is handled via startup reconciliation (§Crash Recovery).

**Per-case serialization:** `CaseInstance` has optimistic locking (`version` field).
Two concurrent `@ObservesAsync` handlers for the same case would both read the
same version, and the second `update()` throws `OptimisticLockException`. A
`ConcurrentHashMap<UUID, ReentrantLock>` serializes evaluation per case. Within
the lock, the evaluator re-reads the CaseInstance from the repository (not cache)
to get the current version. This avoids retry complexity and prevents wasted work.

**Lock map cleanup:** On terminal status (step 11), the lock entry is removed from
`caseLocks` — no further lifecycle events will fire for a completed/faulted/cancelled
case. This bounds the map to the number of active (non-terminal) cases. Without
cleanup, the map grows monotonically with every case that ever triggers an event.

**Reactive await:** `ReactiveCaseInstanceRepository.update()` returns `Uni<T>`.
Within `@ObservesAsync` handlers, the CDI managed executor thread is blocking-safe
(not a Vert.x event loop), so `.await().indefinitely()` is the correct pattern.

**Clean-slate with ordered actions:** On every evaluation, existing labels are
cleared and recomputed from rules. `LabelAction.Add` adds to the accumulating set,
`LabelAction.Remove` removes from it (intra-pass negation). All labels are
rule-derived — no manual labels to preserve. This matches work's strip-and-reapply
pattern, extended with the full `LabelAction` sealed interface.

**Idempotency:** `LabelRule.evaluate()` is pure. Same context produces same labels.
Before/after comparison short-circuits the orchestrator call when nothing changed.

**Context conversion:** `CaseLifecycleEvent.contextSnapshot()` is a `JsonNode`.
Convert to `Map<String, Object>` via Jackson `ObjectMapper.convertValue()` for
`LabelRule.evaluate()`. The `MapAdaptedJQExpression` inside the compiled expression
handles the `Map → JsonNode → JQ evaluation` round-trip internally.

**Terminal case cleanup:** When `CaseLifecycleEvent` carries a terminal status
(COMPLETED, FAULTED, CANCELLED), clear all labels on the CaseInstance, call
`evaluateAndTrack()` to produce REMOVED events for all current queue memberships.
`CaseQueueEntryManager` handles entry cleanup via the resulting REMOVED events.

## Queue View Lifecycle

Queue views (`SubjectViewSpec` records in `SubjectViewStore`) are the glue between
label production (label rules → labels on cases) and label consumption
(`SubjectViewOrchestrator` → membership events → queue entries). Without view specs,
`evaluateAndTrack()` returns an empty list and no queue events fire.

**Separation of concerns:** Label rules define what labels a case gets (data
classification in `CaseDefinition`). Queue views define what label patterns
constitute queues (operational routing in `SubjectViewStore`). This separation
mirrors work's pattern — `FilterEvaluationObserver` does not create views; views
are created as part of queue configuration via separate APIs.

### CaseQueueViewManager

`@ApplicationScoped` service in `casehub-engine-queue` for managing queue view
definitions. Wraps `SubjectViewOrchestrator.saveView()` / `deleteView()` with
engine-specific validation and idempotent bootstrapping.

```java
@ApplicationScoped
public class CaseQueueViewManager {

    @Inject SubjectViewOrchestrator views;

    public SubjectViewSpec ensureQueueView(String name, String tenancyId,
                                            String labelPattern) {
        // Deterministic UUID from (tenancyId, name) — same logical view always
        // gets the same UUID. SubjectViewStore.save() is upsert-by-PK in both
        // InMemory (ConcurrentHashMap.put) and JPA (find-then-merge/persist).
        var viewId = UUID.nameUUIDFromBytes(
            (tenancyId + ":" + name).getBytes(StandardCharsets.UTF_8));
        var spec = new SubjectViewSpec(
            viewId, name, tenancyId, labelPattern,
            null, "createdAt", "ASC", null, Instant.now());
        return views.saveView(spec);
    }

    public boolean deleteQueueView(UUID viewId) {
        return views.deleteView(viewId);
    }
}
```

**Deterministic UUID:** `UUID.nameUUIDFromBytes()` (UUID v3) derives the view ID
from `tenancyId + ":" + name`. On restart, the same logical view produces the same
UUID. `SubjectViewStore.save()` upserts by primary key — no duplicate views are
created. Both `InMemorySubjectViewStore` (`store.put(id, ...)`) and
`JpaSubjectViewStore` (`em.find() ? merge : persist`) support this pattern.

**When views are created:** Queue views are a deployment/configuration concern.
They are created:
1. Programmatically via `CaseQueueViewManager` during application setup
2. Via REST endpoint (future — out of scope for v1)

Example setup in a `@Startup` bean (runs at `@Priority(100)` — before the
reconciler at `@Priority(200)`, see §Crash Recovery):
```java
@ApplicationScoped
public class QueueBootstrap {
    @Inject CaseQueueViewManager queueViews;

    void onStartup(@Observes @Priority(100) StartupEvent event) {
        queueViews.ensureQueueView("High Priority", tenancyId, "priority/high");
        queueViews.ensureQueueView("Entity Resolution Triage", tenancyId,
                                    "triage/entity-resolution");
    }
}
```

**Label pattern matching:** `SubjectViewSpec.labelPattern` is matched against case
labels via `LabelPatternMatcher.matches()`. Supports exact match (`priority/high`),
single-level wildcard (`priority/*`), and recursive wildcard (`priority/**`).

**Scope parameter:** `SubjectViewSpec.scope` is optional. Engine does not use scoping
in v1. The non-scoped `evaluateAndTrack(caseId, tenancyId, labels)` overload is used.

## Queue Events

### CaseQueueEvent

```java
public record CaseQueueEvent(
    UUID caseId,
    UUID queueViewId,
    String queueName,
    CaseQueueEventType eventType,
    String tenancyId
) {}
```

### CaseQueueEventType

```java
public enum CaseQueueEventType { ADDED, REMOVED, CHANGED }
```

Mirrors platform's `ViewEventType`. Same pattern as work's `QueueEventType`.
Mapping via explicit switch — not `valueOf()`, which would throw
`IllegalArgumentException` on any future `ViewEventType` values:

```java
static CaseQueueEventType from(ViewEventType viewType) {
    return switch (viewType) {
        case ADDED   -> CaseQueueEventType.ADDED;
        case REMOVED -> CaseQueueEventType.REMOVED;
        case CHANGED -> CaseQueueEventType.CHANGED;
    };
}
```

The exhaustive switch (no default) ensures a compile error if `ViewEventType` gains
new values, forcing an explicit design decision rather than a runtime crash.

## Operational Layer

### CaseQueueEntry

JPA entity in `casehub-engine-queue` — the operational record wrapping queue
membership with claim/release/escalate semantics.

```java
@Entity
@Table(name = "case_queue_entry",
       uniqueConstraints = @UniqueConstraint(columnNames = {"caseId", "viewId"}))
public class CaseQueueEntry {
    @Id
    private UUID id;
    private UUID caseId;
    private String tenancyId;
    private UUID viewId;
    private String viewName;

    @Enumerated(EnumType.STRING)
    private QueueEntryStatus status;  // PENDING, CLAIMED, REVOKED

    private String assignedTo;        // nullable — set on claim
    private Instant claimedAt;        // nullable
    private Instant escalatedAt;      // nullable
    private UUID previousViewId;      // nullable — set on escalate
    private String previousViewName;  // nullable — set on escalate
    private Instant createdAt;
}
```

**Unique constraint on `(caseId, viewId)`:** prevents duplicate entries when
ADDED events replay (crash recovery, idempotent re-evaluation).

### QueueEntryStatus

```java
public enum QueueEntryStatus { PENDING, CLAIMED, REVOKED }
```

- **PENDING** — in queue, awaiting claim
- **CLAIMED** — assigned to a user/agent, actively worked
- **REVOKED** — was CLAIMED but removed from queue by label change (terminal,
  excluded from active queries, preserved for audit)

### CaseQueueEntryStore

SPI interface for CaseQueueEntry persistence.

```java
public interface CaseQueueEntryStore {
    CaseQueueEntry save(CaseQueueEntry entry);
    CaseQueueEntry upsertByCaseAndView(CaseQueueEntry entry);
    Optional<CaseQueueEntry> findById(UUID id);
    Optional<CaseQueueEntry> findByCaseAndView(UUID caseId, UUID viewId);
    List<CaseQueueEntry> findByView(UUID viewId, String tenancyId);
    List<CaseQueueEntry> findByCaseId(UUID caseId);
    long countByView(UUID viewId, String tenancyId);
    boolean delete(UUID id);
    void deleteByCaseId(UUID caseId);

    /**
     * Atomic PENDING → CLAIMED transition. Returns empty if entry is not PENDING.
     * JPA impl: UPDATE ... WHERE id = ? AND status = 'PENDING', check affected rows.
     */
    Optional<CaseQueueEntry> claimIfPending(UUID entryId, String userId);
}
```

**`upsertByCaseAndView()`** — insert or update by `(caseId, viewId)`. Used by
`CaseQueueEntryManager` on ADDED events to be idempotent on replay.

**`claimIfPending()`** — atomic compare-and-swap for claim. JPA implementation uses
`UPDATE case_queue_entry SET status='CLAIMED', assigned_to=?, claimed_at=?
WHERE id=? AND status='PENDING'` and returns `Optional.empty()` if zero rows
affected (already claimed by another thread).

In-memory implementation for tests, JPA implementation for production. Same dual-stack
convention as engine's persistence modules.

### CaseQueueService

`@ApplicationScoped` service providing queue operations.

```java
@ApplicationScoped
public class CaseQueueService {

    @Inject CaseQueueEntryStore store;
    @Inject Event<CaseQueueEntryClaimed> claimedEvents;
    @Inject Event<CaseQueueEntryReleased> releasedEvents;
    @Inject Event<CaseQueueEntryEscalated> escalatedEvents;

    public CaseQueueEntry claim(UUID entryId, String tenancyId, String userId);
    public CaseQueueEntry release(UUID entryId, String tenancyId);
    public CaseQueueEntry escalate(UUID entryId, String tenancyId, UUID targetViewId);
    public List<CaseQueueEntry> findPending(UUID viewId, String tenancyId);
    public long countByView(UUID viewId, String tenancyId);
}
```

**Tenancy enforcement:** All operational methods take `String tenancyId` and verify
it matches the entry's `tenancyId` before proceeding. Throws `IllegalArgumentException`
on mismatch. This prevents cross-tenant operations regardless of how the service is
called (consistent with `ReactiveCaseInstanceRepository` which takes tenancyId on
every method).

**`claim()`** — delegates to `CaseQueueEntryStore.claimIfPending()` for atomic
PENDING → CLAIMED transition. Returns the claimed entry. Throws if entry is not
PENDING (already claimed — the atomic check prevents the race). Fires
`CaseQueueEntryClaimed`.

**`release()`** — CLAIMED → PENDING. Clears `assignedTo` and `claimedAt`. Fires
`CaseQueueEntryReleased`.

**`escalate()`** — **move operation** on the existing entry. Pre-check: if the case
already has an entry in the target queue (via `store.findByCaseAndView(caseId,
targetViewId)`), throws `IllegalStateException` — the case is already in the target
queue. Otherwise: updates `viewId` and `viewName` to the target queue, sets
`previousViewId`/`previousViewName` to the source, sets `escalatedAt`, clears
`assignedTo`/`claimedAt`, transitions to PENDING in the target queue. Preserves
`createdAt` and entry identity (`id`). Fires `CaseQueueEntryEscalated`.

### Operational CDI Events

```java
public record CaseQueueEntryClaimed(CaseQueueEntry entry, String claimedBy) {}
public record CaseQueueEntryReleased(CaseQueueEntry entry) {}
public record CaseQueueEntryEscalated(CaseQueueEntry entry, UUID sourceViewId, UUID targetViewId) {}
public record CaseQueueEntryRevoked(CaseQueueEntry entry, String previousAssignee) {}
```

`CaseQueueEntryClaimed`, `CaseQueueEntryReleased`, and `CaseQueueEntryEscalated`
are fired by `CaseQueueService` via `Event.fireAsync()`. `CaseQueueEntryRevoked`
is fired by `CaseQueueEntryManager` when processing a REMOVED event on a CLAIMED
entry (§Lifecycle Integration). All use `Event.fireAsync()` — consistent with
engine's fire-and-forget CDI pattern.

### Lifecycle Integration

`CaseQueueEntryManager` (`@ApplicationScoped`) observes `CaseQueueEvent` to manage
`CaseQueueEntry` lifecycle — separate from `CaseLabelEvaluator` for clean separation
of label evaluation and entry management:

- `ADDED` → upsert `CaseQueueEntry` via `store.upsertByCaseAndView()`. Behavior
  depends on existing entry status:
  - **No existing entry:** create new PENDING entry (`createdAt = now`)
  - **Existing PENDING:** no-op (already in queue)
  - **Existing CLAIMED:** no-op (don't disrupt an active claim)
  - **Existing REVOKED:** re-activate to PENDING, reset `createdAt` to now,
    clear `assignedTo`/`claimedAt`/`escalatedAt`/`previousViewId`/`previousViewName`
    (the case re-entered the queue — the revocation is superseded)
- `REMOVED` → if entry is PENDING, delete it. If entry is CLAIMED, transition to
  REVOKED and fire `CaseQueueEntryRevoked` so observers can notify the assignee.
  The queue entry is preserved for audit but excluded from active queue queries.
- `CHANGED` → no-op in v1

## Data Flow

```
CaseLifecycleEvent (CDI @ObservesAsync)
  │
  ▼
CaseLabelEvaluator
  │
  ┌─ Acquire per-case lock (try/finally — lock covers entire operation)
  │
  ├─ Re-read CaseInstance from repository (latest version, not cache)
  ├─ CaseInstance.getCaseMetaModel() → CaseMetaModel
  ├─ CaseDefinitionRegistry.getCaseDefinition(metaModel) → CaseDefinition
  ├─ Convert event.contextSnapshot() (JsonNode) → Map<String, Object>
  ├─ LabelRule.evaluate(rules, context) → List<LabelAction>
  ├─ Apply actions to CaseInstance.labels (clean-slate, ordered Add+Remove)
  ├─ Persist via ReactiveCaseInstanceRepository.update().await().indefinitely()
  │
  ├─ If labels changed:
  │    │
  │    ▼
  │  SubjectViewOrchestrator.evaluateAndTrack(caseId, tenancyId, labels)
  │    │
  │    ▼
  │  List<SubjectViewEvent> → mapped to List<CaseQueueEvent> (explicit switch)
  │  fired via CDI Event<CaseQueueEvent>.fire()
  │    │
  │    ▼
  │  CaseQueueEntryManager
  │    ├─ ADDED → upsert CaseQueueEntry (PENDING)
  │    ├─ REMOVED → delete PENDING / revoke CLAIMED
  │    └─ CHANGED → no-op (v1)
  │
  ├─ If labels unchanged: no-op (idempotent)
  │
  ├─ If terminal status: remove lock entry from caseLocks map
  │
  └─ Release per-case lock (finally block)
```

## Crash Recovery

Because `@ObservesAsync` runs outside the originating transaction (see Design
Decision 6), a crash partway through the evaluation chain can leave stores
diverged. Three stores hold label-derived state:

1. `CaseInstance.labels` — persisted via JPA `@ElementCollection` (**source of truth**)
2. `ViewMembershipTracker` state — maintained by `evaluateAndTrack()`
3. `CaseQueueEntry` records — managed by `CaseQueueEntryManager`

### Startup reconciliation

On application startup, `CaseLabelReconciler` re-evaluates all active cases.
Runs at `@Priority(200)` — **after** `QueueBootstrap` at `@Priority(100)` (see
§Queue View Lifecycle), ensuring queue views exist before reconciliation.

```java
@ApplicationScoped
public class CaseLabelReconciler {

    @Inject ReactiveCaseInstanceRepository caseInstanceRepository;
    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject SubjectViewOrchestrator views;
    @Inject CaseQueueEntryStore entryStore;

    void reconcile(@Observes @Priority(200) StartupEvent event) {
        // Load active cases from REPOSITORY (not cache — cache is empty after crash).
        // For each tenancy with registered queue views:
        //   caseInstanceRepository.findByStatus(ACTIVE, tenancyId)
        //       .await().indefinitely()
        //   For each active CaseInstance:
        //     1. Re-evaluate label rules against current context
        //     2. Call evaluateAndTrack() to reconcile tracker + fire events
        //     3. CaseQueueEntryManager handles entry reconciliation via events
        //
        // Terminal case cleanup:
        //   For each terminal status (COMPLETED, FAULTED, CANCELLED):
        //     Find cases with non-empty labels or existing queue entries
        //     Clear labels, call evaluateAndTrack() to produce REMOVED events
        //     CaseQueueEntryManager handles entry deletion/revocation
    }
}
```

**Startup ordering:** CDI `@Priority` on `@Observes` methods guarantees execution
order (lower values first). Queue views must exist before reconciliation runs,
otherwise `evaluateAndTrack()` sees no views and generates spurious REMOVED events
that wipe all queue entries:

| Priority | Bean | Purpose |
|----------|------|---------|
| 100 | `QueueBootstrap` | Ensure queue views exist (idempotent via deterministic UUID) |
| 200 | `CaseLabelReconciler` | Reconcile case labels and queue entries against persisted state |

### Idempotency guarantees

- `evaluateAndTrack()` is idempotent for `ViewMembershipTracker` — it diffs
  before/after and only fires events for actual changes.
- `CaseQueueEntryManager` uses `upsertByCaseAndView()` — duplicate ADDED events
  do not create duplicate entries (unique constraint on `(caseId, viewId)`).
- `LabelRule.evaluate()` is pure — same context always produces the same labels.

## Testing

- In-memory `CaseQueueEntryStore` for unit tests
- `casehub-platform-view-inmem` (`InMemoryViewMembershipTracker`,
  `InMemorySubjectViewStore`) for integration tests
- `@QuarkusTest` with `casehub-persistence-memory` for full lifecycle tests
- Test pattern: create a CaseDefinition with label rules, start a case, signal
  context changes, assert queue membership events and CaseQueueEntry state

## Platform Recommendations

1. **`LabelRule` should support expression language declaration** — currently `condition`
   is a pre-compiled `CompiledExpression`. For YAML round-tripping and serialisation,
   it would help to also carry the source expression string and language (e.g.
   `"jq"`, `".severity == \"HIGH\""`) so definitions can be stored and reconstructed.
   Engine will handle this at the `CaseDefinitionYamlMapper` level for now.

## Blocked By

- None — platform#187 (LabelRule, LabelAction) and platform#175 (SubjectViewOrchestrator)
  are both shipped.

## References

- Platform view toolkit: `SubjectViewOrchestrator`, `SubjectViewSpec`, `ViewMembershipTracker`
- Platform labelling: `LabelRule`, `LabelAction`, `CompiledExpression`
- Work's queue pattern: `FilterEvaluationObserver`, `WorkItemQueueEvent`, `QueueEventType`
- Engine blackboard module: `casehub-blackboard` (module activation pattern)
