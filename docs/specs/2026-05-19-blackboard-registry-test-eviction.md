# Spec: BlackboardRegistry Test Eviction (engine#292)

**Date:** 2026-05-19
**Issue:** casehubio/engine#292

---

## Problem

`BlackboardRegistry` is an `@ApplicationScoped` singleton backed by three
`ConcurrentHashMap` structures. Three `@QuarkusTest` classes in `work-adapter`
call `registry.getOrCreate(caseId)` in `@BeforeEach` to set up test state, but
never call `registry.evict(caseId)` afterwards. Entries accumulate across all
test methods in a session.

Currently harmless — each test generates a fresh `UUID.randomUUID()` — but the
asymmetry between "clear WorkItemStore/PlanItemStore" and "never clear registry"
is a latent pollution trap.

`BlackboardRegistry.evict(UUID)` already exists and removes all three maps for
the given caseId. The `CaseEvictionHandler` fires it automatically on terminal
case status — but the affected tests drive the registry directly without
starting a case lifecycle, so the handler never fires.

The blackboard integration tests (`BasicBlackboardTest` etc.) are **not
affected** — they start real cases via `CaseHub` beans and `CaseEvictionHandler`
cleans up automatically when the case terminates.

---

## Affected Classes

| File | Module |
|------|--------|
| `work-adapter/src/test/java/io/casehub/workadapter/HumanTaskScheduleHandlerTest.java` | `casehub-engine-work-adapter` |
| `work-adapter/src/test/java/io/casehub/workadapter/HumanTaskScheduleHandlerAtomicityTest.java` | `casehub-engine-work-adapter` |
| `work-adapter/src/test/java/io/casehub/workadapter/WorkItemLifecycleAdapterTest.java` | `casehub-engine-work-adapter` |

---

## Design

Add an `@AfterEach tearDown()` method to each of the three affected test
classes:

```java
@AfterEach
void tearDown() {
  registry.evict(caseId);
}
```

No changes to `BlackboardRegistry`, no new API, no other files.

`HumanTaskScheduleHandlerAtomicityTest.setUp()` is `@Transactional`; the new
`tearDown()` does not need to be — `evict()` is a pure in-memory operation.

---

## Out of scope

- Blackboard integration tests — handled by `CaseEvictionHandler` automatically.
- Unit tests that instantiate `new BlackboardRegistry()` — no shared state.
- Adding a `clear()` method to `BlackboardRegistry` — `evict(caseId)` is
  sufficient and already exists.
