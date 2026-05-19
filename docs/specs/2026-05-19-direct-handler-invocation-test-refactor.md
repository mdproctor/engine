# Spec: Direct Handler Invocation in HumanTaskScheduleHandlerTest (engine#290)

**Date:** 2026-05-19
**Issues:** casehubio/engine#290, casehubio/engine#291

---

## Problem

`HumanTaskScheduleHandlerTest` routes events through the Vert.x event bus
(`eventBus.publish(...)`), which is fire-and-forget. This forces:

- **Positive-path tests (5):** `await().atMost(5, SECONDS).untilAsserted(...)` to
  bridge the async gap
- **Negative-path tests (4):** `Thread.sleep(300)` to guess when the handler
  has finished — creating a false-green risk if the handler hasn't run yet

The event bus dispatch is one line of Quarkus framework configuration. Testing
it is testing Quarkus, not our code. What needs testing is the handler logic.

---

## Design

### Direct invocation

Add `@Inject HumanTaskScheduleHandler handler` to the test class. Replace every
`eventBus.publish(EventBusAddresses.HUMAN_TASK_SCHEDULE, event)` call with
`handler.onHumanTaskSchedule(event)`. The method is `public`; `@Transactional`
is enforced via the CDI proxy on the injected bean — transaction behaviour is
identical to production.

### Positive-path tests (5)

Remove each `await().atMost(5, SECONDS).untilAsserted(...)` block. The handler
returns synchronously with the transaction committed. All assertions follow
immediately — callerRef, title, WorkItemStatus, PlanItemStatus, planItemStore
record check. No assertion is removed or weakened.

### Negative-path tests (4)

Remove each `Thread.sleep(300)` block. The handler returns synchronously
without creating a WorkItem. Assertions follow immediately — PlanItem stays
PENDING, store is empty.

### Wiring smoke test (new)

Add one test that exercises the event bus route end-to-end:

```java
@Test
void eventBus_routesHumanTaskScheduleEvent_toHandler() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Smoke").build();
    eventBus.publish(
        EventBusAddresses.HUMAN_TASK_SCHEDULE,
        new HumanTaskScheduleEvent(caseId, "irb-binding", target, Map.of()));
    await().atMost(5, TimeUnit.SECONDS)
           .untilAsserted(() -> assertThat(planItem.getStatus())
               .isEqualTo(PlanItemStatus.RUNNING));
}
```

This is the only test that uses the event bus and Awaitility. It covers the
one legitimate concern: does `@ConsumeEvent(HUMAN_TASK_SCHEDULE)` actually
route to `HumanTaskScheduleHandler`?

### Fix engine#291 in the same pass

`templateMode_withInputData_usesInputDataAsPayload` assigns `tmpl.defaultPayload`
after the `@Transactional` persist method returns — the entity is detached, the
write is silently dropped, and the test never actually proves inputData overrides
a persisted default. Fix: use `persistTemplate(name, defaultPayload)` directly
so the default is in the database.

### Cleanup

- `@Inject EventBus eventBus` stays (needed by the wiring test)
- `import static org.awaitility.Awaitility.await` stays (needed by the wiring test)
- `import java.util.concurrent.TimeUnit` stays (needed by the wiring test)
- Remove `InterruptedException` catch blocks (removed with the sleeps)
- Update class Javadoc to reflect current scope

---

## Invariants

Every assertion from every existing test is preserved. The only things removed
are timing machinery (`await`, `Thread.sleep`) and the associated boilerplate.
Test count: 9 existing + 1 new wiring test = 10 total.
