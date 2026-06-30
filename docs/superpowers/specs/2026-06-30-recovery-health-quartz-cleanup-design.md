# Design: Recovery Health Check + Quartz Cleanup

**Date:** 2026-06-30
**Issues:** engine#593, engine#594
**Branch:** issue-593-recovery-health-quartz-cleanup

## Summary

Wire `RecoveryStatus` to a `@Liveness` health check and remove a stale TODO from
`QuartzWorkerExecutionManager`. The health check work requires extracting recovery
initiation from the Quartz backend into an engine-level coordinator — recovery is
an engine concern, not a Quartz concern.

## Issue #594 — Remove stale TODO

The TODO at `QuartzWorkerExecutionManager` line 129 describes multi-JVM Quartz
fan-out — an abandoned design direction. The architecture uses RAM store (no
multi-JVM coordination) and routes across backends via `CompositeWorkerExecutionManager`.
The `eventLogId` parameter is self-documenting from the interface Javadoc.

**Action:** Remove the TODO. No replacement needed.

## Issue #593 — Recovery health check

### Problem

`QuartzWorkerExecutionManager` runs startup recovery asynchronously and tracks the
outcome in a `volatile RecoveryStatus` field (`PENDING`, `COMPLETED`, `FAILED`). If
recovery fails, previously-scheduled workers are silently lost. The only indication
is a log line. Operators have no way to observe recovery failure via standard health
endpoints.

### Root cause of the design gap

Recovery initiation is accidentally coupled to the Quartz backend.
`QuartzWorkerExecutionManager.onStart()` calls
`workerExecutionRecoveryService.recoverPendingScheduledWorkers()`, but the recovery
service is backend-agnostic — it finds ALL pending events and reschedules them through
the composite router. Recovery is an engine-level startup concern, not a backend concern.

A health check that injects `QuartzWorkerExecutionManager` directly would break the
`@WorkerBackend` abstraction that `CompositeWorkerExecutionManager` provides.

### Behavioral change: recovery becomes unconditional

Currently, recovery is initiated from `QuartzWorkerExecutionManager.onStart()` in
`scheduler-quartz`. Applications without `scheduler-quartz` on the classpath never
run recovery. After this refactor, `WorkerRecoveryCoordinator` lives in `runtime` —
which is always present — so recovery fires unconditionally on every startup.

This is the correct behavior. `DefaultWorkerExecutionRecoveryService` already routes
through the composite `WorkerExecutionManager`, which dispatches to whichever backends
are available. Recovery should not be gated on a specific backend being present.

### Design

#### 1. Extract `RecoveryStatus` — `runtime/internal/engine/recovery/RecoveryStatus.java`

Standalone enum: `PENDING`, `COMPLETED`, `FAILED`. Internal to the coordinator —
not an SPI contract. Lives alongside `WorkerRecoveryCoordinator` and
`WorkerRecoveryHealthCheck` in `runtime/internal/engine/recovery/`.

#### 2. `WorkerRecoveryCoordinator` — `runtime/internal/engine/recovery/`

`@ApplicationScoped`. Owns recovery initiation and status tracking.

- `@Inject WorkerExecutionRecoveryService recoveryService`
- `@ConfigProperty(name = "casehub.engine.recovery.timeout", defaultValue = "60s") Duration recoveryTimeout`
- `volatile RecoveryStatus status = RecoveryStatus.PENDING`
- `onStart(@Observes @Priority(22) StartupEvent)` — calls
  `recoveryService.recoverPendingScheduledWorkers()` with timeout guard:
  `.ifNoItem().after(recoveryTimeout).fail()`, then subscribes with
  success -> COMPLETED, failure -> FAILED + LOG.errorf. A
  `TimeoutException` from a hung recovery follows the same FAILED path as
  any other recovery error.
- Public `getRecoveryStatus()` accessor

Priority 22 places the coordinator after Quartz job listener registration
(priority 20) and before `HumanTaskRecoveryService` (priority 25). The current
startup ordering becomes:

| Priority | Observer | Purpose |
|----------|---------|---------|
| 10 | `DefaultCaseDefinitionRegistry` | Load case definitions |
| 20 | `QuartzWorkerExecutionManager` | Register job listener (recovery removed) |
| 22 | `WorkerRecoveryCoordinator` | Initiate recovery + track status |
| 25 | `HumanTaskRecoveryService` | Catch up offline-completed WorkItems |
| 30 | `PendingWorkRegistry` | Re-register futures for in-flight work |
| 100 | `RlsPolicyApplicator` | RLS policy setup |

#### 3. Strip `QuartzWorkerExecutionManager`

Remove from `QuartzWorkerExecutionManager`:
- `RecoveryStatus` inner enum
- `volatile RecoveryStatus recoveryStatus` field
- `getRecoveryStatus()` method
- `WorkerExecutionRecoveryService` injection
- Recovery call from `onStart()` — retain only job listener registration

#### 4. `WorkerRecoveryHealthCheck` — `runtime/internal/engine/recovery/`

`@Liveness @ApplicationScoped implements HealthCheck`. Injects
`WorkerRecoveryCoordinator`.

| RecoveryStatus | Health    | Data                          |
|---------------|-----------|-------------------------------|
| PENDING       | UP        | `"status": "in-progress"`     |
| COMPLETED     | UP        | (none)                        |
| FAILED        | DOWN      | `"status": "failed"`          |

`@Liveness` because recovery failure is non-self-healing — restart is the correct
remediation. If the underlying cause persists, CrashLoopBackoff correctly signals
a fundamental problem to operators. New work submissions are unaffected by recovery
status — `@Readiness` would incorrectly block traffic for an old-state-restoration
problem.

#### 5. Dependency

Add `quarkus-smallrye-health` to `runtime/pom.xml` at compile scope. This is the
first SmallRye health infrastructure in the engine and platform — no `@Liveness`,
`@Readiness`, or `@Startup` health checks exist anywhere in the codebase today.
`ActionGateDeploymentHealthCheck` is a `@PostConstruct` startup validator, not a
SmallRye health check.

Adding this dependency enables `/q/health/live`, `/q/health/ready`, and
`/q/health/started` endpoints for all applications that consume `runtime`. Health
is not optional for a production engine — this is foundational infrastructure that
all consuming applications should have.

### Scope boundary: HumanTaskRecoveryService

This spec covers worker recovery health only (engine#593). `HumanTaskRecoveryService`
performs a separate startup recovery for offline-completed work items at priority 25.
Its failure profile differs from worker recovery: it executes synchronously, so
unhandled exceptions crash startup (visible), and individual item failures are logged
and non-critical (the PlanItem stays DELEGATED, recoverable on next restart).

A health check for human task recovery would require different design — filed as a
follow-up issue.

### Tests

- **`WorkerRecoveryCoordinatorTest`** — unit test, mock
  `WorkerExecutionRecoveryService`. Verify: initial status is PENDING; successful
  recovery -> COMPLETED; failed recovery -> FAILED; hung recovery (Uni that never
  completes) -> FAILED after timeout.
- **`WorkerRecoveryHealthCheckTest`** — unit test. Verify UP/DOWN mapping for each
  `RecoveryStatus` value.
- **`QuartzWorkerExecutionManagerTest`** — no changes needed. Existing tests cover
  `getActiveCaseIds()`, `supports()`, and scheduler exception handling. No references
  to `RecoveryStatus` or `getRecoveryStatus()` exist.
- **Existing integration tests unaffected** — `WorkerRecoveryTest`,
  `WorkerScheduleDedupTest`, and `WorkerIdempotencyTest` call
  `recoverPendingScheduledWorkers()` directly on the recovery service. They test
  recovery logic, not startup initiation, and bypass the coordinator entirely.
- **`WorkerRecoveryCoordinatorIT`** — `@QuarkusTest` integration test. Verify
  coordinator fires after startup and transitions to COMPLETED status. Catches
  priority ordering regressions.
- **`WorkerRecoveryHealthCheckIT`** — `@QuarkusTest` integration test. Verify
  `/q/health/live` endpoint returns expected response shape including recovery
  status data.
