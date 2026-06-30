# Recovery Health Check + Quartz Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire worker recovery status to a `@Liveness` health check and remove stale Quartz design debt.

**Architecture:** Extract recovery initiation from `QuartzWorkerExecutionManager` into `WorkerRecoveryCoordinator` in `runtime`. The coordinator owns recovery lifecycle and status tracking. A `@Liveness` health check reads from the coordinator. `RecoveryStatus` is internal to `runtime` — not an SPI.

**Tech Stack:** Quarkus SmallRye Health (MicroProfile Health), CDI startup observers, Mutiny

## Global Constraints

- Java 21, Quarkus 3.32.2
- `quarkus-smallrye-health` is new to the engine — no existing health checks
- Tests named `*Test.java` (surefire), never `*IT.java`
- `mvn install -DskipTests -q` before module-specific tests
- `TESTCONTAINERS_RYUK_DISABLED=true` prefix on all test commands
- Commit messages reference issues: `Refs #N` or `Closes #N`

---

### Task 1: Remove stale TODO from QuartzWorkerExecutionManager (#594)

**Files:**
- Modify: `scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionManager.java:129-130`

**Interfaces:**
- Consumes: nothing
- Produces: nothing — removal only

- [ ] **Step 1: Remove the TODO comment**

In `QuartzWorkerExecutionManager.java`, delete lines 129-130:
```java
  // TODO, yes, here is id of  event object, because later it can be splitted into multiple jobs on
  // diff jvms
```

The `@Override` annotation and `submit()` method below remain unchanged.

- [ ] **Step 2: Verify compilation**

Run: `mvn install -DskipTests -q -pl scheduler-quartz`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run existing tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl scheduler-quartz`
Expected: All tests pass — this is a comment removal only.

- [ ] **Step 4: Commit**

```
git add scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionManager.java
git commit -m "refactor: remove stale multi-JVM fan-out TODO from QuartzWorkerExecutionManager

The TODO described a multi-JVM Quartz fan-out design that was never
pursued. The architecture uses RAM store and routes across backends
via CompositeWorkerExecutionManager.

Closes #594"
```

---

### Task 2: Create RecoveryStatus enum and WorkerRecoveryCoordinator (#593)

**Files:**
- Create: `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/RecoveryStatus.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinator.java`
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinatorTest.java`

**Interfaces:**
- Consumes: `WorkerExecutionRecoveryService.recoverPendingScheduledWorkers()` returns `Uni<Void>`
- Produces: `WorkerRecoveryCoordinator.getRecoveryStatus()` returns `RecoveryStatus` — consumed by Task 3 (health check)

- [ ] **Step 1: Write the failing test**

Create `runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinatorTest.java`:

```java
package io.casehub.engine.internal.engine.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.smallrye.mutiny.Uni;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerRecoveryCoordinatorTest {

  @Test
  void initialStatus_isPending() {
    WorkerExecutionRecoveryService noopService =
        () -> Uni.createFrom().voidItem();
    var coordinator = new WorkerRecoveryCoordinator(noopService, Duration.ofSeconds(60));
    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
  }

  @Test
  void successfulRecovery_transitionsToCompleted() {
    WorkerExecutionRecoveryService successService =
        () -> Uni.createFrom().voidItem();
    var coordinator = new WorkerRecoveryCoordinator(successService, Duration.ofSeconds(60));

    coordinator.triggerRecovery();

    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.COMPLETED);
  }

  @Test
  void failedRecovery_transitionsToFailed() {
    WorkerExecutionRecoveryService failService =
        () -> Uni.createFrom().failure(new RuntimeException("DB down"));
    var coordinator = new WorkerRecoveryCoordinator(failService, Duration.ofSeconds(60));

    coordinator.triggerRecovery();

    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.FAILED);
  }

  @Test
  void hungRecovery_transitionsToFailedAfterTimeout() {
    WorkerExecutionRecoveryService hangService =
        () -> Uni.createFrom().nothing();
    var coordinator = new WorkerRecoveryCoordinator(hangService, Duration.ofMillis(100));

    coordinator.triggerRecovery();

    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.FAILED);
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerRecoveryCoordinatorTest`
Expected: FAIL — `RecoveryStatus` and `WorkerRecoveryCoordinator` do not exist.

- [ ] **Step 3: Create RecoveryStatus enum**

Create `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/RecoveryStatus.java`:

```java
package io.casehub.engine.internal.engine.recovery;

public enum RecoveryStatus {
  PENDING,
  COMPLETED,
  FAILED
}
```

- [ ] **Step 4: Create WorkerRecoveryCoordinator**

Create `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinator.java`:

```java
package io.casehub.engine.internal.engine.recovery;

import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerRecoveryCoordinator {

  private static final Logger LOG = Logger.getLogger(WorkerRecoveryCoordinator.class);

  private final WorkerExecutionRecoveryService recoveryService;
  private final Duration recoveryTimeout;
  private volatile RecoveryStatus status = RecoveryStatus.PENDING;

  @Inject
  public WorkerRecoveryCoordinator(
      WorkerExecutionRecoveryService recoveryService,
      @ConfigProperty(name = "casehub.engine.recovery.timeout", defaultValue = "60s")
          Duration recoveryTimeout) {
    this.recoveryService = recoveryService;
    this.recoveryTimeout = recoveryTimeout;
  }

  void onStart(@Observes @Priority(22) StartupEvent ev) {
    triggerRecovery();
  }

  void triggerRecovery() {
    recoveryService
        .recoverPendingScheduledWorkers()
        .ifNoItem()
        .after(recoveryTimeout)
        .fail()
        .subscribe()
        .with(
            v -> {
              status = RecoveryStatus.COMPLETED;
              LOG.info("Worker execution recovery completed");
            },
            t -> {
              status = RecoveryStatus.FAILED;
              LOG.errorf(t, "Worker execution recovery failed");
            });
  }

  public RecoveryStatus getRecoveryStatus() {
    return status;
  }
}
```

- [ ] **Step 5: Verify `WorkerExecutionRecoveryService` has the right method shape**

The test uses a lambda implementing `WorkerExecutionRecoveryService`. Verify the interface has `recoverPendingScheduledWorkers()` returning `Uni<Void>` and that the interface is a functional interface candidate (it has `loadOrRestoreCaseInstance` too, so it is NOT a functional interface). If not functional, use an anonymous class instead:

```java
    WorkerExecutionRecoveryService noopService = new WorkerExecutionRecoveryService() {
      @Override
      public Uni<CaseInstance> loadOrRestoreCaseInstance(UUID caseId) {
        return Uni.createFrom().nullItem();
      }
      @Override
      public Uni<Void> recoverPendingScheduledWorkers() {
        return Uni.createFrom().voidItem();
      }
    };
```

Update all four test methods to use anonymous classes. Extract a helper to reduce duplication:

```java
  private WorkerExecutionRecoveryService serviceWith(Uni<Void> recoveryResult) {
    return new WorkerExecutionRecoveryService() {
      @Override
      public Uni<CaseInstance> loadOrRestoreCaseInstance(UUID caseId) {
        return Uni.createFrom().nullItem();
      }
      @Override
      public Uni<Void> recoverPendingScheduledWorkers() {
        return recoveryResult;
      }
    };
  }
```

Then each test becomes:
```java
  @Test
  void initialStatus_isPending() {
    var coordinator = new WorkerRecoveryCoordinator(
        serviceWith(Uni.createFrom().voidItem()), Duration.ofSeconds(60));
    assertThat(coordinator.getRecoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
  }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerRecoveryCoordinatorTest`
Expected: All 4 tests pass.

- [ ] **Step 7: Commit**

```
git add runtime/src/main/java/io/casehub/engine/internal/engine/recovery/RecoveryStatus.java \
       runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinator.java \
       runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinatorTest.java
git commit -m "feat(#593): add WorkerRecoveryCoordinator with timeout guard

Extracts recovery initiation from QuartzWorkerExecutionManager into
an engine-level coordinator at @Priority(22). Tracks RecoveryStatus
(PENDING/COMPLETED/FAILED) with configurable timeout
(casehub.engine.recovery.timeout, default 60s).

Refs #593"
```

---

### Task 3: Create WorkerRecoveryHealthCheck (#593)

**Files:**
- Modify: `runtime/pom.xml` — add `quarkus-smallrye-health` dependency
- Create: `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheck.java`
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheckTest.java`

**Interfaces:**
- Consumes: `WorkerRecoveryCoordinator.getRecoveryStatus()` returns `RecoveryStatus` (from Task 2)
- Produces: MicroProfile Health endpoint at `/q/health/live` — includes `worker-recovery` check

- [ ] **Step 1: Add `quarkus-smallrye-health` dependency to runtime/pom.xml**

Add after the `quarkus-virtual-threads` dependency (line 67):

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
```

- [ ] **Step 2: Verify compilation**

Run: `mvn install -DskipTests -q -pl runtime`
Expected: BUILD SUCCESS

- [ ] **Step 3: Write the failing test**

Create `runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheckTest.java`:

```java
package io.casehub.engine.internal.engine.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class WorkerRecoveryHealthCheckTest {

  @Test
  void pending_reportsUp_withInProgressData() {
    var check = new WorkerRecoveryHealthCheck(coordinatorWithStatus(RecoveryStatus.PENDING));
    HealthCheckResponse response = check.call();

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    assertThat(response.getData()).isPresent();
    assertThat(response.getData().get()).containsEntry("status", "in-progress");
  }

  @Test
  void completed_reportsUp_withNoData() {
    var check = new WorkerRecoveryHealthCheck(coordinatorWithStatus(RecoveryStatus.COMPLETED));
    HealthCheckResponse response = check.call();

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
    assertThat(response.getData()).isEmpty();
  }

  @Test
  void failed_reportsDown_withFailedData() {
    var check = new WorkerRecoveryHealthCheck(coordinatorWithStatus(RecoveryStatus.FAILED));
    HealthCheckResponse response = check.call();

    assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    assertThat(response.getData()).isPresent();
    assertThat(response.getData().get()).containsEntry("status", "failed");
  }

  private WorkerRecoveryCoordinator coordinatorWithStatus(RecoveryStatus status) {
    return new WorkerRecoveryCoordinator(null, null) {
      @Override
      public RecoveryStatus getRecoveryStatus() {
        return status;
      }
    };
  }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerRecoveryHealthCheckTest`
Expected: FAIL — `WorkerRecoveryHealthCheck` does not exist.

- [ ] **Step 5: Create WorkerRecoveryHealthCheck**

Create `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheck.java`:

```java
package io.casehub.engine.internal.engine.recovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class WorkerRecoveryHealthCheck implements HealthCheck {

  private final WorkerRecoveryCoordinator coordinator;

  @Inject
  public WorkerRecoveryHealthCheck(WorkerRecoveryCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Override
  public HealthCheckResponse call() {
    return switch (coordinator.getRecoveryStatus()) {
      case PENDING -> HealthCheckResponse.named("worker-recovery")
          .up()
          .withData("status", "in-progress")
          .build();
      case COMPLETED -> HealthCheckResponse.named("worker-recovery")
          .up()
          .build();
      case FAILED -> HealthCheckResponse.named("worker-recovery")
          .down()
          .withData("status", "failed")
          .build();
    };
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerRecoveryHealthCheckTest`
Expected: All 3 tests pass.

- [ ] **Step 7: Commit**

```
git add runtime/pom.xml \
       runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheck.java \
       runtime/src/test/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryHealthCheckTest.java
git commit -m "feat(#593): add @Liveness health check for worker recovery

WorkerRecoveryHealthCheck reports UP during PENDING and COMPLETED,
DOWN on FAILED. Adds quarkus-smallrye-health to runtime — first
SmallRye Health infrastructure in the engine.

Refs #593"
```

---

### Task 4: Strip recovery from QuartzWorkerExecutionManager (#593)

**Files:**
- Modify: `scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionManager.java`

**Interfaces:**
- Consumes: nothing new
- Produces: `onStart()` retains only Quartz job listener registration

- [ ] **Step 1: Remove recovery-related code from QuartzWorkerExecutionManager**

In `QuartzWorkerExecutionManager.java`, remove:

1. The `WorkerExecutionRecoveryService` import and field injection:
```java
// Remove this import
import io.casehub.engine.common.spi.recovery.WorkerExecutionRecoveryService;

// Remove this field
@Inject WorkerExecutionRecoveryService workerExecutionRecoveryService;
```

2. The `RecoveryStatus` inner enum (lines 100-106):
```java
// Remove entirely
private volatile RecoveryStatus recoveryStatus = RecoveryStatus.PENDING;

public enum RecoveryStatus {
    PENDING,
    COMPLETED,
    FAILED
}
```

3. The `getRecoveryStatus()` method (lines 125-127):
```java
// Remove entirely
public RecoveryStatus getRecoveryStatus() {
    return recoveryStatus;
}
```

4. The recovery call from `onStart()`. Change from:
```java
  void onStart(@Observes @Priority(20) StartupEvent ev) throws SchedulerException {
    scheduler.getListenerManager().addJobListener(workflowExecutionJobListener);

    workerExecutionRecoveryService
        .recoverPendingScheduledWorkers()
        .subscribe()
        .with(
            v -> {
              recoveryStatus = RecoveryStatus.COMPLETED;
              LOG.info("Worker execution recovery completed");
            },
            t -> {
              recoveryStatus = RecoveryStatus.FAILED;
              LOG.errorf(t, "Worker execution recovery failed");
            });
  }
```

To:
```java
  void onStart(@Observes @Priority(20) StartupEvent ev) throws SchedulerException {
    scheduler.getListenerManager().addJobListener(workflowExecutionJobListener);
  }
```

5. Remove the now-unused imports: `io.quarkus.runtime.StartupEvent`, `io.smallrye.mutiny.Uni` — but only if they are not used elsewhere in the file. `StartupEvent` is still used by `onStart()`. `Uni` is used by `submit()` and other methods. Check each before removing.

Remove only `WorkerExecutionRecoveryService` import — the others are still needed.

- [ ] **Step 2: Verify compilation**

Run: `mvn install -DskipTests -q -pl scheduler-quartz`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run scheduler-quartz tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl scheduler-quartz`
Expected: All tests pass. No tests reference `RecoveryStatus` or `getRecoveryStatus()`.

- [ ] **Step 4: Run runtime tests to verify coordinator works end-to-end**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=WorkerRecoveryCoordinatorTest,WorkerRecoveryHealthCheckTest`
Expected: All 7 tests pass (4 coordinator + 3 health check).

- [ ] **Step 5: Commit**

```
git add scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionManager.java
git commit -m "refactor(#593): remove recovery from QuartzWorkerExecutionManager

Recovery initiation is now owned by WorkerRecoveryCoordinator in
runtime. QuartzWorkerExecutionManager.onStart() retains only Quartz
job listener registration.

Refs #593"
```

---

### Task 5: Update CLAUDE.md and close issue (#593)

**Files:**
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: nothing
- Produces: nothing — documentation only

- [ ] **Step 1: Update CLAUDE.md**

In the `## Worker Execution Architecture` section, find the paragraph that mentions `QuartzWorkerExecutionManager.onStart()` and `RecoveryStatus`:

> `QuartzWorkerExecutionManager.onStart()` runs recovery asynchronously via `subscribe().with()` with `RecoveryStatus` tracking (engine#588).

Replace with:

> `WorkerRecoveryCoordinator` (`runtime/internal/engine/recovery/`) initiates recovery at `@Priority(22)` via `WorkerExecutionRecoveryService.recoverPendingScheduledWorkers()` with a configurable timeout (`casehub.engine.recovery.timeout`, default 60s). Tracks `RecoveryStatus` (`PENDING`/`COMPLETED`/`FAILED`). `WorkerRecoveryHealthCheck` (`@Liveness`) reports the status at `/q/health/live`. `QuartzWorkerExecutionManager.onStart(@Priority(20))` retains only Quartz job listener registration. Refs engine#593.

- [ ] **Step 2: Run full test suite for affected modules**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl scheduler-quartz,runtime`
Expected: All tests pass.

- [ ] **Step 3: Commit and close issue**

```
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for WorkerRecoveryCoordinator

Reflects recovery extraction from QuartzWorkerExecutionManager to
WorkerRecoveryCoordinator and @Liveness health check.

Closes #593"
```

Then: `gh issue close 593 --repo casehubio/engine`
