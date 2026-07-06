# PlanItem CAS Loops + Repository Naming Cleanup

**Issues:** #649, #662
**Date:** 2026-07-06

## 1. PlanItem Multi-Source-State CAS Loops (#649)

### Problem

`PlanItem` status transitioned from `volatile` to `AtomicReference` in #637.
Single-source-state methods (`markRunning`, `markDelegated`, `markRejected`,
`markSuspended`, `markResumed`) correctly use `compareAndSet`. Four multi-source-state
methods still use get-then-set — a TOCTOU gap:

- `markCompleted()` — accepts RUNNING or DELEGATED
- `markFaulted()` — accepts any active (non-terminal)
- `markObsolete()` — accepts any active (non-terminal)
- `markCancelled()` — accepts any active (non-terminal)

Two race paths exist:
1. **RUNNING PlanItems (CapabilityTarget):** `markCompleted` (via
   `WorkItemLifecycleAdapter` → `PlanItemCompletionApplier`) and `markFaulted`
   (via `WorkerRetryExhaustionHandler`) can race when worker completion and
   retry exhaustion arrive concurrently.
2. **DELEGATED PlanItems (HumanTask, SubCase):** concurrent terminal
   `WorkItemEvent`s (e.g., COMPLETED vs EXPIRED) both route through
   `PlanItemCompletionApplier.applyStatus`, which already catches ISE.

Both `WorkerRetryExhaustionHandler` and `PlanItemFaultHandler` subscribe to
`WORKER_RETRIES_EXHAUSTED` (fan-out via `eventBus.publish()`). Both call
`markFaulted()` on the same PlanItem — the CAS loop ensures exactly one wins.
Both handlers must catch `IllegalStateException` and log at debug level,
following the pattern established in `PlanItemCompletionApplier.applyStatus`.
Handler consolidation is tracked in #666.

### Design

Convert all four methods to explicit CAS loops. Guard logic is unchanged —
only the atomicity mechanism changes.

**`markCompleted()`** — CAS loop, accepts RUNNING or DELEGATED:
```java
public void markCompleted() {
    while (true) {
        PlanItemStatus current = status.get();
        if (current != PlanItemStatus.RUNNING && current != PlanItemStatus.DELEGATED) {
            throw new IllegalStateException(
                "Cannot transition to COMPLETED from " + current
                + " (planItemId=" + planItemId + ")");
        }
        if (status.compareAndSet(current, PlanItemStatus.COMPLETED)) return;
    }
}
```

**`markFaulted()`, `markObsolete()`, `markCancelled()`** — CAS loop, guard on
`!isTerminal()`:
```java
public void markFaulted() {
    while (true) {
        PlanItemStatus current = status.get();
        if (current.isTerminal()) {
            throw new IllegalStateException(
                "Cannot fault a terminal PlanItem (status=" + current
                + ", planItemId=" + planItemId + ")");
        }
        if (status.compareAndSet(current, PlanItemStatus.FAULTED)) return;
    }
}
```

Same pattern for `markObsolete` and `markCancelled` (substituting the target status
and error message verb).

### Testing

Add concurrent transition tests to `PlanItemTest`:
- Two threads racing `markCompleted()` on a RUNNING item — one succeeds, one
  gets `IllegalStateException` (CAS fails, retry reads terminal, guard throws)
- Two threads racing `markFaulted()` vs `markCompleted()` — exactly one wins
- Verify no silent overwrites (the core bug the CAS loop prevents)
- Integration: fire `WORKER_RETRIES_EXHAUSTED` concurrently with a COMPLETED
  `WorkItemEvent` for the same RUNNING PlanItem — exactly one transition wins,
  the other logs debug (not error)

### Files changed

| File | Change |
|------|--------|
| `blackboard/.../plan/PlanItem.java` | CAS loops for 4 methods |
| `blackboard/.../plan/PlanItemTest.java` | Concurrent transition tests |
| `blackboard/.../handler/WorkerRetryExhaustionHandler.java` | Catch ISE on `markFaulted`, log debug |
| `blackboard/.../handler/PlanItemFaultHandler.java` | Catch ISE on `markFaulted`, log debug |

---

## 2. Repository Naming Cleanup (#662)

### Problem

`CaseMetaModelRepository` and `SubCaseGroupRepository` return `Uni<>` but lack
the `Reactive` prefix. Every other repository pair follows the dual-stack
convention: unqualified = blocking, `Reactive` prefix = Uni-based.

Neither has a blocking counterpart.

### Design

Full dual-stack for both repositories, following the established pattern exactly.

#### SPI interfaces (common/spi/)

| Current | After |
|---------|-------|
| `CaseMetaModelRepository` (Uni) | `ReactiveCaseMetaModelRepository` (Uni) |
| — | `CaseMetaModelRepository` (blocking) |
| `SubCaseGroupRepository` (Uni) | `ReactiveSubCaseGroupRepository` (Uni) |
| — | `SubCaseGroupRepository` (blocking) |

Blocking interfaces mirror the reactive signatures with plain return types.
`@see` cross-references link the pairs.

#### In-memory implementations (blocking canonical, reactive delegates)

| Current | After |
|---------|-------|
| `InMemoryCaseMetaModelRepository` (Uni) | `InMemoryCaseMetaModelRepository` (blocking, stores data) |
| — | `InMemoryReactiveCaseMetaModelRepository` (delegates to blocking) |
| `MemorySubCaseGroupRepository` (Uni) | `InMemorySubCaseGroupRepository` (blocking, stores data) |
| — | `InMemoryReactiveSubCaseGroupRepository` (delegates to blocking) |

`MemorySubCaseGroupRepository` is also renamed to `InMemorySubCaseGroupRepository`
for consistency with the `InMemory*` naming convention used by all other in-memory
implementations.

#### JPA implementations (reactive canonical, blocking awaits)

| Current | After |
|---------|-------|
| `JpaCaseMetaModelRepository` (Uni) | `JpaReactiveCaseMetaModelRepository` (reactive, canonical) |
| — | `JpaCaseMetaModelRepository` (blocking, awaits reactive) |
| `JpaSubCaseGroupRepository` (Uni) | `JpaReactiveSubCaseGroupRepository` (reactive, canonical) |
| — | `JpaSubCaseGroupRepository` (blocking, awaits reactive) |

#### Injection sites

All injection sites currently inject the (misnamed) reactive interface. After
rename, they inject `ReactiveCaseMetaModelRepository` / `ReactiveSubCaseGroupRepository`
— same behavior, correct name.

**Production injection sites:**
- `DefaultCaseDefinitionRegistry` — injects `CaseMetaModelRepository` (reactive usage)
- `SubCaseCompletionService` — injects `SubCaseGroupRepository` (reactive usage)
- `SubCaseExecutionHandler` — injects `SubCaseGroupRepository` (reactive usage)

**Test `application.properties` `selected-alternatives` updates** (7 files):

Per module, two operations per repository pair:

| Operation | CaseMetaModel | SubCaseGroup |
|-----------|---------------|--------------|
| Existing entry | `InMemoryCaseMetaModelRepository` (name unchanged, becomes blocking) | `MemorySubCaseGroupRepository` → rename to `InMemorySubCaseGroupRepository` |
| New entry | ADD `InMemoryReactiveCaseMetaModelRepository` | ADD `InMemoryReactiveSubCaseGroupRepository` |

Modules requiring updates:

| Module | CaseMetaModel pair | SubCaseGroup pair |
|--------|-------------------|-------------------|
| blackboard | yes | yes |
| actor-state | yes | yes |
| flow | yes | yes |
| resilience (main) | yes | — |
| resilience (test) | yes | — |
| runtime (memory profile) | yes | — |
| work-adapter | yes | yes |

This follows the existing dual-stack pattern — e.g., `InMemoryCaseInstanceRepository` +
`InMemoryReactiveCaseInstanceRepository` are already listed together in the same
properties files. The blocking canonical is needed by the reactive delegate.

**Testing module:** `TestCaseMetaModelRepository` extends `InMemoryCaseMetaModelRepository`
(no explicit `implements` clause). After the change, it inherits the blocking
`CaseMetaModelRepository` interface from its parent. Javadoc `@link` updated.

### Cross-repo impact

Two devtown classes extend renamed engine implementations:
- `DevtownCaseMetaModelRepository extends InMemoryCaseMetaModelRepository`
- `DevtownSubCaseGroupRepository extends MemorySubCaseGroupRepository`

Both need updating after this rename — tracked in #667. No other application-tier
repos extend or inject these engine SPIs directly.

### Files changed

| File | Change |
|------|--------|
| `common/spi/CaseMetaModelRepository.java` | New blocking interface |
| `common/spi/ReactiveCaseMetaModelRepository.java` | Renamed from CaseMetaModelRepository |
| `common/spi/SubCaseGroupRepository.java` | New blocking interface |
| `common/spi/ReactiveSubCaseGroupRepository.java` | Renamed from SubCaseGroupRepository |
| `persistence-memory/InMemoryCaseMetaModelRepository.java` | Rewrite to blocking canonical |
| `persistence-memory/InMemoryReactiveCaseMetaModelRepository.java` | New reactive delegate |
| `persistence-memory/InMemorySubCaseGroupRepository.java` | Renamed + rewrite to blocking |
| `persistence-memory/InMemoryReactiveSubCaseGroupRepository.java` | New reactive delegate |
| `persistence-hibernate/JpaReactiveCaseMetaModelRepository.java` | Renamed from JpaCaseMetaModelRepository |
| `persistence-hibernate/JpaCaseMetaModelRepository.java` | New blocking, awaits reactive |
| `persistence-hibernate/JpaReactiveSubCaseGroupRepository.java` | Renamed from JpaSubCaseGroupRepository |
| `persistence-hibernate/JpaSubCaseGroupRepository.java` | New blocking, awaits reactive |
| `runtime/DefaultCaseDefinitionRegistry.java` | Import update |
| `blackboard/SubCaseCompletionService.java` | Import update |
| `blackboard/SubCaseExecutionHandler.java` | Import update |
| `testing/TestCaseMetaModelRepository.java` | Inherits blocking interface; Javadoc `@link` updated |
| 7 `application.properties` files | `selected-alternatives` class name updates |
| 5 test files | Import updates |
| `persistence-hibernate` 2 test files | Rename test classes |
| `persistence-memory` 2 test files | Rename test classes |

### CLAUDE.md update

Three CLAUDE.md sections reference renamed classes:

**Line 109** (Persistence architecture — SPI listing):

**Before:** `CaseMetaModelRepository`, ... `SubCaseGroupRepository`, ...

**After:** `ReactiveCaseMetaModelRepository` (Uni<>), `CaseMetaModelRepository` (blocking),
`ReactiveSubCaseGroupRepository` (Uni<>), `SubCaseGroupRepository` (blocking), ...

The dual-stack convention note already present ("unqualified = blocking, `Reactive`
prefix = Uni-based") applies correctly — these two repositories now conform to it.

**Line 378** (Test conventions — selected-alternatives example):

**Before:** `...including MemorySubCaseGroupRepository`

**After:** `...including InMemorySubCaseGroupRepository, InMemoryReactiveSubCaseGroupRepository`

**Line 453** (Test setup — unsatisfied dependency error message):

**Before:** `Unsatisfied dependency for SubCaseGroupRepository`

**After:** `Unsatisfied dependency for ReactiveSubCaseGroupRepository` (injection sites
request the reactive interface after rename).
