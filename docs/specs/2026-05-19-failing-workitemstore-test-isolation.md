# Spec: FailingWorkItemStore Test Isolation (engine#282)

**Date:** 2026-05-19
**Issue:** casehubio/engine#282

---

## Problem

`HumanTaskScheduleHandlerTest.FailingWorkItemStore` is listed in
`work-adapter/src/test/resources/application.properties` under
`quarkus.arc.selected-alternatives`. With `@Priority(2)` it beats
`InMemoryWorkItemStore` (`@Priority(1)`) and becomes the active
`WorkItemStore` for every `@QuarkusTest` class in the module.

Only one test method — `inlineMode_workItemCreationFails_planItemStaysPending_storeNotUpdated` —
needs it. The other eight tests in `HumanTaskScheduleHandlerTest` and all
tests in sibling classes run with a non-default store they did not ask for.
`setUp()` carries dead `instanceof FailingWorkItemStore` branches as a
symptom of the leakage.

---

## Design

### Remove from global alternatives

Delete the `HumanTaskScheduleHandlerTest$FailingWorkItemStore` entry from
`work-adapter/src/test/resources/application.properties`. After this change
`InMemoryWorkItemStore` is the active `WorkItemStore` for the whole module.

### `HumanTaskScheduleHandlerTest` — simplified

Runs on the default Quarkus test profile (no annotation). `setUp()` drops
the `instanceof FailingWorkItemStore` arm — only the `InMemoryWorkItemStore`
clear remains. All existing happy-path and negative-path handler tests stay
in this class unchanged.

### `HumanTaskScheduleHandlerAtomicityTest` — new class

Scoped to the single atomicity assertion.

```
@QuarkusTest
@TestProfile(HumanTaskScheduleHandlerAtomicityTest.Profile.class)
class HumanTaskScheduleHandlerAtomicityTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(FailingWorkItemStore.class);
        }
    }

    @Alternative @Priority(2) @ApplicationScoped
    public static class FailingWorkItemStore implements WorkItemStore { ... }

    // one test: inlineMode_workItemCreationFails_planItemStaysPending_storeNotUpdated
}
```

`FailingWorkItemStore` moves from `HumanTaskScheduleHandlerTest` to
`HumanTaskScheduleHandlerAtomicityTest`. The `shouldFail` static
`AtomicBoolean` and in-memory `store` map are unchanged. `setUp()` in
the new class registers the case/planItem, clears the store map, and
resets `shouldFail` to `false`.

**Profile restart:** Quarkus restarts the application once when switching
from the default profile to `Profile`. Expected and acceptable.

---

## Out of scope

- `engine#290` — `Thread.sleep(300)` negative-path assertions in
  `HumanTaskScheduleHandlerTest` should be replaced with Awaitility.
  Filed; not addressed here.
