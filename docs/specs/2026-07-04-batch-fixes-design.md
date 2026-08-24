# Batch Fixes — engine#629, #636, #637, #641, #642, #643

**Date:** 2026-07-04
**Issues:** engine#629, #636, #637, #641, #642, #643
**Status:** Design
**Branch:** issue-636-worker-runtime-batch-fixes

---

## 1. WorkerRecoveryHealthCheck @Readiness (#629)

### Problem

`WorkerRecoveryHealthCheck` is annotated `@Liveness`. A failed recovery marks the pod as not-live, triggering Kubernetes restarts. But a failed recovery doesn't mean the JVM is hung — the engine is still serving requests. Restarting won't fix a persistent recovery failure; it creates a restart loop.

Library modules should not define liveness checks — liveness is a deployment-layer concern. The scaffold's `ApplicationLivenessCheck` is the sole liveness authority.

### Changes

| File | Change |
|------|--------|
| `runtime/.../recovery/WorkerRecoveryHealthCheck.java` | `@Liveness` → `@Readiness` (import swap) |
| `runtime/.../recovery/WorkerRecoveryHealthCheckTest.java` | Update assertions if they reference liveness semantics |
| `CLAUDE.md` | Update recovery health check documentation line |

No behavioral change. The check still reports UP/DOWN based on `RecoveryStatus`. It moves from `/q/health/live` to `/q/health/ready`.

---

## 2. GateRequired CandidateSetStrategy Evaluation Upstream (#641)

### Problem

`ActionGateWorkItemHandler.candidateGroupsCsv()` evaluates `CandidateSetStrategy` with `new CandidateSetContext(null)` — null case context. Dynamic strategies like `JqCandidateSetStrategy` that evaluate JQ against the case context silently return empty/wrong results.

The handler lives in `work-adapter` — it has no case context available. The evaluation must happen upstream in `WorkflowExecutionCompletedHandler.handleGate()` where `CaseInstance` is in scope.

### Design

Evaluate the strategy upstream, carry resolved groups in the event, remove routing dependency from the handler.

### Changes

**`ActionGateScheduleRequest`** (common) — Add `resolvedCandidateGroups` field:

```java
public record ActionGateScheduleEvent(
    UUID caseId,
    String tenancyId,
    long gateId,
    PlannedAction plannedAction,
    RiskDecision.GateRequired gateRequired,
    Set<String> resolvedCandidateGroups) {}
```

**`WorkflowExecutionCompletedHandler.handleGate()`** (runtime) — Evaluate `CandidateSetStrategy` before publishing the event:

```java
private Uni<Void> handleGate(..., RiskDecision.GateRequired gate, ...) {
    // Evaluate candidateGroups with case context
    Uni<Set<String>> groupsUni;
    if (gate.candidateGroups() != null) {
        JsonNode contextNode = caseInstance.getCaseContext()
            .panel(ContextPanel.WORKING).asJsonNode();
        groupsUni = gate.candidateGroups()
            .evaluate(new CandidateSetContext(contextNode));
    } else {
        groupsUni = Uni.createFrom().item(Set.of());
    }
    
    return groupsUni
        .onFailure()
        .recoverWithUni(t -> {
            LOG.warnf(t,
                "CandidateSetStrategy evaluation failed for caseId=%s — "
                + "proceeding with empty candidate groups",
                caseInstance.getUuid());
            return Uni.createFrom().item(Set.of());
        })
        .chain(resolvedGroups -> {
            // ... existing gate logic, pass resolvedGroups to event
            eventBus.publish(EventBusAddresses.ACTION_GATE_SCHEDULE,
                new ActionGateScheduleEvent(
                    caseInstance.getUuid(), caseInstance.tenancyId,
                    gateEventLog.id, plannedAction, gate, resolvedGroups));
            // ...
        });
}
```

**`ActionGateWorkItemHandler`** (work-adapter) — Remove `candidateGroupsCsv()` method. Read pre-resolved groups:

```java
public void onActionGateSchedule(final ActionGateScheduleEvent event) {
    Set<String> groups = event.resolvedCandidateGroups();
    String candidateGroupsCsv = (groups == null || groups.isEmpty())
        ? null : String.join(",", groups);
    // ... build WorkItemCreateRequest with candidateGroupsCsv
}
```

**Invariant:** The strategy is always evaluated where context exists. The event carries data, not behavior.

---

## 3. PlanItem CAS Guard (#637)

### Problem

`PlanItem.status` is `volatile` with check-then-set transitions — not atomic. Concurrent `CONTEXT_CHANGED` evaluations (Vert.x worker threads) can both read PENDING and both dispatch the same binding.

This is a production race condition, not just a test issue. `@ConsumeEvent(blocking=true)` handlers run on a worker pool — concurrent processing of events for the same case IS expected.

### Design — Option A (CAS guard)

Make PlanItem status transitions atomic via `AtomicReference`. Add `tryMarkRunning()` for use in dispatch paths where concurrent callers are expected.

### Changes

**`PlanItem`** (blackboard):

```java
// Before
private volatile PlanItemStatus status;

// After
private final AtomicReference<PlanItemStatus> status;
```

All `status` reads → `status.get()`. All `status = X` → `status.set(X)`. New method:

```java
public boolean tryMarkRunning() {
    return status.compareAndSet(PlanItemStatus.PENDING, PlanItemStatus.RUNNING);
}
```

Existing `markRunning()` updated to use CAS too — consistent with `tryMarkRunning()`:

```java
public void markRunning() {
    if (!status.compareAndSet(PlanItemStatus.PENDING, PlanItemStatus.RUNNING)) {
        throw new IllegalStateException(
            "Cannot transition to RUNNING from " + status.get());
    }
}
```

Same CAS, different error handling: `markRunning()` throws, `tryMarkRunning()` returns false. Callers that expect exclusive access get reliable violation detection via CAS instead of a racy check-then-set.

**`PlanningStrategyLoopControl`** (blackboard) — Merge `filterToDispatchable` and `indexSelectedForCompletion` into a single atomic step. Change the terminal `.map()` + `.invoke()` chain to a single `.map()` that uses `tryMarkRunning()` as the atomic filter-and-transition for CapabilityTarget bindings:

```java
// Before (two steps — TOCTOU gap between filter and index):
//   .map(selected -> filterToDispatchable(plan, selected))
//   .invoke(dispatchable -> indexSelectedForCompletion(caseId, dispatchable, plan));

// After (single atomic step):
    .map(selected -> filterAndIndexForDispatch(caseId, plan, selected));
```

```java
private List<Binding> filterAndIndexForDispatch(
    UUID caseId, CasePlanModel plan, List<Binding> selected) {
  List<Binding> dispatched = new ArrayList<>();
  for (Binding binding : selected) {
    Optional<PlanItem> piOpt = plan.getPlanItemByBindingName(binding.getName());
    if (piOpt.isEmpty()) {
      dispatched.add(binding); // new binding, no PlanItem yet
      continue;
    }
    PlanItem pi = piOpt.get();
    if (binding.target() instanceof CapabilityTarget) {
      // Atomic CAS: only the thread that wins transitions PENDING→RUNNING
      if (pi.tryMarkRunning()) {
        registry.indexForCompletion(caseId, pi.getWorkerName(), pi.getPlanItemId());
        dispatched.add(binding);
      }
      // Losing thread: binding excluded from returned list — no dispatch
    } else {
      // Non-capability targets: handler owns the RUNNING/DELEGATED transition.
      // Status check only — no CAS here.
      if (pi.getStatus() == PlanItemStatus.PENDING) {
        dispatched.add(binding);
      }
    }
  }
  return dispatched;
}
```

Only bindings that win the CAS are returned to the caller for dispatch. The losing thread's binding is excluded from the list — `publishByTarget()` never fires for it.

**Follow-on issue:** Per-case CONTEXT_CHANGED serialization (Option B) — file as a new GitHub issue after implementation. For CapabilityTarget bindings, the CAS guard prevents double-dispatch but doesn't eliminate wasted concurrent evaluation. For non-CapabilityTarget bindings (HumanTask, SubCase, Extension), the TOCTOU gap on the PENDING check remains — their handlers own the transition but double-dispatch of events is still possible. Option B eliminates the race class entirely for all target types but requires dedicated analysis of re-entrant paths, deadlock surface, and contention impact.

---

## 4. EngineStrategyResolver Default Determinism (#642)

### Problem

`defaults.putIfAbsent(iface, strategy)` makes the first CDI-iterated strategy the default for each type. CDI iteration order is not deterministic. The default strategy for a type should be the one annotated `@DefaultBean`, not whichever happens to iterate first.

### Design

Use Quarkus ARC's `InjectableBean.isDefaultBean()` to identify `@DefaultBean`-annotated strategies. The `EngineStrategyResolver` is already deeply Quarkus-specific (`@Alternative @Priority(1) @ApplicationScoped`) — Quarkus ARC coupling is appropriate.

### Changes

**`EngineStrategyResolver`** (runtime) — Restructure constructor to iterate `Instance.Handle<>`:

```java
@Inject
public EngineStrategyResolver(
    @Any Instance<AgentRoutingStrategy> agentStrategies,
    @Any Instance<ImplementationRoutingStrategy> implStrategies,
    @Any Instance<CandidateMatchingStrategy> matchStrategies,
    @Any Instance<CandidateSetStrategy> candidateSetStrategies,
    @Any Instance<WorkerExecutionRoutingStrategy> execStrategies,
    @Any Instance<TrustRoutingPolicyProvider> trustStrategies) {
  this.index = new HashMap<>();
  this.defaults = new HashMap<>();

  registerStrategies(agentStrategies);
  registerStrategies(implStrategies);
  registerStrategies(matchStrategies);
  registerStrategies(candidateSetStrategies);
  registerStrategies(execStrategies);
  registerStrategies(trustStrategies);

  // log discovered strategies
}

private <T extends NamedStrategy> void registerStrategies(Instance<T> instance) {
  for (Instance.Handle<T> handle : instance.handles()) {
    T strategy = handle.get();
    boolean isDefault = (handle.getBean() instanceof InjectableBean<?> ib)
        && ib.isDefaultBean();
    for (Class<?> iface : resolveStrategyTypes(strategy.getClass())) {
      Map<String, NamedStrategy> byId =
          index.computeIfAbsent(iface, k -> new LinkedHashMap<>());
      NamedStrategy existing = byId.put(strategy.id(), strategy);
      if (existing != null) {
        throw new IllegalStateException("Duplicate strategy id '" + strategy.id()
            + "' for type " + iface.getSimpleName());
      }
      if (isDefault) {
        NamedStrategy existingDefault = defaults.put(iface, strategy);
        if (existingDefault != null
            && existingDefault != strategy) {
          throw new IllegalStateException(
              "Multiple @DefaultBean strategies for type "
              + iface.getSimpleName() + ": "
              + existingDefault.getClass().getName() + " and "
              + strategy.getClass().getName());
        }
      } else {
        defaults.putIfAbsent(iface, strategy);
      }
    }
  }
}
```

`@DefaultBean` strategies explicitly win. Non-`@DefaultBean` strategies only become default as a fallback if no `@DefaultBean` exists for that type (with a logged warning).

---

## 5. WorkerRuntime.spawnCase() / awaitCase() (#636)

### Problem

`WorkerRuntime.spawnCase()`, `awaitCase()`, and `spawnAndAwaitCase()` are TODO stubs that throw `UnsupportedOperationException`. Tier 1 orchestration needs cross-case coordination — a worker function should be able to spawn a child case and block until it completes.

### Design

Three components: SPI addition for name-based lookup, a CompletableFuture-based tracker for case completion, and the runtime implementation.

### 5.1. CaseDefinitionRegistry.findByName() — SPI addition

**`CaseDefinitionRegistry`** (common/spi) — New `default` method per SPI evolution protocol:

```java
default Optional<CaseDefinition> findByName(String name) {
    return Optional.empty();
}
```

Returns `Optional<CaseDefinition>` (not `CaseMetaModel`) because the caller needs the definition to call `startCase()`.

**`DefaultCaseDefinitionRegistry`** (runtime) — Implementation scans the registry map:

```java
@Override
public Optional<CaseDefinition> findByName(String name) {
    List<RegistryEntry> matches = registry.values().stream()
        .filter(e -> name.equals(e.definition().getName()))
        .toList();
    if (matches.isEmpty()) return Optional.empty();
    if (matches.size() > 1) {
        throw new IllegalArgumentException(
            "Ambiguous caseType '" + name + "' — matches " + matches.size()
            + " definitions across namespaces. Use qualified lookup to disambiguate.");
    }
    return Optional.of(matches.get(0).definition());
}
```

In Tier 1 (in-process, same JVM), case names are almost always unique within a deployment. Ambiguity throws with a clear message.

### 5.2. CaseCompletionTracker

**New file:** `runtime/src/main/java/io/casehub/engine/internal/engine/CaseCompletionTracker.java`

```java
@ApplicationScoped
public class CaseCompletionTracker {

  private final ConcurrentHashMap<UUID, CompletableFuture<CaseContext>> pending =
      new ConcurrentHashMap<>();

  public CompletableFuture<CaseContext> register(UUID caseId) {
    return pending.computeIfAbsent(caseId, k -> new CompletableFuture<>());
  }

  public void complete(UUID caseId, CaseContext context) {
    CompletableFuture<CaseContext> future = pending.get(caseId);
    if (future != null) {
      future.complete(context);
    }
  }

  public void completeExceptionally(UUID caseId, Throwable t) {
    CompletableFuture<CaseContext> future = pending.get(caseId);
    if (future != null) {
      future.completeExceptionally(t);
    }
  }

  public void remove(UUID caseId) {
    pending.remove(caseId);
  }
}
```

Thread-safe. Only `register()` creates futures — `complete()`/`completeExceptionally()` use `get()` and only act on futures that an `awaitCase()` caller has registered. This avoids orphan creation: `CaseStatusChangedHandler` calls `complete()` for every terminal case, but only cases with a registered awaiter create map entries. Cleanup via `remove()` in the caller's `finally` block.

The out-of-order race (child completes between `spawnCase()` return and `awaitCase()` call) is handled by a cache-based race guard in `awaitCase()` — see §5.4. `CaseInstanceCacheImpl` is a `ConcurrentHashMap` with no eviction policy, so terminal instances are always present when the guard checks.

### 5.3. CaseStatusChangedHandler — wire tracker

**`CaseStatusChangedHandler`** (runtime) — Inject `CaseCompletionTracker`. Notify on terminal state, AFTER state is persisted but BEFORE infrastructure cleanup (channel close, gate cancel, trigger cancel). The snapshot is taken at notification time, capturing the final context before cleanup modifies case state. Pass a snapshot (not the live reference) to prevent data races with concurrent handlers:

```java
@Inject CaseCompletionTracker caseCompletionTracker;

// In onCaseStatusChangedHandler(), inside the terminal state block:
if (isTerminalState(newState)) {
    CaseContext contextSnapshot = caseInstance.getCaseContext().snapshot();
    if (newState == CaseStatus.COMPLETED) {
        caseCompletionTracker.complete(caseInstance.getUuid(), contextSnapshot);
    } else {
        caseCompletionTracker.completeExceptionally(
            caseInstance.getUuid(),
            new CaseTerminatedException(caseInstance.getUuid(), newState));
    }
    // ... existing: close channels, cancel gate, cancel triggers
}
```

- **Snapshot:** `CaseContext.snapshot()` returns an immutable copy. The awaiting thread gets a frozen view of the final state — no data race with concurrent `getCaseContext().set()` calls from other handlers.
- **FAULTED/CANCELLED → `completeExceptionally()`:** A faulted or cancelled child case is an exceptional condition. The awaiting thread receives a `CaseTerminatedException` (carrying the terminal status and case ID) via `ExecutionException`, making the `awaitCase()` catch block operational.

`CaseTerminatedException` is a new `RuntimeException` subclass in `engine-common`:

```java
public class CaseTerminatedException extends RuntimeException {
    private final UUID caseId;
    private final CaseStatus terminalStatus;
    // constructor, getters
}
```

Tracker notification happens BEFORE infrastructure cleanup (channel close, gate cancel, trigger cancel) and BEFORE event bus publishes (`CASE_COMPLETED`/`CASE_FAULTED`). The awaiting worker thread unblocks with the context snapshot taken at the moment of terminal state transition.

### 5.4. DefaultWorkerRuntime — implement methods

**Constructor** — Add `CaseCompletionTracker tracker` parameter.

**`spawnCase(caseType, input)`:**

```java
@Override
public UUID spawnCase(String caseType, Map<String, Object> input) {
    CaseDefinition definition = definitionRegistry.findByName(caseType)
        .orElseThrow(() -> new IllegalArgumentException(
            "No case definition found for caseType: " + caseType));

    CaseInstance parentInstance = caseInstanceCache.get(caseId);
    PropagationContext propagation = parentInstance != null
        ? parentInstance.getPropagationContext() : null;

    try {
        return caseHubRuntime.startCase(definition, input, caseId, propagation)
            .toCompletableFuture().join();
    } catch (Exception e) {
        throw new RuntimeException("Failed to spawn case '" + caseType + "'", e);
    }
}
```

- Links parent-child via `parentCaseId` (matches SubCase behavior)
- Inherits `PropagationContext` from parent (deadline budget, lineage)
- Blocks on `join()` — runs on a virtual thread, blocking is expected at Tier 1
- **Tenancy:** follows the same `CaseHubRuntime.startCase(definition, input, parentCaseId, propagation)` path as `SubCaseExecutionHandler`. Tenancy is resolved by `CaseHubReactor.buildInstance()` via `CurrentPrincipal.tenancyId()` — the same mechanism used by SubCase execution on `@ConsumeEvent(blocking=true)` worker threads

**`awaitCase(childCaseId, timeout)`:**

```java
@Override
public CaseContext awaitCase(UUID childCaseId, Duration timeout) {
    CompletableFuture<CaseContext> future = tracker.register(childCaseId);

    // Race guard: case may have completed between spawnCase() and register().
    // CaseInstanceCacheImpl is a ConcurrentHashMap with no eviction — terminal
    // instances are always present when this check runs.
    CaseInstance child = caseInstanceCache.get(childCaseId);
    if (child != null && isTerminal(child.getState())) {
        CaseContext snapshot = child.getCaseContext().snapshot();
        if (child.getState() == CaseStatus.COMPLETED) {
            future.complete(snapshot);
        } else {
            future.completeExceptionally(
                new CaseTerminatedException(childCaseId, child.getState()));
        }
    }

    try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        throw new SettlementTimeoutException(
            "Child case " + childCaseId + " did not complete within " + timeout);
    } catch (ExecutionException e) {
        if (e.getCause() instanceof CaseTerminatedException cte) {
            throw cte;
        }
        throw new RuntimeException(
            "Child case " + childCaseId + " failed", e.getCause());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
            "Interrupted while awaiting case " + childCaseId, e);
    } finally {
        tracker.remove(childCaseId);
    }
}

private static boolean isTerminal(CaseStatus status) {
    return status == CaseStatus.COMPLETED
        || status == CaseStatus.FAULTED
        || status == CaseStatus.CANCELLED;
}
```

Race guard handles the out-of-order case: if the child case completed between `spawnCase()` return and `register()`, the tracker's `complete()` found no registered future (no-op). The cache check detects the terminal state and completes the future directly. `CaseInstanceCacheImpl` has no eviction — terminal instances persist for the JVM lifetime.

`CaseTerminatedException` is rethrown directly (not wrapped in `RuntimeException`) so callers can catch it by type:

```java
try {
    CaseContext result = runtime.awaitCase(childId, timeout);
} catch (CaseTerminatedException e) {
    // child faulted or cancelled
}
```

The `finally` block cleans up the entry from the tracker regardless of outcome (success, timeout, interruption, exception).

**`spawnAndAwaitCase`** — already delegates to `spawnCase()` and `awaitCase()`. Works once those are implemented; no additional changes needed.

### 5.5. WorkerRuntimeFactory — inject tracker

Add `CaseCompletionTracker` to constructor, pass to `DefaultWorkerRuntime.create()`.

### 5.6. Tests

| Test class | What it covers |
|-----------|---------------|
| `CaseDefinitionRegistryTest` | `findByName()`: happy path, not-found, ambiguous |
| `CaseCompletionTrackerTest` | register → complete → get; register → timeout; already-terminal race; completeExceptionally |
| `DefaultWorkerRuntimeTest` | `spawnCase` and `awaitCase` with mocked deps |
| `WorkerRuntimeContractTest` | Update to cover `spawnCase`/`awaitCase` |
| `HybridOrchestrationIntegrationTest` | New test: worker spawns child case, awaits completion, asserts result in parent context |

---

## 6. PLATFORM.md + Garden Protocol (#643)

### Changes

**`casehubio/parent/docs/PLATFORM.md`** — Two additions from engine#634 spec §7:

1. Capability ownership table — new entry:

> **Routing Strategy Resolution** — `casehub-platform-api` (`io.casehub.platform.api.routing`)
>
> `NamedStrategy` marker interface and `StrategyResolver` CDI bean. All per-case-selectable routing strategies extend `NamedStrategy` and are resolved by `id` via `StrategyResolver`. Resolution order: YAML-specified ID → `@DefaultBean` fallback. Domain-specific strategy interfaces live in their owning module (`engine-api`, `work-api`); the shared convention lives in `platform-api`.

2. Step 4 consistency rules — new entry:

> **Routing strategies:** Any SPI where a harness author selects among alternative implementations per case or per binding must extend `NamedStrategy` (platform-api), declare a stable `id()`, and ship a `@DefaultBean` no-op or sensible-default implementation. Resolve via `StrategyResolver`, never via direct `Instance<>` iteration or CDI `@Priority` override.

**`casehubio/garden/docs/protocols/casehub/routing-strategy-convention.md`** — New protocol:

Scope: platform (all casehubio repos). Rule: Per-case or per-binding selectable strategies extend `NamedStrategy`, declare `id()`, ship `@DefaultBean` default, resolve via `StrategyResolver`. Non-members: `ActionRiskClassifier` (chain composition), `@DefaultBean`-only SPIs (single-bean replacement), `ContextDiffStrategy` (deployment-level config), access control policies, data providers, delivery infrastructure.

---

## 7. Follow-on Issue

**Per-case CONTEXT_CHANGED serialization** — Serialize the evaluation pipeline per caseId to eliminate the race class that #637 Option A mitigates but doesn't fully prevent. Requires analysis of: re-entrant paths (handlers that re-publish CONTEXT_CHANGED), deadlock surface (nested case operations), contention impact (high-throughput cases). The CAS guard in #637 is sufficient for correctness for CapabilityTarget bindings (the atomic `filterAndIndexForDispatch` step prevents double-dispatch). For non-CapabilityTarget bindings (HumanTask, SubCase, Extension), the CAS does not apply — their handlers own the transition, and the PENDING status check in `filterAndIndexForDispatch` remains a TOCTOU gap. Per-case serialization eliminates the race class for ALL target types and is a correctness requirement for the non-capability paths.

---

## Summary of File Changes

| Module | File | Issues |
|--------|------|--------|
| common | `CaseDefinitionRegistry.java` | #636 |
| common | `CaseTerminatedException.java` (new) | #636 |
| common | `ActionGateScheduleEvent.java` | #641 |
| runtime | `WorkerRecoveryHealthCheck.java` | #629 |
| runtime | `WorkerRecoveryHealthCheckTest.java` | #629 |
| runtime | `DefaultCaseDefinitionRegistry.java` | #636 |
| runtime | `CaseCompletionTracker.java` (new) | #636 |
| runtime | `CaseStatusChangedHandler.java` | #636 |
| runtime | `WorkflowExecutionCompletedHandler.java` | #641 |
| runtime | `DefaultWorkerRuntime.java` | #636 |
| runtime | `WorkerRuntimeFactory.java` | #636 |
| runtime | `EngineStrategyResolver.java` | #642 |
| runtime | Tests (multiple) | #629, #636, #637, #642 |
| blackboard | `PlanItem.java` | #637 |
| blackboard | `PlanningStrategyLoopControl.java` | #637 |
| blackboard | Tests | #637 |
| work-adapter | `ActionGateWorkItemHandler.java` | #641 |
| work-adapter | Tests | #641 |
| cross-repo | `casehubio/parent/docs/PLATFORM.md` | #643 |
| cross-repo | `casehubio/garden/docs/protocols/casehub/routing-strategy-convention.md` (new) | #643 |
| project | `CLAUDE.md` | #629 |
