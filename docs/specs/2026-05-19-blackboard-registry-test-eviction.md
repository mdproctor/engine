# Spec: BlackboardRegistry Consolidation and Test Eviction (engine#292)

**Date:** 2026-05-19
**Issue:** casehubio/engine#292

---

## Problem

`BlackboardRegistry` maintains three separate `ConcurrentHashMap` structures all
keyed by the same `UUID caseId`:

```java
ConcurrentHashMap<UUID, CasePlanModel>                       planModels
ConcurrentHashMap<UUID, ConcurrentHashMap<String, String>>   completionIndex
Set<UUID>                                                    configured
```

This creates two problems:

1. **Data model incoherence** — three independent maps track facets of the same
   per-case state. `evict()` removes from all three sequentially, with a race
   window between removes where another thread could observe partial state.

2. **Test pollution** — three `@QuarkusTest` classes in `work-adapter` call
   `registry.getOrCreate(caseId)` in `@BeforeEach` but never call
   `registry.evict(caseId)` afterwards. Entries accumulate across test methods.

---

## Design

### Part 1 — Internal consolidation of `BlackboardRegistry`

Replace the three maps with a single `ConcurrentHashMap<UUID, CaseEntry>` where
`CaseEntry` is a private static final class holding all per-case state:

```java
private static final class CaseEntry {
  final CasePlanModel planModel;
  final ConcurrentHashMap<String, String> completionIndex = new ConcurrentHashMap<>();
  final AtomicBoolean configured = new AtomicBoolean(false);

  CaseEntry(UUID caseId) {
    this.planModel = new DefaultCasePlanModel(caseId);
  }
}

private final ConcurrentHashMap<UUID, CaseEntry> entries = new ConcurrentHashMap<>();

private CaseEntry entryFor(UUID caseId) {
  return entries.computeIfAbsent(caseId, CaseEntry::new);
}
```

Public API is **unchanged** — all six methods keep identical signatures:

```java
public CasePlanModel getOrCreate(UUID caseId) {
  return entryFor(caseId).planModel;
}

public Optional<CasePlanModel> get(UUID caseId) {
  CaseEntry e = entries.get(caseId);
  return e == null ? Optional.empty() : Optional.of(e.planModel);
}

public void indexWorkerForCompletion(UUID caseId, String workerName, String planItemId) {
  entryFor(caseId).completionIndex.put(workerName, planItemId);
}

public Optional<String> getPlanItemId(UUID caseId, String workerName) {
  CaseEntry e = entries.get(caseId);
  return e == null ? Optional.empty() : Optional.ofNullable(e.completionIndex.get(workerName));
}

public boolean markConfigured(UUID caseId) {
  return entryFor(caseId).configured.compareAndSet(false, true);
}

public void evict(UUID caseId) {
  entries.remove(caseId);   // single atomic remove — no race window
}
```

No callers change. `CaseEvictionHandler`, `PlanningStrategyLoopControl`, and
`PlanItemCompletionHandler` are unaffected.

### Part 2 — `@AfterEach` eviction in three test classes

Add `@AfterEach void tearDown() { registry.evict(caseId); }` to each of:

| File |
|------|
| `work-adapter/src/test/.../HumanTaskScheduleHandlerTest.java` |
| `work-adapter/src/test/.../HumanTaskScheduleHandlerAtomicityTest.java` |
| `work-adapter/src/test/.../WorkItemLifecycleAdapterTest.java` |

`tearDown()` does not need `@Transactional` — `evict()` is a pure in-memory
operation.

---

## Not in scope

- Blackboard integration tests — `CaseEvictionHandler` handles cleanup via the
  case lifecycle automatically when cases reach terminal state.
- Unit tests that instantiate `new BlackboardRegistry()` in `@BeforeEach` — no
  shared singleton, no accumulation.
- Adding a `clear()` blast method — `evict(caseId)` is sufficient.
