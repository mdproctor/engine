# Hybrid Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engine truly hybrid by adding WorkerRuntime (Tier 1 execution-level orchestration), SequentialPlanningStrategy (Tier 2 plan-level orchestration), and signalAndAwait (bulk signal with settlement detection).

**Architecture:** WorkerRuntime is a per-invocation handle injected via `WorkerExecutionContext` thread-local that lets workers call other functions and spawn sub-cases. SequentialPlanningStrategy implements the existing `PlanningStrategy` SPI to select one binding at a time. signalAndAwait uses generation-tagged signals to track when all triggered workers have completed.

**Tech Stack:** Java 21, Quarkus 3.32.2, Mutiny, Vert.x EventBus, Quartz (RAM store), JUnit 5, casehub-persistence-memory for tests.

## Global Constraints

- Zero changes to `casehub-worker-api` — all access through engine-api's `WorkerExecutionContext`
- New `CaseHubRuntime` methods are `default` methods per SPI evolution protocol
- `WorkerRuntime` interface in `api/engine/` (does not reference `CaseInstance`)
- `SequentialPlanningStrategy` in `blackboard/control/` (alongside `DefaultPlanningStrategy`)
- All records that gain `signalId` use nullable UUID with convenience constructors
- Test naming: `*Test.java` (surefire), never `*IT.java`
- Maven: `mvn install -DskipTests -q` before module-specific tests; always `TESTCONTAINERS_RYUK_DISABLED=true`

## File Map

### New files
| File | Module | Responsibility |
|------|--------|----------------|
| `api/src/main/java/io/casehub/api/engine/WorkerRuntime.java` | api | Orchestration surface interface (6 methods) |
| `api/src/main/java/io/casehub/api/engine/SettlementTimeoutException.java` | api | Unchecked exception for await timeouts |
| `api/src/main/java/io/casehub/api/model/WorkerFunctions.java` | api | Static `sequence()` combinator + `merge()` utility |
| `api/src/test/java/io/casehub/api/engine/WorkerRuntimeContractTest.java` | api | Default method contract test |
| `api/src/test/java/io/casehub/api/model/WorkerFunctionsTest.java` | api | Unit tests for sequence() |
| `api/src/test/java/io/casehub/api/model/WorkerExecutionContextTest.java` | api | Already exists — extend for runtime thread-local |
| `runtime/src/main/java/io/casehub/engine/internal/executor/WorkerRuntimeFactory.java` | runtime | @ApplicationScoped factory, creates per-invocation DefaultWorkerRuntime |
| `runtime/src/main/java/io/casehub/engine/internal/executor/DefaultWorkerRuntime.java` | runtime | Per-invocation implementation (not CDI) |
| `runtime/src/main/java/io/casehub/engine/internal/engine/SignalSettlementTracker.java` | runtime | @ApplicationScoped settlement tracking |
| `runtime/src/test/java/io/casehub/engine/internal/executor/DefaultWorkerRuntimeTest.java` | runtime | Unit tests for execute(), spawnCase() |
| `runtime/src/test/java/io/casehub/engine/internal/engine/SignalSettlementTrackerTest.java` | runtime | Unit tests for settlement lifecycle |
| `blackboard/src/main/java/io/casehub/blackboard/control/SequentialPlanningStrategy.java` | blackboard | One-at-a-time strategy |
| `blackboard/src/test/java/io/casehub/blackboard/control/SequentialPlanningStrategyTest.java` | blackboard | Unit tests for strategy logic |

### Modified files
| File | Change |
|------|--------|
| `api/.../model/WorkerExecutionContext.java` | Add `RUNTIME_HOLDER` ThreadLocal, `currentRuntime()`, `setRuntime()` |
| `api/.../engine/CaseHubRuntime.java` | Add `signal(UUID, Map)`, `signalAndAwait()`, `signalAndAwaitSync()` default methods |
| `api/.../model/CaseDefinition.java` | Add `planningStrategy` field + builder method |
| `common/.../event/CaseContextChangedEvent.java` | Add `signalId` field (nullable UUID) |
| `common/.../event/WorkerScheduleEvent.java` | Add `signalId` field (nullable UUID) |
| `common/.../event/WorkflowExecutionCompleted.java` | Add `signalId` field (nullable UUID) |
| `common/.../event/SignalReceivedEvent.java` | Add bulk variant (Map payload) or new BulkSignalReceivedEvent |
| `runtime/.../executor/SyncAgentWorkerFunctionHandler.java` | Inject `WorkerRuntimeFactory`, set runtime before invocation |
| `runtime/.../engine/CaseHubRuntimeImpl.java` | Implement bulk signal + signalAndAwait |
| `runtime/.../engine/CaseHubReactor.java` | Add bulk signal + signalAndAwait methods |
| `runtime/.../engine/handler/SignalReceivedEventHandler.java` | Handle bulk signal (Map payload) |
| `runtime/.../engine/handler/CaseContextChangedEventHandler.java` | Thread signalId, call tracker |
| `runtime/.../engine/handler/WorkflowExecutionCompletedHandler.java` | Call tracker on completion |
| `scheduler-quartz/.../QuartzWorkerExecutionJob.java` | Thread signalId through job data → WorkflowExecutionCompleted |
| `runtime/.../engine/handler/WorkerScheduleEventHandler.java` | Store signalId in EventLog metadata |
| `blackboard/.../control/PlanningStrategyLoopControl.java` | `Instance<PlanningStrategy>` injection, ID-based lookup |
| `api/.../model/converter/CaseDefinitionYamlMapper.java` | Map `planningStrategy:` and `sequence:` YAML keys |

---

### Task 1: WorkerRuntime API types + WorkerExecutionContext enhancement

**Files:**
- Create: `api/src/main/java/io/casehub/api/engine/WorkerRuntime.java`
- Create: `api/src/main/java/io/casehub/api/engine/SettlementTimeoutException.java`
- Create: `api/src/main/java/io/casehub/api/model/WorkerFunctions.java`
- Create: `api/src/test/java/io/casehub/api/engine/WorkerRuntimeContractTest.java`
- Create: `api/src/test/java/io/casehub/api/model/WorkerFunctionsTest.java`
- Modify: `api/src/main/java/io/casehub/api/model/WorkerExecutionContext.java`
- Modify: `api/src/test/java/io/casehub/api/model/WorkerExecutionContextTest.java`

**Interfaces:**
- Produces: `WorkerRuntime` interface (6 methods: `caseId()`, `execute(WorkerFunction, Map)`, `execute(String, Map)`, `spawnCase(String, Map)`, `awaitCase(UUID, Duration)`, `spawnAndAwaitCase(String, Map, Duration)`), `SettlementTimeoutException`, `WorkerFunctions.sequence(WorkerFunction...)`, `WorkerFunctions.merge(Map, Map)`, `WorkerExecutionContext.currentRuntime()`, `WorkerExecutionContext.setRuntime(WorkerRuntime)`

- [ ] **Step 1: Write WorkerRuntime interface**

```java
// api/src/main/java/io/casehub/api/engine/WorkerRuntime.java
package io.casehub.api.engine;

import io.casehub.api.context.CaseContext;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public interface WorkerRuntime {

  UUID caseId();

  WorkerResult execute(WorkerFunction function, Map<String, Object> input);

  WorkerResult execute(String workerName, Map<String, Object> input);

  UUID spawnCase(String caseType, Map<String, Object> input);

  CaseContext awaitCase(UUID childCaseId, Duration timeout);

  CaseContext spawnAndAwaitCase(String caseType, Map<String, Object> input, Duration timeout);
}
```

- [ ] **Step 2: Write SettlementTimeoutException**

```java
// api/src/main/java/io/casehub/api/engine/SettlementTimeoutException.java
package io.casehub.api.engine;

import java.time.Duration;
import java.util.UUID;

public class SettlementTimeoutException extends RuntimeException {

  private final UUID targetId;
  private final Duration timeout;

  public SettlementTimeoutException(UUID targetId, Duration timeout) {
    super("Settlement timed out after " + timeout + " for " + targetId);
    this.targetId = targetId;
    this.timeout = timeout;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public Duration getTimeout() {
    return timeout;
  }
}
```

- [ ] **Step 3: Write WorkerRuntime contract test**

```java
// api/src/test/java/io/casehub/api/engine/WorkerRuntimeContractTest.java
package io.casehub.api.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerRuntimeContractTest {

  @Test
  void anonymousImplementationCompiles() {
    WorkerRuntime runtime =
        new WorkerRuntime() {
          @Override
          public UUID caseId() {
            return UUID.randomUUID();
          }

          @Override
          public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
            return WorkerResult.of(Map.of());
          }

          @Override
          public WorkerResult execute(String workerName, Map<String, Object> input) {
            return WorkerResult.of(Map.of());
          }

          @Override
          public UUID spawnCase(String caseType, Map<String, Object> input) {
            return UUID.randomUUID();
          }

          @Override
          public io.casehub.api.context.CaseContext awaitCase(
              UUID childCaseId, Duration timeout) {
            return null;
          }

          @Override
          public io.casehub.api.context.CaseContext spawnAndAwaitCase(
              String caseType, Map<String, Object> input, Duration timeout) {
            return null;
          }
        };
    assertNotNull(runtime.caseId());
  }
}
```

- [ ] **Step 4: Run contract test to verify it passes**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=WorkerRuntimeContractTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: PASS

- [ ] **Step 5: Enhance WorkerExecutionContext**

Add `RUNTIME_HOLDER` ThreadLocal and static methods to `api/src/main/java/io/casehub/api/model/WorkerExecutionContext.java`:

```java
private static final ThreadLocal<WorkerRuntime> RUNTIME_HOLDER = new ThreadLocal<>();

public static WorkerRuntime currentRuntime() {
  return RUNTIME_HOLDER.get();
}

public static void setRuntime(WorkerRuntime runtime) {
  RUNTIME_HOLDER.set(runtime);
}
```

Update `clear()` to also remove `RUNTIME_HOLDER`:
```java
public static void clear() {
  HOLDER.remove();
  RUNTIME_HOLDER.remove();
}
```

Add import: `import io.casehub.api.engine.WorkerRuntime;`

- [ ] **Step 6: Extend WorkerExecutionContextTest for runtime thread-local**

Add test to existing `api/src/test/java/io/casehub/api/model/WorkerExecutionContextTest.java`:

```java
@Test
void runtimeThreadLocal_setAndClear() {
  assertNull(WorkerExecutionContext.currentRuntime());

  WorkerRuntime mockRuntime =
      new WorkerRuntime() {
        @Override public UUID caseId() { return UUID.fromString("00000000-0000-0000-0000-000000000001"); }
        @Override public WorkerResult execute(WorkerFunction f, Map<String, Object> i) { return null; }
        @Override public WorkerResult execute(String n, Map<String, Object> i) { return null; }
        @Override public UUID spawnCase(String t, Map<String, Object> i) { return null; }
        @Override public CaseContext awaitCase(UUID id, Duration t) { return null; }
        @Override public CaseContext spawnAndAwaitCase(String t, Map<String, Object> i, Duration d) { return null; }
      };

  WorkerExecutionContext.setRuntime(mockRuntime);
  assertSame(mockRuntime, WorkerExecutionContext.currentRuntime());

  WorkerExecutionContext.clear();
  assertNull(WorkerExecutionContext.currentRuntime());
}
```

- [ ] **Step 7: Run WorkerExecutionContext tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=WorkerExecutionContextTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 8: Write WorkerFunctions utility**

```java
// api/src/main/java/io/casehub/api/model/WorkerFunctions.java
package io.casehub.api.model;

import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerFunctions {

  private WorkerFunctions() {}

  public static WorkerFunction.Sync sequence(WorkerFunction... steps) {
    if (steps.length == 0) {
      throw new IllegalArgumentException("sequence requires at least one step");
    }
    WorkerFunction[] copy = steps.clone();
    return new WorkerFunction.Sync(
        input -> {
          var rt = WorkerExecutionContext.currentRuntime();
          if (rt == null) {
            return WorkerResult.failed("WorkerRuntime not available — "
                + "sequence must run inside engine execution context");
          }
          var acc = input;
          for (var step : copy) {
            var result = rt.execute(step, acc);
            if (!(result.outcome() instanceof WorkerOutcome.Success)) {
              return result;
            }
            acc = merge(acc, result.output());
          }
          return WorkerResult.of(acc);
        });
  }

  public static Map<String, Object> merge(
      Map<String, Object> base, Map<String, Object> overlay) {
    var merged = new LinkedHashMap<>(base);
    merged.putAll(overlay);
    return merged;
  }
}
```

- [ ] **Step 9: Write WorkerFunctionsTest**

```java
// api/src/test/java/io/casehub/api/model/WorkerFunctionsTest.java
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.engine.WorkerRuntime;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerFunctionsTest {

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void sequence_executesInOrder_accumulatesResults() {
    List<String> order = new ArrayList<>();
    WorkerFunction fnA =
        new WorkerFunction.Sync(
            input -> {
              order.add("A");
              return WorkerResult.of(Map.of("a", "fromA"));
            });
    WorkerFunction fnB =
        new WorkerFunction.Sync(
            input -> {
              order.add("B");
              assertEquals("fromA", input.get("a"));
              return WorkerResult.of(Map.of("b", "fromB"));
            });

    var stubRuntime = new StubWorkerRuntime();
    WorkerExecutionContext.setRuntime(stubRuntime);

    WorkerFunction.Sync seq = WorkerFunctions.sequence(fnA, fnB);
    WorkerResult result = seq.fn().apply(Map.of("initial", "data"));

    assertEquals(List.of("A", "B"), order);
    assertEquals("fromA", result.output().get("a"));
    assertEquals("fromB", result.output().get("b"));
    assertEquals("data", result.output().get("initial"));
    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
  }

  @Test
  void sequence_failFast_stopsOnNonSuccess() {
    List<String> order = new ArrayList<>();
    WorkerFunction fnA =
        new WorkerFunction.Sync(
            input -> {
              order.add("A");
              return WorkerResult.declined("not ready");
            });
    WorkerFunction fnB =
        new WorkerFunction.Sync(
            input -> {
              order.add("B");
              return WorkerResult.of(Map.of());
            });

    WorkerExecutionContext.setRuntime(new StubWorkerRuntime());
    WorkerResult result = WorkerFunctions.sequence(fnA, fnB).fn().apply(Map.of());

    assertEquals(List.of("A"), order);
    assertInstanceOf(WorkerOutcome.Declined.class, result.outcome());
  }

  @Test
  void sequence_noRuntime_returnsFailed() {
    WorkerFunction fn = new WorkerFunction.Sync(input -> WorkerResult.of(Map.of()));
    WorkerResult result = WorkerFunctions.sequence(fn).fn().apply(Map.of());
    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
  }

  @Test
  void sequence_emptySteps_throws() {
    assertThrows(IllegalArgumentException.class, () -> WorkerFunctions.sequence());
  }

  @Test
  void merge_overlayWins() {
    var base = Map.<String, Object>of("a", 1, "b", 2);
    var overlay = Map.<String, Object>of("b", 99, "c", 3);
    var result = WorkerFunctions.merge(base, overlay);
    assertEquals(1, result.get("a"));
    assertEquals(99, result.get("b"));
    assertEquals(3, result.get("c"));
  }

  /** Minimal stub that delegates execute() directly to the function. */
  private static class StubWorkerRuntime implements WorkerRuntime {
    @Override public java.util.UUID caseId() { return java.util.UUID.randomUUID(); }
    @Override public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
      if (function instanceof WorkerFunction.Sync sync) {
        try {
          return sync.fn().apply(input);
        } catch (Exception e) {
          return WorkerResult.failed(e.getMessage());
        }
      }
      return WorkerResult.failed("unsupported function type");
    }
    @Override public WorkerResult execute(String n, Map<String, Object> i) { return WorkerResult.failed("stub"); }
    @Override public java.util.UUID spawnCase(String t, Map<String, Object> i) { return null; }
    @Override public io.casehub.api.context.CaseContext awaitCase(java.util.UUID id, java.time.Duration t) { return null; }
    @Override public io.casehub.api.context.CaseContext spawnAndAwaitCase(String t, Map<String, Object> i, java.time.Duration d) { return null; }
  }
}
```

- [ ] **Step 10: Run WorkerFunctionsTest**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl api -Dtest=WorkerFunctionsTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 11: Commit Task 1**

```
git add api/src/main/java/io/casehub/api/engine/WorkerRuntime.java \
       api/src/main/java/io/casehub/api/engine/SettlementTimeoutException.java \
       api/src/main/java/io/casehub/api/model/WorkerFunctions.java \
       api/src/main/java/io/casehub/api/model/WorkerExecutionContext.java \
       api/src/test/java/io/casehub/api/engine/WorkerRuntimeContractTest.java \
       api/src/test/java/io/casehub/api/model/WorkerFunctionsTest.java \
       api/src/test/java/io/casehub/api/model/WorkerExecutionContextTest.java
git commit -m "feat(#485): WorkerRuntime interface, WorkerFunctions, WorkerExecutionContext enhancement

Refs #485, #484"
```

---

### Task 2: DefaultWorkerRuntime + Factory + Handler integration

**Files:**
- Create: `runtime/src/main/java/io/casehub/engine/internal/executor/WorkerRuntimeFactory.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/executor/DefaultWorkerRuntime.java`
- Create: `runtime/src/test/java/io/casehub/engine/internal/executor/DefaultWorkerRuntimeTest.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/executor/SyncAgentWorkerFunctionHandler.java`

**Interfaces:**
- Consumes: `WorkerRuntime` (Task 1), `WorkerExecutionContext.setRuntime()` (Task 1)
- Produces: `WorkerRuntimeFactory.create(UUID caseId): WorkerRuntime`, `DefaultWorkerRuntime` implementation

- [ ] **Step 1: Write failing test for DefaultWorkerRuntime.execute()**

```java
// runtime/src/test/java/io/casehub/engine/internal/executor/DefaultWorkerRuntimeTest.java
package io.casehub.engine.internal.executor;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.SettlementTimeoutException;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.vertx.core.eventbus.EventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

  private static final UUID CASE_ID = UUID.randomUUID();
  private DefaultWorkerRuntime runtime;

  @BeforeEach
  void setUp() {
    runtime = new DefaultWorkerRuntime(CASE_ID, null, null, null);
  }

  @AfterEach
  void cleanup() {
    WorkerExecutionContext.clear();
  }

  @Test
  void caseId_returnsConstructorValue() {
    assertEquals(CASE_ID, runtime.caseId());
  }

  @Test
  void execute_syncFunction_returnsResult() {
    WorkerFunction fn = new WorkerFunction.Sync(
        input -> WorkerResult.of(Map.of("result", "hello")));

    WorkerResult result = runtime.execute(fn, Map.of("key", "value"));

    assertInstanceOf(WorkerOutcome.Success.class, result.outcome());
    assertEquals("hello", result.output().get("result"));
  }

  @Test
  void execute_throwingFunction_wrapsInFailed() {
    WorkerFunction fn = new WorkerFunction.Sync(
        input -> { throw new RuntimeException("boom"); });

    WorkerResult result = runtime.execute(fn, Map.of());

    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
    assertEquals("boom", ((WorkerOutcome.Failed) result.outcome()).reason());
  }

  @Test
  void execute_preservesParentContext() {
    var parentContext = new io.casehub.api.model.WorkerContext(
        "parent-task", CASE_ID, null, null, null, null);
    WorkerExecutionContext.set(parentContext);

    WorkerFunction fn = new WorkerFunction.Sync(input -> {
      var innerCtx = WorkerExecutionContext.current();
      assertNotNull(innerCtx);
      assertEquals(CASE_ID, innerCtx.caseId());
      return WorkerResult.of(Map.of());
    });

    runtime.execute(fn, Map.of());

    assertSame(parentContext, WorkerExecutionContext.current());
  }

  @Test
  void execute_nestedOrchestration_stackSemantics() {
    List<String> order = new ArrayList<>();
    WorkerFunction inner = new WorkerFunction.Sync(input -> {
      order.add("inner");
      return WorkerResult.of(Map.of("inner", true));
    });
    WorkerFunction outer = new WorkerFunction.Sync(input -> {
      order.add("outer-start");
      var rt = WorkerExecutionContext.currentRuntime();
      var result = rt.execute(inner, input);
      order.add("outer-end");
      return result;
    });

    WorkerExecutionContext.setRuntime(runtime);
    WorkerResult result = runtime.execute(outer, Map.of());

    assertEquals(List.of("outer-start", "inner", "outer-end"), order);
    assertTrue((Boolean) result.output().get("inner"));
  }

  @Test
  void execute_unsupportedFunctionType_returnsFailed() {
    WorkerResult result = runtime.execute(WorkerFunction.NONE, Map.of());
    assertInstanceOf(WorkerOutcome.Failed.class, result.outcome());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=DefaultWorkerRuntimeTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: FAIL — `DefaultWorkerRuntime` does not exist

- [ ] **Step 3: Implement DefaultWorkerRuntime**

```java
// runtime/src/main/java/io/casehub/engine/internal/executor/DefaultWorkerRuntime.java
package io.casehub.engine.internal.executor;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.SettlementTimeoutException;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.api.model.AgentWorkerFunction;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.WorkerContext;
import io.casehub.api.model.WorkerExecutionContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.vertx.core.eventbus.EventBus;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

class DefaultWorkerRuntime implements WorkerRuntime {

    private final UUID                   caseId;
    private final CaseHubRuntime         caseHubRuntime;
    private final CaseDefinitionRegistry definitionRegistry;
    private final CaseInstanceCache      caseInstanceCache;

    DefaultWorkerRuntime(
            UUID caseId,
            CaseHubRuntime caseHubRuntime,
            CaseDefinitionRegistry definitionRegistry,
            CaseInstanceCache caseInstanceCache) {
        this.caseId             = caseId;
        this.caseHubRuntime     = caseHubRuntime;
        this.definitionRegistry = definitionRegistry;
        this.caseInstanceCache  = caseInstanceCache;
    }

    @Override
    public UUID caseId() {
        return caseId;
    }

    @Override
    public WorkerResult execute(WorkerFunction function, Map<String, Object> input) {
        if (function instanceof WorkerFunction.Sync sync) {
            return executeSync(sync.fn()::apply, input);
        }
        if (function instanceof AgentWorkerFunction agent) {
            return executeSync(agent.agent()::execute, input);
        }
        return WorkerResult.failed(
                "Unsupported function type for Tier 1 execution: "
                + function.getClass().getName()
                + ". FlowWorkerFunction belongs at Tier 3.");
    }

    @Override
    public WorkerResult execute(String workerName, Map<String, Object> input) {
        CaseInstance instance = caseInstanceCache.get(caseId);
        if (instance == null) {
            return WorkerResult.failed("Case instance not found: " + caseId);
        }
        CaseDefinition definition =
                definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
        if (definition == null) {
            return WorkerResult.failed("Case definition not found for case: " + caseId);
        }
        Worker worker =
                definition.getWorkers().stream()
                          .filter(w -> workerName.equals(w.name()))
                          .findFirst()
                          .orElse(null);
        if (worker == null) {
            throw new IllegalArgumentException(
                    "Worker '" + workerName + "' not found in case definition '"
                    + definition.getName() + "'");
        }
        return execute(worker.function(), input);
    }

    @Override
    public UUID spawnCase(String caseType, Map<String, Object> input) {
        // Resolution: scan registry for definition with matching name
        // TODO: implement CaseDefinitionRegistry.findByName() or scan
        throw new UnsupportedOperationException("spawnCase not yet implemented");
    }

    @Override
    public CaseContext awaitCase(UUID childCaseId, Duration timeout) {
        // TODO: implement event bus listener for CASE_STATUS_CHANGED
        throw new UnsupportedOperationException("awaitCase not yet implemented");
    }

    @Override
    public CaseContext spawnAndAwaitCase(
            String caseType, Map<String, Object> input, Duration timeout) {
        UUID childId = spawnCase(caseType, input);
        return awaitCase(childId, timeout);
    }

    private WorkerResult executeSync(
            java.util.function.Function<Map<String, Object>, WorkerResult> fn,
            Map<String, Object> input) {
        WorkerContext parentCtx     = WorkerExecutionContext.current();
        WorkerRuntime parentRuntime = WorkerExecutionContext.currentRuntime();
        try {
            WorkerContext childCtx = new WorkerContext(null, caseId, null, null, null, null);
            WorkerExecutionContext.set(childCtx);
            WorkerExecutionContext.setRuntime(this);
            return fn.apply(input);
        } catch (Exception e) {
            return WorkerResult.failed(e.getMessage());
        } finally {
            if (parentCtx != null) {
                WorkerExecutionContext.set(parentCtx);
            } else {
                WorkerExecutionContext.clear();
            }
            if (parentRuntime != null) {
                WorkerExecutionContext.setRuntime(parentRuntime);
            }
        }
    }
}
```

- [ ] **Step 4: Implement WorkerRuntimeFactory**

```java
// runtime/src/main/java/io/casehub/engine/internal/executor/WorkerRuntimeFactory.java
package io.casehub.engine.internal.executor;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.engine.WorkerRuntime;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

@ApplicationScoped
public class WorkerRuntimeFactory {

  private final CaseHubRuntime caseHubRuntime;
  private final CaseDefinitionRegistry definitionRegistry;
  private final CaseInstanceCache caseInstanceCache;

  @Inject
  public WorkerRuntimeFactory(
      CaseHubRuntime caseHubRuntime,
      CaseDefinitionRegistry definitionRegistry,
      CaseInstanceCache caseInstanceCache) {
    this.caseHubRuntime = caseHubRuntime;
    this.definitionRegistry = definitionRegistry;
    this.caseInstanceCache = caseInstanceCache;
  }

  public WorkerRuntime create(UUID caseId) {
    return new DefaultWorkerRuntime(
        caseId, caseHubRuntime, definitionRegistry, caseInstanceCache);
  }
}
```

- [ ] **Step 5: Run DefaultWorkerRuntimeTest**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=DefaultWorkerRuntimeTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS (the tests that use null deps for spawnCase/awaitCase are not called)

- [ ] **Step 6: Wire WorkerRuntimeFactory into SyncAgentWorkerFunctionHandler**

Modify `runtime/src/main/java/io/casehub/engine/internal/executor/SyncAgentWorkerFunctionHandler.java`:

Add `WorkerRuntimeFactory` injection to constructor:
```java
private final WorkerRuntimeFactory workerRuntimeFactory;

@Inject
public SyncAgentWorkerFunctionHandler(
    @VirtualThreads ExecutorService virtualThreads,
    WorkerRuntimeFactory workerRuntimeFactory) {
  this.virtualThreads = virtualThreads;
  this.workerRuntimeFactory = workerRuntimeFactory;
}
```

In the `execute()` method, inside the `Uni.createFrom().item()` lambda, set the runtime alongside the context:
```java
return Uni.createFrom()
    .item(
        () -> {
          WorkerExecutionContext.set(context);
          WorkerExecutionContext.setRuntime(
              workerRuntimeFactory.create(context.caseId()));
          try {
            return fn.apply(inputData);
          } finally {
            WorkerExecutionContext.clear();
          }
        })
    // ... rest unchanged
```

- [ ] **Step 7: Run existing SyncAgentWorkerFunctionHandler tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=SyncAgentWorkerFunctionHandlerTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: PASS (existing tests should still work — WorkerRuntimeFactory needs to be mocked/stubbed in the test)

Note: If the test fails due to missing `WorkerRuntimeFactory` injection, add a mock to the test setup. The test likely uses constructor injection — add the factory parameter.

- [ ] **Step 8: Commit Task 2**

```
git add runtime/src/main/java/io/casehub/engine/internal/executor/DefaultWorkerRuntime.java \
       runtime/src/main/java/io/casehub/engine/internal/executor/WorkerRuntimeFactory.java \
       runtime/src/main/java/io/casehub/engine/internal/executor/SyncAgentWorkerFunctionHandler.java \
       runtime/src/test/java/io/casehub/engine/internal/executor/DefaultWorkerRuntimeTest.java
git commit -m "feat(#485): DefaultWorkerRuntime, WorkerRuntimeFactory, handler integration

Refs #485, #484"
```

---

### Task 3: Bulk signal + signalAndAwait + SignalSettlementTracker

**Files:**
- Modify: `api/src/main/java/io/casehub/api/engine/CaseHubRuntime.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/engine/SignalSettlementTracker.java`
- Create: `runtime/src/test/java/io/casehub/engine/internal/engine/SignalSettlementTrackerTest.java`
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/CaseContextChangedEvent.java`
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/WorkerScheduleEvent.java`
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/WorkflowExecutionCompleted.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubRuntimeImpl.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/CaseHubReactor.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/SignalReceivedEventHandler.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java`
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkerScheduleEventHandler.java`
- Modify: `scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionJob.java`

**Interfaces:**
- Consumes: `SettlementTimeoutException` (Task 1)
- Produces: `CaseHubRuntime.signal(UUID, Map)`, `CaseHubRuntime.signalAndAwait(UUID, Map, Duration)`, `SignalSettlementTracker`

- [ ] **Step 1: Add default methods to CaseHubRuntime**

Add to `api/src/main/java/io/casehub/api/engine/CaseHubRuntime.java`:

```java
import io.casehub.api.context.CaseContext;
import java.time.Duration;

default CompletionStage<Void> signal(UUID caseId, Map<String, Object> updates) {
  throw new UnsupportedOperationException();
}

default CompletionStage<CaseContext> signalAndAwait(
    UUID caseId, Map<String, Object> updates, Duration timeout) {
  throw new UnsupportedOperationException();
}

default CaseContext signalAndAwaitSync(
    UUID caseId, Map<String, Object> updates, Duration timeout) {
  return signalAndAwait(caseId, updates, timeout).toCompletableFuture().join();
}
```

- [ ] **Step 2: Write SignalSettlementTracker test**

```java
// runtime/src/test/java/io/casehub/engine/internal/engine/SignalSettlementTrackerTest.java
package io.casehub.engine.internal.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalSettlementTrackerTest {

  private SignalSettlementTracker tracker;

  @BeforeEach
  void setUp() {
    tracker = new SignalSettlementTracker();
  }

  @Test
  void zeroWorkers_resolvesOnFullyDispatched() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.markFullyDispatched(signalId);

    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void oneWorker_resolvesOnCompletion() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.markFullyDispatched(signalId);
    assertFalse(future.isDone());

    tracker.recordCompletion(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void completionBeforeFullyDispatched_resolvesOnDispatchMark() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());

    tracker.markFullyDispatched(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void multipleWorkers_allMustComplete() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    CompletableFuture<Void> future = tracker.getFuture(signalId);

    tracker.incrementExpected(signalId);
    tracker.incrementExpected(signalId);
    tracker.incrementExpected(signalId);
    tracker.markFullyDispatched(signalId);

    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());
    tracker.recordCompletion(signalId);
    assertFalse(future.isDone());
    tracker.recordCompletion(signalId);
    assertNull(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  void resolvedEntry_isCleanedUp() throws Exception {
    UUID signalId = tracker.registerSignal(UUID.randomUUID());
    tracker.markFullyDispatched(signalId);
    tracker.getFuture(signalId).get(1, TimeUnit.SECONDS);

    assertNull(tracker.getFuture(signalId));
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=SignalSettlementTrackerTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: FAIL — `SignalSettlementTracker` does not exist

- [ ] **Step 4: Implement SignalSettlementTracker**

```java
// runtime/src/main/java/io/casehub/engine/internal/engine/SignalSettlementTracker.java
package io.casehub.engine.internal.engine;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
class SignalSettlementTracker {

  private final ConcurrentHashMap<UUID, SettlementState> states = new ConcurrentHashMap<>();

  UUID registerSignal(UUID caseId) {
    UUID signalId = UUID.randomUUID();
    states.put(signalId, new SettlementState(caseId));
    return signalId;
  }

  void incrementExpected(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.expected.incrementAndGet();
    }
  }

  void markFullyDispatched(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.fullyDispatched.set(true);
      tryResolve(signalId, state);
    }
  }

  void recordCompletion(UUID signalId) {
    SettlementState state = states.get(signalId);
    if (state != null) {
      state.completed.incrementAndGet();
      tryResolve(signalId, state);
    }
  }

  CompletableFuture<Void> getFuture(UUID signalId) {
    SettlementState state = states.get(signalId);
    return state != null ? state.future : null;
  }

  void remove(UUID signalId) {
    states.remove(signalId);
  }

  private void tryResolve(UUID signalId, SettlementState state) {
    if (state.fullyDispatched.get()
        && state.completed.get() >= state.expected.get()) {
      state.future.complete(null);
      states.remove(signalId);
    }
  }

  private static class SettlementState {
    final UUID caseId;
    final AtomicInteger expected = new AtomicInteger(0);
    final AtomicInteger completed = new AtomicInteger(0);
    final AtomicBoolean fullyDispatched = new AtomicBoolean(false);
    final CompletableFuture<Void> future = new CompletableFuture<>();

    SettlementState(UUID caseId) {
      this.caseId = caseId;
    }
  }
}
```

- [ ] **Step 5: Run SignalSettlementTrackerTest**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -Dtest=SignalSettlementTrackerTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 6: Add signalId to CaseContextChangedEvent**

Add `UUID signalId` as the 6th record component in `common/src/main/java/io/casehub/engine/common/internal/event/CaseContextChangedEvent.java`:

```java
public record CaseContextChangedEvent(
    CaseInstance instance,
    CaseContext contextSnapshot,
    String changedPanel,
    String triggerChannelId,
    String triggerCorrelationId,
    UUID signalId) {

  // Existing convenience constructors updated — add null for signalId:
  public CaseContextChangedEvent(
      CaseInstance instance, CaseContext contextSnapshot, String changedPanel) {
    this(instance, contextSnapshot, changedPanel, null, null, null);
  }

  // Full 5-arg constructor for backward compatibility:
  public CaseContextChangedEvent(
      CaseInstance instance,
      CaseContext contextSnapshot,
      String changedPanel,
      String triggerChannelId,
      String triggerCorrelationId) {
    this(instance, contextSnapshot, changedPanel, triggerChannelId, triggerCorrelationId, null);
  }
}
```

- [ ] **Step 7: Add signalId to WorkerScheduleEvent**

Add `UUID signalId` as the 6th record component in `common/src/main/java/io/casehub/engine/common/internal/event/WorkerScheduleEvent.java`. Add convenience constructors that pass `null` for signalId to maintain all existing call sites.

- [ ] **Step 8: Add signalId to WorkflowExecutionCompleted**

Add `UUID signalId` as the 7th record component in `common/src/main/java/io/casehub/engine/common/internal/event/WorkflowExecutionCompleted.java`. Add convenience constructor that passes `null` to maintain existing call sites. Update `approved()` static method to pass `null`.

- [ ] **Step 9: Implement bulk signal in CaseHubReactor and CaseHubRuntimeImpl**

In `CaseHubReactor`, add:
```java
Uni<Void> signalBulk(UUID caseId, Map<String, Object> updates) {
  return eventBus
      .<Void>request(
          SIGNAL_RECEIVED,
          new BulkSignalReceivedEvent(caseId, updates, null, null))
      .replaceWithVoid();
}
```

Create `BulkSignalReceivedEvent` in `common/.../event/` alongside `SignalReceivedEvent`:
```java
public record BulkSignalReceivedEvent(
    UUID caseId, Map<String, Object> updates,
    String triggerChannelId, String triggerCorrelationId) {
  public BulkSignalReceivedEvent {
    if (caseId == null) throw new IllegalArgumentException("caseId cannot be null");
    if (updates == null) throw new IllegalArgumentException("updates cannot be null");
  }
}
```

In `CaseHubRuntimeImpl`, implement:
```java
@Override
public CompletionStage<Void> signal(UUID caseId, Map<String, Object> updates) {
  return reactor.signalBulk(caseId, updates).subscribeAsCompletionStage();
}
```

- [ ] **Step 10: Implement signalAndAwait in CaseHubReactor**

```java
Uni<CaseContext> signalAndAwait(UUID caseId, Map<String, Object> updates, Duration timeout) {
  UUID signalId = settlementTracker.registerSignal(caseId);
  return eventBus
      .<Void>request(
          SIGNAL_RECEIVED,
          new BulkSignalReceivedEvent(caseId, updates, null, null, signalId))
      .replaceWithVoid()
      .chain(() -> {
        CompletableFuture<Void> future = settlementTracker.getFuture(signalId);
        if (future == null) {
          return Uni.createFrom().item(requireInstance(caseId).getCaseContext());
        }
        return Uni.createFrom().completionStage(
            future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
            .onFailure(java.util.concurrent.TimeoutException.class)
            .transform(t -> {
              settlementTracker.remove(signalId);
              return new SettlementTimeoutException(caseId, timeout);
            })
            .map(v -> requireInstance(caseId).getCaseContext());
      });
}
```

Add `signalId` field to `BulkSignalReceivedEvent`.

In `CaseHubRuntimeImpl`:
```java
@Override
public CompletionStage<CaseContext> signalAndAwait(
    UUID caseId, Map<String, Object> updates, Duration timeout) {
  return reactor.signalAndAwait(caseId, updates, timeout).subscribeAsCompletionStage();
}
```

- [ ] **Step 11: Handle bulk signal in SignalReceivedEventHandler**

Add a second `@ConsumeEvent` method or extend the existing handler to also accept `BulkSignalReceivedEvent`. The handler applies `setAll(updates)` instead of `set(path, value)`, writes a single CONTEXT_UPDATED event log, and publishes a single `CaseContextChangedEvent` with the `signalId` from the bulk event.

- [ ] **Step 12: Thread signalId through CaseContextChangedEventHandler**

In `CaseContextChangedEventHandler.onCaseStateContextChangedEventHandler()`:
- Extract `signalId` from `CaseContextChangedEvent`
- When publishing `WorkerScheduleEvent` for CapabilityTarget, pass `signalId` through
- After all binding evaluation completes, call `tracker.markFullyDispatched(signalId)` if signalId is non-null
- Call `tracker.incrementExpected(signalId)` immediately before each successful `WorkerScheduleEvent` publish (in `scheduleWorker()`, not `publishWorkerSchedule()`)

- [ ] **Step 13: Thread signalId through WorkerScheduleEventHandler**

In `WorkerScheduleEventHandler.buildEventLog()`, when signalId is non-null, add it to the EventLog metadata map:
```java
if (event.signalId() != null) {
  metadata.put("signalId", event.signalId().toString());
}
```

- [ ] **Step 14: Thread signalId through QuartzWorkerExecutionJob**

In `QuartzWorkerExecutionJob.execute()`, read signalId from EventLog metadata. When constructing `WorkflowExecutionCompleted`, pass it as the new 7th parameter.

- [ ] **Step 15: Call tracker in WorkflowExecutionCompletedHandler**

In `WorkflowExecutionCompletedHandler.onWorkflowExecutionCompletedHandler()`, after processing the outcome:
```java
UUID signalId = event.signalId();
if (signalId != null) {
  settlementTracker.recordCompletion(signalId);
}
```

- [ ] **Step 16: Fix all compilation errors from record changes**

Run: `mvn install -DskipTests -q`

All call sites constructing `CaseContextChangedEvent`, `WorkerScheduleEvent`, or `WorkflowExecutionCompleted` need updating. Use convenience constructors that pass `null` for `signalId`.

- [ ] **Step 17: Run full module tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 18: Commit Task 3**

```
git add -A
git commit -m "feat(#483): bulk signal, signalAndAwait, SignalSettlementTracker

Adds signal(UUID, Map) for atomic multi-key context updates and
signalAndAwait() with generation-tagged settlement tracking.

Refs #483"
```

---

### Task 4: SequentialPlanningStrategy + CaseDefinition.planningStrategy + PlanningStrategyLoopControl multi-strategy

**Files:**
- Create: `blackboard/src/main/java/io/casehub/blackboard/control/SequentialPlanningStrategy.java`
- Create: `blackboard/src/test/java/io/casehub/blackboard/control/SequentialPlanningStrategyTest.java`
- Modify: `api/src/main/java/io/casehub/api/model/CaseDefinition.java`
- Modify: `blackboard/src/main/java/io/casehub/blackboard/control/PlanningStrategyLoopControl.java`

**Interfaces:**
- Consumes: `PlanningStrategy` (existing), `CasePlanModel` (existing), `PlanItem` (existing)
- Produces: `SequentialPlanningStrategy` (PlanningStrategy implementation with id="sequential")

- [ ] **Step 1: Add planningStrategy field to CaseDefinition**

In `api/src/main/java/io/casehub/api/model/CaseDefinition.java`:
- Add field: `private String planningStrategy;`
- Add getter: `public String getPlanningStrategy() { return planningStrategy; }`
- Add to Builder: `public Builder planningStrategy(String id) { this.planningStrategy = id; return this; }`
- Wire in Builder.build()

- [ ] **Step 2: Write SequentialPlanningStrategy unit test**

```java
// blackboard/src/test/java/io/casehub/blackboard/control/SequentialPlanningStrategyTest.java
package io.casehub.blackboard.control;

import static org.junit.jupiter.api.Assertions.*;

import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CapabilityTarget;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.blackboard.model.CasePlanModel;
import io.casehub.blackboard.model.DefaultCasePlanModel;
import io.casehub.blackboard.model.PlanItem;
import io.casehub.blackboard.model.PlanItemStatus;
import io.casehub.api.engine.PlanExecutionContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SequentialPlanningStrategyTest {

  private SequentialPlanningStrategy strategy;
  private CasePlanModel plan;

  @BeforeEach
  void setUp() {
    strategy = new SequentialPlanningStrategy();
    plan = new DefaultCasePlanModel();
  }

  @Test
  void id_isSequential() {
    assertEquals("sequential", strategy.getId());
  }

  @Test
  void firstPending_isSelected() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    plan.addPlanItem(PlanItem.create("step-a", "workerA", 0, a.target()));
    plan.addPlanItem(PlanItem.create("step-b", "workerB", 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b)).await().indefinitely();

    assertEquals(1, result.size());
    assertEquals("step-a", result.get(0).getName());
  }

  @Test
  void completedStep_advancesToNext() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    PlanItem itemA = PlanItem.create("step-a", "workerA", 0, a.target());
    itemA.markRunning();
    itemA.markCompleted();
    plan.addPlanItem(itemA);
    plan.addPlanItem(PlanItem.create("step-b", "workerB", 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b)).await().indefinitely();

    assertEquals(1, result.size());
    assertEquals("step-b", result.get(0).getName());
  }

  @Test
  void runningStep_returnsEmpty() {
    Binding a = binding("step-a");
    PlanItem itemA = PlanItem.create("step-a", "workerA", 0, a.target());
    itemA.markRunning();
    plan.addPlanItem(itemA);

    List<Binding> result = strategy.select(plan, null, List.of(a)).await().indefinitely();

    assertTrue(result.isEmpty());
  }

  @Test
  void faultedStep_haltsSequence() {
    Binding a = binding("step-a");
    Binding b = binding("step-b");
    PlanItem itemA = PlanItem.create("step-a", "workerA", 0, a.target());
    itemA.markRunning();
    itemA.markFaulted();
    plan.addPlanItem(itemA);
    plan.addPlanItem(PlanItem.create("step-b", "workerB", 0, b.target()));

    List<Binding> result = strategy.select(plan, null, List.of(a, b)).await().indefinitely();

    assertTrue(result.isEmpty());
  }

  @Test
  void allCompleted_returnsEmpty() {
    Binding a = binding("step-a");
    PlanItem itemA = PlanItem.create("step-a", "workerA", 0, a.target());
    itemA.markRunning();
    itemA.markCompleted();
    plan.addPlanItem(itemA);

    List<Binding> result = strategy.select(plan, null, List.of(a)).await().indefinitely();

    assertTrue(result.isEmpty());
  }

  private Binding binding(String name) {
    Capability cap = Capability.builder().name(name).build();
    return Binding.builder()
        .name(name)
        .capability(cap)
        .on(new ContextChangeTrigger("." + name))
        .build();
  }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -Dtest=SequentialPlanningStrategyTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: FAIL — `SequentialPlanningStrategy` does not exist

- [ ] **Step 4: Implement SequentialPlanningStrategy**

```java
// blackboard/src/main/java/io/casehub/blackboard/control/SequentialPlanningStrategy.java
package io.casehub.blackboard.control;

import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.Binding;
import io.casehub.blackboard.model.CasePlanModel;
import io.casehub.blackboard.model.PlanItem;
import io.casehub.blackboard.model.PlanItemStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SequentialPlanningStrategy implements PlanningStrategy {

  @Override
  public String getId() {
    return "sequential";
  }

  @Override
  public String getName() {
    return "Sequential Strategy";
  }

  @Override
  public Uni<List<Binding>> select(
      CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible) {

    for (Binding binding : eligible) {
      Optional<PlanItem> item = plan.getPlanItemByBindingName(binding.getName());

      if (item.isEmpty()) {
        return Uni.createFrom().item(List.of(binding));
      }

      PlanItemStatus status = item.get().getStatus();

      if (status == PlanItemStatus.COMPLETED) {
        continue;
      }

      if (status.isTerminal()) {
        return Uni.createFrom().item(List.of());
      }

      if (status == PlanItemStatus.PENDING) {
        return Uni.createFrom().item(List.of(binding));
      }

      return Uni.createFrom().item(List.of());
    }

    return Uni.createFrom().item(List.of());
  }
}
```

- [ ] **Step 5: Run SequentialPlanningStrategyTest**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -Dtest=SequentialPlanningStrategyTest -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 6: Modify PlanningStrategyLoopControl for multi-strategy injection**

In `blackboard/src/main/java/io/casehub/blackboard/control/PlanningStrategyLoopControl.java`:

Change constructor injection from single `PlanningStrategy` to `Instance<PlanningStrategy>`:

```java
import jakarta.enterprise.inject.Instance;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

private final Map<String, PlanningStrategy> strategies;

@Inject
public PlanningStrategyLoopControl(
    Instance<PlanningStrategy> strategyBeans,
    BlackboardRegistry registry,
    StageLifecycleEvaluator stageLifecycleEvaluator,
    Instance<BlackboardPlanConfigurer> configurers,
    ImplementationRoutingStrategy implementationRoutingStrategy) {
  this.strategies = StreamSupport.stream(strategyBeans.spliterator(), false)
      .collect(Collectors.toMap(PlanningStrategy::getId, s -> s));
  // ... rest of constructor
}
```

In the `select()` method, resolve strategy from CaseDefinition:

```java
String strategyId = ctx.definition().getPlanningStrategy();
if (strategyId == null) {
  strategyId = "default";
}
PlanningStrategy strategy = strategies.get(strategyId);
if (strategy == null) {
  strategy = strategies.get("default");
}
// Use resolved strategy instead of the former single injected field
```

- [ ] **Step 7: Run full blackboard tests**

Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl blackboard -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 8: Commit Task 4**

```
git add api/src/main/java/io/casehub/api/model/CaseDefinition.java \
       blackboard/src/main/java/io/casehub/blackboard/control/SequentialPlanningStrategy.java \
       blackboard/src/main/java/io/casehub/blackboard/control/PlanningStrategyLoopControl.java \
       blackboard/src/test/java/io/casehub/blackboard/control/SequentialPlanningStrategyTest.java
git commit -m "feat(#484): SequentialPlanningStrategy, multi-strategy PlanningStrategyLoopControl

Adds sequential one-at-a-time binding selection strategy. PlanningStrategyLoopControl
now injects Instance<PlanningStrategy> and resolves by ID from CaseDefinition.

Refs #484"
```

---

### Task 5: YAML support + integration tests

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java`
- Create: integration test for Tier 1 orchestrating worker
- Create: integration test for Tier 2 sequential strategy
- Create: integration test for signalAndAwait

**Interfaces:**
- Consumes: All prior tasks
- Produces: YAML `sequence:` and `planningStrategy:` support, end-to-end tests

- [ ] **Step 1: Add planningStrategy: YAML mapping to CaseDefinitionYamlMapper**

In the mapper method that processes the case definition YAML, after reading spec fields, add:

```java
if (schema.getSpec().getPlanningStrategy() != null) {
  builder.planningStrategy(schema.getSpec().getPlanningStrategy());
}
```

Note: This requires the `io.casehub.model.CaseDefinition` schema class (jsonschema2pojo generated) to have a `planningStrategy` field. If it doesn't, add it to the JSON schema first.

- [ ] **Step 2: Add sequence: worker YAML mapping**

In the worker mapping loop of `CaseDefinitionYamlMapper`, after ExecutionPolicy conversion, add:

```java
// Check for sequence: key on the worker
List<String> sequenceSteps = schemaWorker.getSequence(); // if schema supports it
if (sequenceSteps != null && !sequenceSteps.isEmpty()) {
  // Resolve each step name to its WorkerFunction from previously built workers
  WorkerFunction[] stepFunctions = sequenceSteps.stream()
      .map(stepName -> builtWorkers.get(stepName))
      .filter(java.util.Objects::nonNull)
      .map(Worker::function)
      .toArray(WorkerFunction[]::new);
  worker.setFunction(WorkerFunctions.sequence(stepFunctions));
}
```

This requires workers referenced in `sequence:` to be defined first. The mapper may need a two-pass approach: first build all workers, then resolve sequence references.

- [ ] **Step 3: Write Tier 1 orchestrating worker integration test**

Write a `@QuarkusTest` that:
1. Defines a CaseHub with one binding triggering an orchestrating worker
2. The orchestrating worker uses `WorkerExecutionContext.currentRuntime()` to call two inner functions
3. Verifies execution order and result accumulation
4. Verifies the case completes with the orchestrated output

- [ ] **Step 4: Write Tier 2 sequential strategy integration test**

Write a `@QuarkusTest` that:
1. Defines a CaseHub with `planningStrategy("sequential")` and 3 bindings on the same ContextChangeTrigger
2. Signals the case
3. Verifies bindings fire one at a time in declaration order
4. Verifies each step's PlanItem transitions through PENDING → RUNNING → COMPLETED
5. Verifies stage autocomplete after all steps complete

- [ ] **Step 5: Write signalAndAwait integration test**

Write a `@QuarkusTest` that:
1. Defines a CaseHub with one binding triggering a worker
2. Calls `signalAndAwait(caseId, Map.of("key", "value"), Duration.ofSeconds(5))`
3. Verifies the CompletionStage resolves after the worker completes
4. Verifies the returned CaseContext contains the worker's output
5. Tests timeout: call with a worker that blocks longer than the timeout, verify SettlementTimeoutException

- [ ] **Step 6: Run all integration tests**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl runtime -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

- [ ] **Step 7: Commit Task 5**

```
git add -A
git commit -m "feat(#490): YAML support + integration tests for hybrid orchestration

Adds sequence: and planningStrategy: YAML keys. Integration tests
for Tier 1 orchestrating workers, Tier 2 sequential strategy,
and signalAndAwait settlement.

Closes #483, Closes #484, Closes #485, Refs #490"
```

- [ ] **Step 8: Run full project tests**

Run: `mvn install -DskipTests -q && TESTCONTAINERS_RYUK_DISABLED=true mvn test -f /Users/mdproctor/claude/casehub/engine/pom.xml`

Expected: ALL PASS

---

## Self-Review Checklist

### Spec coverage
- [x] WorkerRuntime interface (6 methods) → Task 1
- [x] SettlementTimeoutException → Task 1
- [x] WorkerExecutionContext enhancement → Task 1
- [x] WorkerFunctions.sequence() + merge() → Task 1
- [x] DefaultWorkerRuntime + WorkerRuntimeFactory → Task 2
- [x] SyncAgentWorkerFunctionHandler integration → Task 2
- [x] CaseHubRuntime.signal(UUID, Map) → Task 3
- [x] CaseHubRuntime.signalAndAwait() → Task 3
- [x] SignalSettlementTracker → Task 3
- [x] signalId threading through all events → Task 3
- [x] CaseDefinition.planningStrategy field → Task 4
- [x] PlanningStrategyLoopControl multi-strategy injection → Task 4
- [x] SequentialPlanningStrategy → Task 4
- [x] YAML sequence: key → Task 5
- [x] YAML planningStrategy: key → Task 5
- [x] Tier 1 integration test → Task 5
- [x] Tier 2 integration test → Task 5
- [x] signalAndAwait integration test → Task 5

### Spec items NOT in plan (deliberate)
- WorkflowPlanningStrategy (Tier 3) — future, not built in #490
- blocks integration (Tier 4) — future
- spawnCase() / awaitCase() full implementation — Task 2 has TODO stubs; full implementation depends on event bus listener wiring that needs integration test coverage in a follow-up

### Type consistency check
- `WorkerRuntime` — consistent across Tasks 1-2
- `SignalSettlementTracker` — consistent across Tasks 3
- `SequentialPlanningStrategy.getId()` returns `"sequential"` — matches `CaseDefinition.planningStrategy("sequential")`
- `CaseContextChangedEvent.signalId()` — UUID, nullable, consistent across Tasks 3
