# S/XS Backlog Sweep — Design Spec

**Epic:** #678  
**Branch:** `issue-678-sx-backlog-sweep`  
**Date:** 2026-07-07

---

## Cluster A — Mechanical Fixes (XS)

### #664 — Rename all Memory*-prefixed classes to InMemory* for consistency

**What:** Rename all `Memory*`-prefixed classes in `persistence-memory/` to `InMemory*` for
consistency with all other in-memory implementations (`InMemoryCaseInstanceRepository`,
`InMemorySubCaseGroupRepository`, etc.).

**Classes to rename:**
1. `MemoryReactivePlanItemStore` → `InMemoryReactivePlanItemStore`
2. `MemoryPlanItemStore` → `InMemoryPlanItemStore`
3. `MemoryPlanItemStoreContractTest` → `InMemoryPlanItemStoreContractTest`

**Cross-repo impact:** `MemoryPlanItemStore` may be referenced in consumer repos'
`quarkus.arc.selected-alternatives` config entries (per PLATFORM.md §Cross-Repo
Dependency Map). Update any such config entries as part of this rename.

**Scope:**
- Rename classes in `persistence-memory/`
- Update cross-repo `selected-alternatives` entries that reference the old names
- Update any imports

**Risk:** Low. Mechanical rename via IntelliJ. Cross-repo config update is a one-line change.

---

### #665 — Make DefaultCaseDefinitionRegistry startup timeout configurable

**What:** Replace hardcoded 30s timeout at `DefaultCaseDefinitionRegistry.java:86`.

**Design:**
```java
@ConfigProperty(name = "casehub.engine.registry.startup-timeout", defaultValue = "30s")
Duration startupTimeout;
```
Replace `.atMost(Duration.ofSeconds(30))` → `.atMost(startupTimeout)`.

Follows existing pattern: `casehub.engine.recovery.timeout` in `WorkerRecoveryCoordinator`.

---

### #673 — CbrConfig validation at CaseDefinition registration time

**What:** Add registration-time validation for `CbrConfig` in
`DefaultCaseDefinitionRegistry.validateExpressions()`, alongside existing
binding/milestone/goal validation.

**Checks:**
1. If `CbrConfig` present but `domain` is null and no `EpisodicMemoryConfig` exists →
   warn: "CbrConfig has no domain and no EpisodicMemoryConfig — retrieval will always
   return empty"
2. If `CbrConfig` has JQ-based `FeatureExtractor` instances → compile-check each JQ
   expression via `jqEvaluator.validate()`

**Not a hard failure** — log WARNING, not throw. CbrConfig may be intentionally incomplete
during development.

---

## Cluster B — Handler Consolidation and Bug Fix

### #666 — Consolidate WorkerRetryExhaustionHandler + PlanItemFaultHandler

**Root cause:** Both handlers subscribe to `EventBusAddresses.WORKER_RETRIES_EXHAUSTED` via
`@ConsumeEvent(blocking = true)`. The address uses `eventBus.publish()` (fan-out delivery),
so both handlers receive every event. Both call `item.markFaulted()`. The PlanItem state
machine throws `IllegalStateException` on concurrent state transition — the loser catches
the exception and returns early. This means either `PlanItemFaultedEvent` (from
PlanItemFaultHandler, fired via CDI `fireAsync()`) or `stageAutocompleteEvaluator.evaluate()`
(from WorkerRetryExhaustionHandler) is lost on any given exhaustion.

**Verified:** Both handlers use `@ConsumeEvent(value = EventBusAddresses.WORKER_RETRIES_EXHAUSTED,
blocking = true)`. PlanItemFaultHandler's Javadoc confirms fan-out via `eventBus.publish()`.
The "CAS" is `PlanItem.markFaulted()` throwing `IllegalStateException` when the state machine
rejects the transition (item already terminal).

**Design:** Merge into a single `WorkerRetryExhaustionHandler`:
1. Resolve PlanItem (using `bindingName` with `workerId` fallback — current best-path
   from WorkerRetryExhaustionHandler)
2. Call `markFaulted()` once
3. Fire `PlanItemFaultedEvent` via CDI `fireAsync()` (adopted from PlanItemFaultHandler)
4. Call `stageAutocompleteEvaluator.evaluate()` (already present)
5. **Delete `PlanItemFaultHandler`** — it has no unique responsibility left

**Ordering rationale:** `markFaulted()` first (state transition must succeed before
downstream effects). `PlanItemFaultedEvent` via `fireAsync()` is non-blocking — it's
dispatched asynchronously and does not delay the handler. `stageAutocompleteEvaluator.evaluate()`
is synchronous and follows immediately. This preserves the intent of both original handlers
without race conditions.

**PlanItemFaultedEvent observers:** This CDI event is observed by consumers outside the
blackboard module (no observers found within the engine runtime for this specific event type).
The event semantics are unchanged — it still fires after `markFaulted()` succeeds. Other
producers of `PlanItemFaultedEvent` (`PlanItemCompletionApplier`, `ActionGateExpiredPlanItemHandler`,
`ActionGateRejectedPlanItemHandler`, `WorkerOutcomeResolvedHandler`) are unaffected by this
consolidation.

**Why single handler, not event chain:** Making PlanItemFaultHandler observe
`PlanItemFaultedEvent` would add latency and coupling. The operations are cheap and
logically sequential — no reason to decouple them.

---

### #663 — CaseInstance tenant mismatch after reactive→sync migration

**Root cause hypothesis:** After the reactive→sync migration, event bus handlers on worker
threads resolve a different `CurrentPrincipal.tenancyId()` than the one used at
`CaseInstance.save()` time. `InMemoryCaseInstanceRepository.findByUuid()` filters by
tenancyId — returns null when the thread's principal differs.

**Design:** The in-memory repositories exist for testing — strict tenancy enforcement in
test infrastructure masks real bugs rather than catching them. The real enforcement is in
`TenantAwareRepository` (JPA layer) via PostgreSQL RLS.

Fix `TestCaseInstanceRepository` in `casehub-engine-testing`:
- Override `findByUuid(UUID, String)` to delegate to `findByUuid(UUID)` (ignoring tenancyId)
- This matches how the test infrastructure is actually used — tests don't set up real
  multi-tenant scenarios

**Deeper fix:** Thread `tenancyId` through event bus messages so handler threads resolve
the correct principal. This is a larger change in the engine runtime — tracked as a
separate GitHub issue (to be filed at implementation time, not a sweep item). The test-repo
override is annotated with a `TODO` referencing that issue.

**Protocol note:** The `no-conditional-tenancy-filtering` protocol applies to production
code paths — "queries, events, cache keys, audit entries." The test repository is test
infrastructure, not a production query path. The `InMemoryCaseInstanceRepository` (which
`TestCaseInstanceRepository` extends) correctly enforces tenancy filtering; the test
subclass relaxes it specifically because test harnesses don't set up multi-tenant
`CurrentPrincipal` contexts.

**Verification:** Run the failing casehub-aml test suite against the fix to confirm the
34 failures resolve. (Out of scope for this branch — the fix is in engine-testing, consumers
will pick it up on next SNAPSHOT.)

---

## Cluster C — Registration-time enrichment

### #654 — Populate CaseMetaModel definition column during registration

**What:** In `DefaultCaseDefinitionRegistry.registerCaseDefinition()`, serialize the
`CaseDefinition` to JSON and set it on `CaseMetaModel.definition` before saving.

**Design:**

The `CaseDefinition` canonical model (Layer 3 per `case-definition-layers` protocol) contains
runtime artifacts that are not serializable: `Worker.function` holds `WorkerFunction` instances
(lambdas via `@FunctionalInterface`), and goal conditions may use `LambdaExpressionEvaluator`.
Per the `worker-function-execution-model` protocol, worker functions are runtime execution
artifacts — they have no YAML representation and must be excluded from persistence.

**Serialization approach — Jackson MixIn:**
```java
// Exclude Worker.function from serialization (lambda, not metadata)
abstract class WorkerSerializationMixIn {
    @JsonIgnore abstract WorkerFunction function();
}

// Dedicated ObjectMapper for CaseMetaModel.definition population
ObjectMapper metadataMapper = new ObjectMapper();
metadataMapper.addMixIn(Worker.class, WorkerSerializationMixIn.class);
```

**Fields captured in the JSON snapshot:**
- **Included:** namespace, name, version, title, summary, dsl, capabilities (name only),
  workers (name, capabilityNames, executionPolicy, description — NOT function), bindings
  (name, capability, when, producedKeys, inputMapping, outputMapping), milestones, goals,
  types, labels, stages, planningStrategy, cbrConfig, episodicMemoryConfig, agentDescriptors,
  semanticData, layerNames
- **Excluded:** `Worker.function` (lambda), any `LambdaExpressionEvaluator` instances
  (runtime-only, no string representation)

**Integration:**
```java
// After building the CaseDefinition, before repository.save():
JsonNode definitionJson = metadataMapper.valueToTree(definition);
model.setDefinition(definitionJson);
```

The `definition` column is already declared on `CaseMetaModel` — it's just never populated.

---

### #655 — Vocabulary validation for CaseDefinition types and labels

**What:** At registration time, validate type and label paths against the
`VocabularyRegistry` if available.

**Design:**
- Inject `Instance<VocabularyRegistry>` into `DefaultCaseDefinitionRegistry`
- In `validateExpressions()`, if `vocabularyRegistry.isResolvable()`:
  - For each type path: check if any segment resolves against known vocabularies
  - For each label path: same check
  - Log WARNING for unresolvable paths (not error — vocabulary might load later)
- When `NoOpVocabularyRegistry` is active (no eidos): skip validation silently

**Advisory, not enforcement.** Vocabularies are optional infrastructure. Validation
catches typos when the infrastructure exists but doesn't block registration when it doesn't.

---

## Cluster D — Bridge and Caching

### #661 — Extend QhorusMessageSignalBridge for STATUS messages

**What:** Route `MessageType.STATUS` messages through the bridge into case signals.

**Design:**
- Add `STATUS` to the set of handled message types (new method `isSignalWorthy()` —
  STATUS is not commitment-resolving, it's informational)
- STATUS messages route through `runtime.signal()` (not the failure outcome path)
- Signal payload: `{ "statusReport": { "from": <actorId>, "content": <messageContent>, "timestamp": <ts> } }`
- Milestone/sentry conditions can evaluate `.statusReport` in their JQ expressions

**Why a separate predicate, not extending `isCommitmentResolving()`:** STATUS is NOT a
commitment resolution — it doesn't close or satisfy a commitment. It's a progress report.
Conflating it with RESPONSE/DONE/DECLINE/FAILURE would break the semantic distinction.

```java
// New predicate alongside isCommitmentResolving():
private boolean isStatusUpdate(MessageType type) {
    return type == MessageType.STATUS;
}
```

The routing method checks `isCommitmentResolving() || isStatusUpdate()`, then branches:
commitment-resolving messages follow the existing path; STATUS follows the signal path
directly (no correlationId lookup, no worker outcome routing).

---

### #671 — Case-lifetime CBR retrieval caching

**What:** Add `CASE_LIFETIME` caching option to avoid per-evaluation CBR retrieval for
high-frequency tick applications.

**Design:**

Add to `CbrConfig`:
```java
public enum CbrRetrievalTiming { PER_EVALUATION, CASE_LIFETIME }
```
Default: `PER_EVALUATION` (current behaviour, no change for existing consumers).

Add cache to `CbrRetrievalService`:
```java
private final ConcurrentHashMap<UUID, List<RetrievedExperience>> cache = new ConcurrentHashMap<>();
private static final int MAX_CACHE_SIZE = 1000;
```

In `retrieve()`:
- If `timing == CASE_LIFETIME`, check cache by `caseId` first
- On cache miss, retrieve, wrap in `List.copyOf()` (immutable), and cache
- On cache hit, return cached value (already immutable)
- If cache size exceeds `MAX_CACHE_SIZE`, evict oldest entries before caching

**Immutability:** Cached lists are always `List.copyOf()` — concurrent readers cannot
corrupt shared state.

**Eviction:**
1. **Terminal state:** Observe `CaseStatusChangedHandler` — when a case reaches a terminal
   state (COMPLETED, FAULTED, CANCELLED), remove from cache. Uses the existing CDI event
   mechanism — no new infrastructure needed.
2. **Safety bound:** `MAX_CACHE_SIZE` prevents unbounded growth for non-terminal cases.
   When exceeded, evict entries to stay within bounds.

**Staleness trade-off:** `CASE_LIFETIME` caching means retrieval results are fixed at first
access and do not update as the case context evolves. This is the explicit design intent —
CBR retrieval is computationally expensive, and for high-frequency tick applications (the
primary use case), the relevant experiences are determined by the case's initial state. If
mid-case re-retrieval is needed, use `PER_EVALUATION` (the default).

**YAML:**
```yaml
cbr:
  timing: case-lifetime  # or per-evaluation (default)
```

---

## Cluster E — API Quality (POC Feature Restoration)

### #618 — ExecutionOrigin provenance metadata

**What:** Tag EventLog entries with the origination path of each execution.

**Design:**
```java
// api/src/main/java/io/casehub/api/model/event/ExecutionOrigin.java
public enum ExecutionOrigin {
    BINDING_DISPATCH,      // CaseContextChangedEventHandler matched a binding
    SIGNAL,                // External signal via CaseHubRuntime.signal()
    SCHEDULE_TRIGGER,      // ScheduledTrigger fired
    SUBCASE_COMPLETION,    // Child case completed, propagated result
    RECOVERY               // WorkerRecoveryCoordinator re-dispatched
}
```

**Dropped from issue's proposal (with rationale):**
- `PROVISIONED` — provisioning is a sub-step of `BINDING_DISPATCH`, not a separate origin.
  The provisioning step happens within the binding dispatch flow; tagging it separately would
  double-count the same execution path.
- `HUMAN_TASK_COMPLETION` — this is `SUBCASE_COMPLETION` or a `SIGNAL`; the human/machine
  distinction belongs on the actor, not the origin. A WorkItem completion enters the engine
  through the same signal/completion path regardless of whether a human or machine resolved it.

**Coverage of manual/administrative triggers:** A REST endpoint that directly starts a case
goes through `CaseHubRuntime.start()` — this is a case lifecycle event, not an execution
origin. `ExecutionOrigin` tags individual executions within a running case. A REST endpoint
that triggers a binding goes through `CaseHubRuntime.signal()`, which is `SIGNAL`.

**Integration points:**
- `EventLog.metadata` gains an `"origin"` field (string, set by each handler)
- `PlanExecutionContext` gains `ExecutionOrigin origin` field
- Handlers that create EventLog entries set the origin:
  - `CaseContextChangedEventHandler` → `BINDING_DISPATCH`
  - `SignalReceivedEventHandler` → `SIGNAL`
  - `SchedulerService` → `SCHEDULE_TRIGGER`
  - `SubCaseCompletionService` → `SUBCASE_COMPLETION`
  - `WorkerRecoveryCoordinator` → `RECOVERY`

---

### #619 — Per-key change listener API on CaseContext

**What:** Add `onChange()` and `onAnyChange()` to `CaseContext` for per-key observation.

**Design:**

```java
// CaseContext interface additions (default methods per spi-evolution-default-methods):
default Subscription onChange(String key, Consumer<ContextChangeEvent> listener) {
    return Subscription.NOOP;
}
default Subscription onAnyChange(Consumer<ContextChangeEvent> listener) {
    return Subscription.NOOP;
}

// ContextChangeEvent record:
record ContextChangeEvent(String key, Object oldValue, Object newValue) {}

// Subscription (for cleanup):
interface Subscription {
    void cancel();
    Subscription NOOP = () -> {};
}
```

**Implementation in `CaseContextImpl`:**
- Internal `ConcurrentHashMap<String, List<Consumer<ContextChangeEvent>>>` for per-key listeners
- Internal `CopyOnWriteArrayList<Consumer<ContextChangeEvent>>` for any-change listeners
- `WritableLayerImpl.set()` captures `(key, oldValue, newValue)` inside the write lock,
  then fires listeners **outside the write lock** after releasing it
- Listeners are per-CaseInstance (not global) — they travel with the context object

**Listener invocation contract:**
1. **Fired outside the write lock:** Old and new values are captured inside the lock
   (consistent snapshot). Listeners execute after the lock is released — no lock contention,
   no deadlock risk from re-entrant writes.
2. **Error isolation:** Each listener invocation is wrapped in try/catch. Exceptions are
   logged (WARN level) and never propagated — a failing listener does not affect the write
   operation or other listeners.
3. **Ordering:** Listeners for the same key fire in FIFO registration order. Any-change
   listeners fire after per-key listeners, also in registration order.
4. **Threading:** Listeners execute on the calling thread (the thread that called `set()`).
   They MUST be non-blocking.

**Why synchronous, not EventBus-backed:** The per-key listener is a data model concern —
"notify me when this field changes." It operates at a different abstraction level than
`CaseContextChangedEvent` (which is a coarse-grained, CaseInstance-level event fired after
a context-changing operation completes). The per-key listener is fine-grained and local;
the EventBus event is for cross-component communication. Making per-key notification async
via EventBus would add latency for a simple observer pattern where the consumer is co-located
with the context.

**Contract testing:** Per `spi-default-method-contract-test` protocol, add an anonymous
`CaseContext` implementation test verifying that the default `onChange()` and `onAnyChange()`
methods compile and return no-op subscriptions.

**Scope concern:** Listeners are registered on a CaseInstance's context. They live as long
as the CaseInstance is in the cache. No explicit lifecycle management beyond `Subscription.cancel()`.

---

### #617 — RetryState explicit retry attempt history

**What:** An explicit record tracking every retry attempt for a worker execution.

**Design:**
```java
// api/src/main/java/io/casehub/api/model/RetryState.java
public record RetryState(
    int attemptCount,
    List<RetryAttempt> attempts,
    Instant firstAttemptTime,
    Instant lastAttemptTime
) {
    public record RetryAttempt(
        Instant timestamp,
        String errorMessage,
        Duration duration,
        boolean succeeded
    ) {}

    public static RetryState empty() {
        return new RetryState(0, List.of(), null, null);
    }
}
```

**Population:** `QuartzRetryService` (scheduler-quartz) builds `RetryState` from the
failure chain. Each `WORKER_EXECUTION_FAILED` EventLog entry contributes a `RetryAttempt`.
On retry exhaustion, the full `RetryState` is attached to the `WorkerRetriesExhaustedEvent`.

**Integration points:**
- `PlanExecutionContext` gains `RetryState retryState` (nullable — present only when
  the current execution is a retry)
- `DeadLetterEntry` gains `RetryState retryState` — full history travels to the DLQ
- `PlanningStrategy.select()` can inspect retry history via `PlanExecutionContext`

**Why in `api/`, not `resilience/`:** `PlanExecutionContext` is in `api/`, so `RetryState`
must be accessible from `api/`. The resilience module is a consumer (populates it), not
the owner (defines it).

---

### #616 — CaseFileContribution key-level audit trail

**What:** Track which bindings produce which context keys, both statically (declared) and
at runtime (observed).

**Design — two layers:**

**Layer 1 — Static declaration on Binding:**
```java
// Binding.Builder addition:
.producedKeys("risk_score", "compliance_status")
```

`producedKeys` is `Set<String>` on `Binding` — the keys this binding is expected to write
to the case context. Populated from YAML:
```yaml
bindings:
  - name: analyse-risk
    capability: risk-analysis
    producedKeys: [risk_score, compliance_status]
```

**YAML schema location:** `producedKeys` is a sibling of `capability`, `when`, and
`inputMapping` in the binding block. Schema path: `CaseDefinition.yaml` →
`properties.spec.properties.bindings.items.properties.producedKeys` (array of strings).

Registration-time validation: warn if two bindings in the same stage declare overlapping
`producedKeys` (potential write conflict). Enables future static cycle detection without
waiting for runtime.

**Cross-stage key overwriting is intentional.** Two bindings in different stages producing
the same key is a valid refinement pipeline pattern (stage 2 overwrites stage 1's output).
This is documented platform behavior, not a bug. Same-stage overlap is the warning trigger
because it indicates a potential race condition between concurrent bindings.

**Layer 2 — Runtime audit in EventLog:**
The existing `contextChanges` metadata on `WORKER_EXECUTION_COMPLETED` EventLog entries
already captures the diff. Add a `producedKeys` field to the metadata — the set of
top-level keys that were actually written:
```json
{ "producedKeys": ["risk_score", "compliance_status"], "contextChanges": {...} }
```

Extracted from the context diff in `WorkflowExecutionCompletedHandler`. This is the
observed audit trail — what actually happened, not what was declared.

**Why both:** Static declarations enable analysis before execution (cycle detection,
dependency graphs, PlanningStrategy reasoning). Runtime audit captures what actually
happened (conditional outputs, unexpected writes). Neither replaces the other.

---

## Platform coherence review

Checked against PLATFORM.md and relevant protocols:

- **Module tier structure:** All new types (`ExecutionOrigin`, `RetryState`,
  `ContextChangeEvent`, `Subscription`) go in `api/` (Tier 1, pure Java). Correct.
- **SPI evolution:** New methods on `CaseContext` are additions to an existing interface.
  Per `spi-evolution-default-methods` protocol, add as default methods with safe no-op
  returns. `onChange()` default returns a no-op `Subscription`; `onAnyChange()` same.
- **SPI contract testing:** Per `spi-default-method-contract-test` protocol, new default
  methods on `CaseContext` require a contract test with an anonymous implementation
  verifying the default methods compile and return correct no-op values.
- **engine-spi-noops-defaultbean:** No new SPIs — all changes are to existing types.
- **No Flyway:** No schema changes. `CaseMetaModel.definition` column already exists.
- **YAML schema:** `producedKeys` and `cbr.timing` need schema additions in
  `CaseDefinition.yaml`.
- **case-definition-layers protocol:** #654's serialization aligns with Layer 1 (YAML
  structure) — runtime artifacts (WorkerFunction, LambdaExpressionEvaluator) are excluded
  via Jackson MixIn. The serialized JSON captures the YAML-representable metadata subset.
- **worker-function-execution-model protocol:** #654 correctly excludes `Worker.function`
  from serialization — worker functions are runtime execution artifacts per this protocol.
- **sweep-blocked-item-process protocol:** All 13 in-scope issues are covered. Three
  deferred items (#635, #648, #672) are documented with cross-repo scope breakdowns.

---

## Cluster ordering and dependencies

**Cluster independence:** Clusters A, C, D, and E are code-independent — they touch
different modules and can be landed in any order. Cluster B touches `blackboard/` handlers.
No cluster depends on another cluster's changes.

**Intra-cluster dependency:** #673 (CbrConfig validation) and #671 (CbrConfig caching)
both touch `CbrConfig`. They are in different clusters (A and D) but must be coordinated:
#673 validates the config fields that #671 adds (`timing`). Implementation order: #671
first (adds the `timing` enum and YAML schema), then #673 (validates all CbrConfig fields
including the new one).

**Commit strategy:** One commit per issue within the sweep branch. Each commit references
its issue number. The branch is squash-merged to main via `work-end`.

**Revert granularity:** Because each issue is a separate commit, any issue can be reverted
independently without affecting others — even within the same cluster.

---

## Issues deferred (cross-repo)

- #635 — io.casehub.api rename (14+ repos, labeled L/cross-repo, scope breakdown filed)
- #648 — OutcomeRecorder.addAttestation (casehub-ledger SPI, scope breakdown filed)
- #672 — feature-level similarity (casehub-neocortex API, scope breakdown filed)

---

## Verification plan

1. **Per-issue TDD:** Each issue gets a failing test before implementation
2. **Module-level suites:** After each cluster, run the affected module's full test suite
3. **Full build:** After all clusters, run full `mvn test` across all modules
4. **CI:** Push and verify green

**High-risk item test scenarios:**

- **#666 (handler consolidation):** Test that a single `WorkerRetriesExhaustedEvent`
  results in both `PlanItemFaultedEvent` firing AND `stageAutocompleteEvaluator.evaluate()`
  being called. Verify with a CDI event observer counting `PlanItemFaultedEvent` deliveries
  and a mock/spy on `StageAutocompleteEvaluator`. Negative test: verify that deleting
  `PlanItemFaultHandler` causes no test failures (it's fully subsumed).
- **#619 (per-key listeners):** Test listener invocation on key change with correct old/new
  values. Test error isolation: a throwing listener does not prevent subsequent listeners
  from firing. Test that listeners fire outside the write lock: register a listener that
  acquires a write lock on the same context (would deadlock if fired inside the lock).
  Contract test for `CaseContext` default methods per `spi-default-method-contract-test`.
- **#671 (CBR cache):** Test cache eviction on terminal case state (COMPLETED, FAULTED,
  CANCELLED). Test that cached lists are immutable (`List.copyOf()`). Test MAX_CACHE_SIZE
  bound — cache more than MAX_CACHE_SIZE entries, verify size stays bounded.
- **#663 (tenancy bypass):** The 34 casehub-aml test failures are verified when casehub-aml
  picks up the next engine-testing SNAPSHOT. Track this as a verification step in the
  casehub-aml repo, not in this sweep branch. File a GitHub issue for the deeper fix
  (threading tenancyId through event bus messages).
