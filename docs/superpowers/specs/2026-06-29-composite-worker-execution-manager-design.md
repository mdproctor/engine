# Composite WorkerExecutionManager for Multi-Worker Co-deployment

**Issue:** casehubio/engine#461
**Date:** 2026-06-29

## Problem

`WorkerExecutionManager` is a single-CDI-bean interface. Seven real implementations exist
across the platform (Quartz, Camel, HTTP, Script, MCP, GitHub Actions, Claudony),
all `@ApplicationScoped`. Co-deploying any two causes CDI ambiguity at startup — only
one can be active per deployment.

## Decision: Composite at WorkerExecutionManager, Not WorkerFunctionHandler

External backends (Camel, HTTP, Script, MCP, GitHub Actions) bypass the in-process
execution pipeline (`DefaultWorkerExecutor` → `WorkerFunctionHandler`). Three independent
arguments rule out unifying them under `WorkerFunctionHandler`:

1. **Split completion model.** External async dispatch returns `Uni<Void>` (fire-and-forget
   with callback registration). `WorkerFunctionHandler.execute()` returns `Uni<WorkerResult>`
   — expects a result. Async dispatch has no result at call time.

2. **Routing decoupling.** Today, capability → backend mapping is pure configuration
   (resolver registrations). Making external backends into `WorkerFunctionHandler` would
   push backend selection into the `WorkerFunction` type on the `Worker` record, coupling
   case definitions to execution infrastructure.

3. **Information threading.** `WorkerFunctionHandler.execute()` lacks `eventLogId`,
   `CaseInstance`, and `Capability` — required by external backends for endpoint
   resolution, callback registration, and fault publishing.

## Architecture

```
Engine injection sites (5) + 1 cross-module (WorkerFaultHandler in workers-common)
  └─ @Inject WorkerExecutionManager  ──→  CompositeWorkerExecutionManager
       │                                       │
       │  @Inject @WorkerBackend Instance<WEM> │  @Inject WorkerExecutionRoutingStrategy
       │                                       │
       ▼                                       ▼
  ┌─────────────────────────────────────────────────┐
  │  WorkerExecutionRoutingStrategy.select()        │
  │  (default: FirstSupportedRoutingStrategy)       │
  │  candidates sorted by @Priority (desc)          │
  │  → first where supports() == true               │
  └──────────────────┬──────────────────────────────┘
                     │
     ┌───────────────┼───────────────────────┐
     ▼               ▼                       ▼
  @Priority(10)   @Priority(10)          @Priority(0)
  CamelWEM        HttpWEM ...            QuartzWEM
  supports():     supports():            supports():
  resolver check  resolver check         true (catch-all)
```

## Interface Changes

All in `casehub-engine-common/spi/scheduler/`.

### @WorkerBackend qualifier

```java
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface WorkerBackend {}
```

### WorkerExecutionManager — new abstract method

```java
public interface WorkerExecutionManager {

    boolean supports(String capabilityName, String tenancyId);

    Uni<Void> submit(Long eventLogId, CaseInstance instance, Worker worker,
                     Capability capability, Map<String, Object> inputData);

    default Uni<Void> schedulePersistedEvent(EventLog scheduledEventLog) {
        return Uni.createFrom().voidItem();
    }

    int getActiveWorkCount(String workerId);

    default List<UUID> getActiveCaseIds(String workerId) {
        return List.of();
    }
}
```

`supports()` is abstract — breaking change. Every implementation must be explicit about
what it can handle. This is the backend's self-declaration (capability signal), not the
final routing decision. The strategy consumes it.

`schedulePersistedEvent()` is a default no-op. Only Quartz overrides — external backends
use callback-based completion, not persisted event recovery. The default enforces this
invariant structurally and eliminates identical no-op implementations across external
backend modules.

### WorkerExecutionRoutingStrategy

```java
public interface WorkerExecutionRoutingStrategy {

    Optional<WorkerExecutionManager> select(
        List<WorkerExecutionManager> candidates,
        Worker worker,
        Capability capability,
        String tenancyId);
}
```

The strategy receives candidates sorted by `@Priority` annotation value (descending — highest
first). The composite explicitly sorts backends — CDI `Instance<>` iteration order is not
guaranteed by the CDI specification. Returns the selected backend, or empty if none can handle
the request.

## Composite Implementation

`CompositeWorkerExecutionManager` — `@ApplicationScoped` in `runtime/internal/worker/`.
Replaces `NoOpWorkerExecutionManager` (deleted). On construction, sorts discovered backends
by `@Priority` annotation value (descending — highest first). This explicit sort is required
because CDI `Instance<>` iteration order is implementation-specific (Quarkus ArC respects
`@Priority` in `Instance.stream()`, but this is not guaranteed by the CDI specification).

| Method | Composite behaviour |
|--------|-------------------|
| `submit()` | Delegates routing to `WorkerExecutionRoutingStrategy.select()`. Selected → dispatch. Empty → `ProvisioningException`. No backends discovered → `ProvisioningException`. |
| `schedulePersistedEvent()` | Routes to the backend that `supports()` the event's capability. Extracts `capabilityName` from `EventLog.metadata` and `tenancyId` from `EventLog.tenancyId` (both stored at scheduling time by `WorkerScheduleEventHandler.buildEventLog()`). Filters backends via `supports(capabilityName, tenancyId)`, delegates to the first match. No match → logs warning (backend undeployed). Only Quartz overrides the interface default no-op — external backends inherit the no-op. |
| `getActiveWorkCount()` | Sums across ALL backends. |
| `getActiveCaseIds()` | Unions across ALL backends. |
| `supports()` | Returns `true` if any backend supports (composite's own self-declaration). |

## Default Strategy

`FirstSupportedRoutingStrategy` — `@DefaultBean @ApplicationScoped` in
`runtime/internal/routing/`. Iterates candidates in priority order, returns first
where `supports()` is true.

Consumer deployments provide custom `@ApplicationScoped` implementations to override:
tenant-aware routing, load-balanced routing, function-type-aware routing, etc.

## Backend Migration

Each implementation:

1. Annotation: `@ApplicationScoped` → `@WorkerBackend @Priority(N) @ApplicationScoped`
2. New method: implement `supports(String capabilityName, String tenancyId)`

| Backend | Module | Priority | `supports()` |
|---------|--------|----------|-------------|
| `QuartzWorkerExecutionManager` | `scheduler-quartz` | 0 | `return true` (catch-all) |
| `CamelWorkerExecutionManager` | `workers-camel` | 10 | `camelCapabilityResolver.canResolve()` |
| `HttpWorkerExecutionManager` | `workers-http` | 10 | `httpEndpointResolver.canResolve()` |
| `ScriptWorkerExecutionManager` | `workers-script` | 10 | `scriptDefinitionResolver.canResolve()` |
| `McpWorkerExecutionManager` | `workers-mcp` | 10 | `mcpServerResolver.canResolve()` |
| `GitHubActionsWorkerExecutionManager` | `workers-github-actions` | 10 | configured capability set (workflow-dispatch, repository-dispatch, or deployment-registered names) |
| `ClaudonyWorkerExecutionManager` | `claudony/casehub` | 10 | tmux registry check |

Each external resolver gains a `canResolve(String capabilityName, String tenancyId)`
method — boolean, no-throw — alongside the existing `resolve()` which throws on miss.

### Unchanged

- All 5 engine injection sites: unqualified `@Inject WorkerExecutionManager` → gets composite.
- `WorkerFaultHandler` in `workers-common`: unqualified `@Inject WorkerExecutionManager` → gets
  composite. Behavioral change: pre-composite, resubmits to the single deployed backend;
  post-composite, resubmits through the routing strategy. This IS the retry self-healing path
  for external backends.
- `WorkerFunctionHandler`, `DefaultWorkerExecutor`, `QuartzWorkerExecutionJob`: no changes.
- `WorkerScheduleEventHandler`, `CaseContextChangedEventHandler`: no changes.
- `AgentCandidateFactory`: receives WEM as a method parameter (not CDI-injected) — no change.

### CDI Migration for Concrete-Type Injections

Adding `@WorkerBackend` to a backend class removes the implicit `@Default` qualifier
(CDI §2.3.1). Unqualified concrete-type injection points (`@Inject ConcreteWEM`) carry
implicit `@Default` — resolution fails.

**Claudony callers requiring migration** (add `@WorkerBackend` qualifier):
- `ClaudonyReactiveWorkerProvisioner` — constructor injection (line 65)
- `ClaudonyLedgerEventCapture` — field injection (line 47)
- `CasehubStartupService` — constructor injection (line 31)
- `ServerStartup` — `Instance<ClaudonyWorkerExecutionManager>` (line 34)

**General rule:** any concrete-type injection of any `@WorkerBackend`-qualified backend
must add `@WorkerBackend` to the injection point. This applies to all backends, not just
Claudony. `QuartzWorkerExecutionManager` currently has zero concrete-type injection sites
in the engine codebase.

### Priority Collision

Multiple backends at the same `@Priority` competing for the same capability is a
**configuration error**. `FirstSupportedRoutingStrategy` will select whichever CDI
enumerates first — this is nondeterministic. Deployments MUST ensure that for any given
capability, at most one backend at each priority level returns `supports() == true`.

## Edge Cases

**Retry routing — two paths:**
- *External backends* (`WorkerFaultHandler` in `workers-common`): resubmits via `submit()`
  with same capability and tenancy → same strategy evaluation → same backend.
  Post-composite, this routes through the strategy, enabling self-healing (a failed
  backend's retry can land on a different backend if the original no longer supports
  the capability).
- *Quartz retries* (`QuartzRetryService` in `scheduler-quartz`): creates a new
  `QuartzWorkerExecutionJob` directly via `QuartzWorkerSchedulerService.scheduleRetryAsync()`.
  This bypasses the composite entirely — Quartz retry is internal to the Quartz backend
  and does not re-evaluate the routing strategy. This is correct: Quartz retries are for
  in-process workers whose `WorkerFunctionHandler` failed transiently.

**supports() true but submit() fails:** Composite lets failure propagate through
existing fault/retry mechanism. Retry re-enters `submit()` → re-routes through strategy.

**Single-backend deployment:** Composite works identically — one candidate selected.

**No backends:** `submit()` → `ProvisioningException` (same as old no-op).

## Test Strategy

**Unit tests (engine runtime):**
- `CompositeWorkerExecutionManagerTest` — mock backends verifying routing, aggregation,
  empty-case exception.
- `FirstSupportedRoutingStrategyTest` — mock backends verifying priority ordering,
  supports filtering, empty result.

**Contract test (engine-common test-jar):**
- `WorkerExecutionManagerContractTest` (abstract) — `supports()` contract: consistent,
  no-throw. Each backend module extends with its resolver configured.
- `schedulePersistedEvent()` contract: external backends (non-Quartz) must return
  `Uni<Void>` with no side effects (no-op invariant). Verified by calling with a
  stub `EventLog` and asserting no state change. Backends that override the default
  must document why.

**Backend tests (per module):**
- Each existing test class gains `supports()` tests for registered and unregistered
  capabilities.

**Integration test (engine runtime):**
- `CompositeWorkerExecutionManagerIntegrationTest` — `@QuarkusTest` with two
  `@WorkerBackend` recording stubs verifying CDI discovery and end-to-end routing.

## Protocol Update

`PP-20260514-engine-spi-noops-defaultbean` beans table:
- Remove `NoOpWorkerExecutionManager`
- Add `CompositeWorkerExecutionManager` (`@ApplicationScoped`, not `@DefaultBean`)
  with note: replaces no-op, handles empty-backends case internally
- Add `FirstSupportedRoutingStrategy` (`@DefaultBean @ApplicationScoped`)

## Deferred Concerns

1. **Quartz catch-all `supports()` is a correctness concern** (casehubio/engine#586,
   casehubio/engine#587) — Quartz's `return true` means any capability falls through to
   Quartz if no external backend claims it (misconfigured resolver, undeployed backend,
   unregistered capability). Quartz then dispatches through `DefaultWorkerExecutor` →
   `WorkerFunctionHandler`, which has no handler for external capabilities — the job fails
   silently. This is a **pre-existing** issue (not introduced by the composite), but the
   composite makes it more visible. Fix: make Quartz's `supports()` check whether a
   `WorkerFunctionHandler` exists for the capability's function type, or introduce a
   `WorkerFunction.None` marker that Quartz rejects.

2. **Startup recovery interaction** (casehubio/engine#588) —
   `QuartzWorkerExecutionManager.onStart()` blocks on `recoverPendingScheduledWorkers()`
   for up to 30 seconds (lines 80–88, marked TODO). After the composite, recovery calls
   `schedulePersistedEvent()` on the composite (which routes via `supports()`). The
   blocking call and its interaction with backend discovery timing needs rework.

3. **Quartz trigger scheduling methods** (casehubio/engine#589) —
   `scheduleScheduledTrigger()`, `scheduleConditionalTrigger()`,
   `cancelScheduledTrigger()`, `cancelAllScheduledTriggers()` are public methods on
   `QuartzWorkerExecutionManager` not on the WEM interface. They currently have zero
   callers in the engine codebase — planned API for binding/trigger scheduling, not dead
   code. No migration impact from `@WorkerBackend` (no concrete-type injection sites for
   `QuartzWorkerExecutionManager` exist today).
