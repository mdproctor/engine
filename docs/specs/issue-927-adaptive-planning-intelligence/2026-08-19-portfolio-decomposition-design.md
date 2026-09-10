# Portfolio Decomposition Strategy — Design Spec

**Issue:** #933
**Date:** 2026-08-19
**Module:** `casehub-engine-api` (config type), `casehub-engine-planning` (strategy implementation), `casehub-engine-api` (YAML parsing)

---

## Summary

Cascading `DecompositionStrategy` that tries fast classical decomposition first and escalates to LLM only when needed. Matches problem difficulty to planner capability — GOAP resolves well-specified cases in milliseconds, LLM handles ambiguous or under-specified cases. Provides resilience when LLM is unavailable.

## Motivation

IPC portfolio planners consistently outperform single-planner approaches. Many case definitions have complete GOAP precondition/effect specifications where `GoapPlanner` produces a valid plan in <50ms. Invoking `LlmDecompositionStrategy` adds 2-5s latency and token cost for the same result. Additionally, LLM unavailability or rate limiting shouldn't block decomposition when a classical path exists.

---

## Architecture

### Module Placement

| Component | Module | Package |
|-----------|--------|---------|
| `PortfolioConfig` | `engine-api` | `io.casehub.engine.plan` |
| `PortfolioDecompositionStrategy` | `planning` | `io.casehub.engine.planning.decomposition` |
| `PortfolioResult` | `engine-api` | `io.casehub.engine.plan` |
| YAML parsing | `engine-api` | `io.casehub.api.model.converter` |

---

## 1. PortfolioConfig (engine-api)

Per-case configuration on `CaseDefinition`:

```java
package io.casehub.engine.plan;

public record PortfolioConfig(
    List<String> delegates,
    Map<String, Long> timeouts
) {
  public static final List<String> DEFAULT_DELEGATES = List.of("goap", "llm");
  public static final long DEFAULT_TIMEOUT_MS = 30000L;
  public static final Map<String, Long> DEFAULT_TIMEOUTS = Map.of(
      "goap", 1000L,
      "llm", 30000L
  );

  public PortfolioConfig {
    delegates = delegates == null || delegates.isEmpty()
        ? DEFAULT_DELEGATES : List.copyOf(delegates);
    timeouts = timeouts == null
        ? DEFAULT_TIMEOUTS : Map.copyOf(timeouts);
    for (var entry : timeouts.entrySet()) {
      if (entry.getValue() <= 0) {
        throw new IllegalArgumentException(
            "timeout for '" + entry.getKey() + "' must be positive");
      }
    }
  }

  public static PortfolioConfig defaults() {
    return new PortfolioConfig(DEFAULT_DELEGATES, DEFAULT_TIMEOUTS);
  }

  public long timeoutFor(String strategyId) {
    return timeouts.getOrDefault(strategyId, DEFAULT_TIMEOUT_MS);
  }
}
```

**Review finding fixes:**
- Default GOAP timeout raised from 500ms to 1000ms — 500ms risks unnecessary LLM cascades on larger state spaces (robustness R1-08)
- Fallback in `timeoutFor()` uses `DEFAULT_TIMEOUT_MS` constant, not a magic literal (robustness R1-10)
- Timeout validation rejects non-positive values at construction (robustness R1-09)

`CaseDefinition` gains `portfolioConfig` (nullable `PortfolioConfig`). When `decompositionStrategy` is `"portfolio"` and `portfolioConfig` is null, `PortfolioConfig.defaults()` is used.

Builder: `.portfolioConfig(PortfolioConfig.defaults())`.

---

## 2. PortfolioDecompositionStrategy (planning)

`@ApplicationScoped`, id=`"portfolio"`. Injects `StrategyResolver` to resolve delegates lazily.

```java
@ApplicationScoped
public class PortfolioDecompositionStrategy implements DecompositionStrategy<JsonNode> {

  @Inject StrategyResolver strategyResolver;

  @Override
  public String id() {
    return "portfolio";
  }

  @Override
  public DagPlan<TaskNode.LeafTask<JsonNode>> decompose(
      TaskNode<JsonNode> task, DecompositionContext<JsonNode> context) {

    PortfolioConfig config = resolveConfig(context);
    List<PortfolioAttempt> attempts = new ArrayList<>();

    for (String delegateId : config.delegates()) {
      if ("portfolio".equals(delegateId)) continue; // recursion prevention

      Optional<DecompositionStrategy<JsonNode>> delegateOpt = resolveDelegate(delegateId);
      if (delegateOpt.isEmpty()) {
        attempts.add(PortfolioAttempt.skipped(delegateId, "not found"));
        continue;
      }

      long timeoutMs = config.timeoutFor(delegateId);
      long startMs = System.currentTimeMillis();

      try {
        DagPlan<TaskNode.LeafTask<JsonNode>> result =
            executeWithTimeout(delegateOpt.get(), task, context, timeoutMs);
        long durationMs = System.currentTimeMillis() - startMs;
        attempts.add(PortfolioAttempt.success(delegateId, durationMs));
        return result;
      } catch (TimeoutException e) {
        long durationMs = System.currentTimeMillis() - startMs;
        attempts.add(PortfolioAttempt.timeout(delegateId, durationMs));
      } catch (Exception e) {
        long durationMs = System.currentTimeMillis() - startMs;
        attempts.add(PortfolioAttempt.failed(delegateId, durationMs, e.getMessage()));
      }
    }

    throw new AgentException("Portfolio: no strategy produced a plan. Attempted: "
        + attempts);
  }
}
```

**Review finding fixes:**
- Pseudocode cleaned: single `executeWithTimeout()` path, no double invocation (coherence R1-01, robustness R1-02)
- Self-reference filtering is inline in the loop, not described-but-absent (robustness R1-06, coherence R1-03)
- `TimeoutException` caught separately → `PortfolioAttempt.timeout()` (coherence R1-04)
- `id()` override present (structure R1-07)
- `AgentException` passes the full `attempts` list (its `toString()` includes all details) (robustness R1-11)

### 2.1 Delegate Resolution

Resolves via `strategyResolver.find(DecompositionStrategy.class, delegateId)`. Returns `Optional.empty()` for unknown IDs (skip, don't crash).

### 2.2 Time Budget Enforcement

Each delegate runs on a virtual thread via `Executors.newVirtualThreadPerTaskExecutor().submit()`. `Future.get(timeoutMs, MILLISECONDS)` enforces the budget. `TimeoutException` → log warning, cascade. `Future.cancel(true)` is best-effort — virtual threads cooperate on interrupt for blocking I/O (HTTP calls in LLM delegates) but GOAP's CPU-bound `plan()` may not interrupt immediately. The timeout is enforced at the `Future.get()` level regardless.

**Timeout layering:** `DefaultGoalDecomposer` already applies `casehub.engine.decomposition.timeout-ms` (default 30s) around the entire decomposition call. The portfolio's per-delegate timeouts are strictly shorter — GOAP at 1s, LLM at 30s. If the outer timeout fires first, the entire portfolio is cancelled. This is correct: the outer timeout is a safety ceiling, the inner timeouts are per-delegate budgets.

### 2.3 Replan Support

`replan()` cascades the same way — tries each delegate's `replan()`, catching `UnsupportedOperationException` alongside other exceptions. The cascade order is the same as `decompose()`. If no delegate supports replanning, throws `UnsupportedOperationException` with the full attempt list.

### 2.4 PortfolioAttempt (package-private)

Internal record tracking each attempt for audit:

```java
record PortfolioAttempt(
    String delegateId,
    Status status,
    long durationMs,
    String detail
) {
  enum Status { SUCCESS, FAILED, SKIPPED, TIMEOUT }

  static PortfolioAttempt success(String id, long ms) {
    return new PortfolioAttempt(id, Status.SUCCESS, ms, null);
  }
  static PortfolioAttempt failed(String id, long ms, String reason) {
    return new PortfolioAttempt(id, Status.FAILED, ms, reason);
  }
  static PortfolioAttempt skipped(String id, String reason) {
    return new PortfolioAttempt(id, Status.SKIPPED, 0, reason);
  }
  static PortfolioAttempt timeout(String id, long ms) {
    return new PortfolioAttempt(id, Status.TIMEOUT, ms, "timeout");
  }
}
```

**Review finding fix:** Removed `EMPTY` status — both existing delegates (`GoapDecompositionStrategy`, `LlmDecompositionStrategy`) throw `AgentException` on failure, never return empty plans. The empty-plan check was dead code (structure R1-06, robustness R1-07).

### 2.5 Metrics — Return-based, not ThreadLocal

The strategy returns metrics alongside the plan result. `PortfolioResult` (engine-api) wraps the `DagPlan` with attempt metadata:

```java
public record PortfolioResult<T>(
    DagPlan<TaskNode.LeafTask<T>> plan,
    String selectedDelegate,
    List<String> attemptedDelegates,
    Map<String, Long> delegateDurationsMs
) {}
```

`PortfolioDecompositionStrategy.decompose()` stores the latest result in a `ThreadLocal<PortfolioResult<?>>` — but this is **read-only metrics extraction**, not shared mutable state. `DefaultGoalDecomposer` calls `getLastResult()` after `decompose()` returns and writes the metadata to the `GOAL_DECOMPOSED` EventLog. If the caller doesn't read it, the ThreadLocal is cleared on the next `decompose()` call.

**Review finding fix:** Eliminated the self-contradictory "ThreadLocal or GoalDecompositionContext" discussion (coherence R1-02, structure R1-02, robustness R1-03). The design is now unambiguous: ThreadLocal for metrics extraction only, cleared on every `decompose()` entry.

---

## 3. CaseDefinition Integration

`CaseDefinition` gains:

```java
private PortfolioConfig portfolioConfig;

public PortfolioConfig getPortfolioConfig() { return portfolioConfig; }
public void setPortfolioConfig(PortfolioConfig portfolioConfig) { ... }
```

Builder: `.portfolioConfig(PortfolioConfig)`.

**Review finding acknowledgment (structure R1-03):** Adding a strategy-specific config field to `CaseDefinition` is a precedent. If future strategies need per-case config, they'd add their own fields — a mild extension pattern concern. Accepted because: (a) portfolio is the only composite strategy likely to need per-case delegate lists, (b) the alternative (generic `Map<String, Object>` or `JsonNode strategyConfig`) loses type safety and validation, (c) pre-release platform — refactoring is cheap if the pattern doesn't scale.

---

## 4. YAML Configuration

```yaml
spec:
  decompositionStrategy: portfolio
  portfolioConfig:
    delegates: [goap, llm]
    timeouts:
      goap: 1000
      llm: 30000
```

`decompositionStrategy: portfolio` without `portfolioConfig` uses `PortfolioConfig.defaults()`.

Parsed by `CaseDefinitionYamlMapper`. Validation: delegates list must not be empty, timeout values must be positive (enforced by `PortfolioConfig` constructor).

---

## 5. Graceful Degradation

| Condition | Behavior |
|-----------|----------|
| No `PortfolioConfig` on definition | `PortfolioConfig.defaults()` used |
| Unknown delegate ID | Skipped (logged warning), cascade continues |
| Delegate throws any exception | Caught, logged, cascade continues |
| Delegate times out | Cancelled (best-effort), cascade continues |
| All delegates fail | `AgentException` with full attempt details |
| Self-reference (`"portfolio"` in delegates) | Silently filtered (recursion prevention) |

---

## 6. Testing Strategy

### 6.1 PortfolioConfig Unit Tests (engine-api)

- Default construction
- Custom delegates preserved
- Custom timeouts preserved
- `timeoutFor()` returns configured value or `DEFAULT_TIMEOUT_MS`
- Null delegates/timeouts → defaults
- Non-positive timeout rejected at construction
- Empty delegates list → defaults

### 6.2 PortfolioDecompositionStrategy Unit Tests (planning)

- First strategy succeeds → returned immediately, second not called
- First strategy throws → cascades to second
- First strategy times out → cascades to second
- Unknown delegate ID → skipped, cascade continues
- All delegates fail → AgentException with attempt summary
- Self-reference filtered out (no recursion)
- Replan cascading (including UnsupportedOperationException from delegates)
- Metrics: `getLastResult()` returns selected delegate and attempt list

### 6.3 YAML Parsing Tests (engine-api)

- `decompositionStrategy: portfolio` without config → defaults
- `portfolioConfig` with custom delegates and timeouts
- `portfolioConfig` with only delegates (timeouts default)

---

## 7. Scope Boundaries

**In scope:**
- `PortfolioConfig` record with timeout validation (engine-api)
- `PortfolioResult` record for metrics return (engine-api)
- `PortfolioDecompositionStrategy` with cascade, timeout, recursion prevention (planning)
- `PortfolioAttempt` internal tracking type
- YAML parsing for `portfolioConfig:` block
- `CaseDefinition.portfolioConfig` field
- Time budget enforcement per delegate via virtual threads

**Out of scope:**
- Dynamic delegate ordering based on case history (future — could use CBR success rates)
- Parallel delegate execution (race to first result) — sequential is simpler and sufficient for 2-3 delegates
- Per-case delegate weighting (portfolio optimization) — v1 is ordered cascade
- `DefaultGoalDecomposer` EventLog enrichment with portfolio metrics — can be added when the metric path is exercised

---

## References

- `api/src/main/java/io/casehub/engine/plan/DecompositionStrategy.java` — SPI interface
- `api/src/main/java/io/casehub/engine/plan/DecompositionContext.java` — context interface
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/GoapDecompositionStrategy.java` — GOAP delegate (throws AgentException on failure)
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/LlmDecompositionStrategy.java` — LLM delegate (throws AgentException on failure)
- `../../../planning-core/src/main/java/io/casehub/engine/planning/decomposition/DefaultGoalDecomposer.java` — caller that writes GOAL_DECOMPOSED EventLog
- `api/src/main/java/io/casehub/api/model/AdaptationConfig.java` — config record pattern
- `api/src/main/java/io/casehub/engine/plan/monitoring/MonitoringConfig.java` — config record pattern
- `specs/issue-927-adaptive-planning-intelligence/decisions-933.md` — design decisions
- `research/2026-08-18-adaptive-planning-intelligence.md` §2 — portfolio planners
- Light design review (3 dimensions, 2026-08-19) — findings incorporated
- GitHub #933 — focal issue
- GitHub #929 — prerequisite (GOAP as DecompositionStrategy)
