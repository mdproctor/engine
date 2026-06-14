# Design: casehub-engine-inbound — InboundMessage → WorkItem bridge

**Issues:** engine#468 (module + bridge), engine#469 (tests)
**Date:** 2026-06-13

---

## Context

Two Foundation-tier peers — `casehub-qhorus` and `casehub-work` — cannot depend on each other. When a qhorus channel receives a human message, there is no platform mechanism to turn it into a WorkItem. This module is the bridge.

The trigger is a `MessageReceivedEvent` dispatched synchronously by `MessageObserverDispatcher` to all registered `MessageObserver` beans. `InboundWorkItemBridge` implements `MessageObserver` and delegates to a deployment-provided `InboundWorkItemPolicy` to decide whether and how to create a WorkItem.

**Post-commit timing:** In production, `MessageObserverDispatcher.dispatch()` defers observer calls to the JTA `afterCompletion(STATUS_COMMITTED)` callback (qhorus#166). This means when `onMessage()` runs, there is no active JTA transaction. `WorkItemService.create()` is `@Transactional` and starts its own transaction. `TenantContextRunner.runInTenantContext()` activates a new CDI request context (none is active in `afterCompletion`). Tests call `onMessage()` directly, bypassing the qhorus transaction entirely — which is fine and requires no `@Transactional` setup on test methods.

---

## Module structure

New Maven module: `casehub-engine-inbound`

- **Artifact id:** `casehub-engine-inbound`
- **Package root:** `io.casehub.engine.inbound`
- **Position in root `pom.xml`:** after `work-adapter`, before `runtime` — consumers activate by adding to classpath; no other engine module depends on it
- **No JPA entities, no Flyway**
- **Quartz:** `casehub-work` (runtime) depends on `quarkus-quartz` (compile scope) — `WorkItemTimerService` injects `@Inject Scheduler scheduler`. Quartz is therefore transitively on the classpath; no explicit production or test dep needed. RAM store must be configured in test `application.properties`.

**Production dependencies:**
- `casehub-qhorus-api` — `MessageObserver`, `MessageReceivedEvent`
- `casehub-work` (runtime) — `WorkItemService`, `TenantContextRunner`, `WorkItemCreateRequest`
- `quarkus-arc`

**Test dependencies:**
- `casehub-work-testing` — `InMemoryWorkItemStore`, `InMemoryAuditEntryStore`
- `quarkus-jdbc-h2` — casehub-work JPA entities require a datasource

---

## SPI: `InboundWorkItemPolicy`

```java
@FunctionalInterface
public interface InboundWorkItemPolicy {
    Optional<WorkItemCreateRequest> decide(MessageReceivedEvent event);
}
```

**Placement:** `io.casehub.engine.inbound` in the bridge module — not in `api/spi/`. Per PP-20260601-c43112: the SPI references `WorkItemCreateRequest` from `casehub-work`, which must not become a transitive dependency of `casehub-engine-api` consumers.

**CDI semantics:**
- No `@DefaultBean` — module is completely inert with no policy bean present
- Single policy bean expected. Ambiguity (`isAmbiguous()`) is a deployment error; the bridge throws `IllegalStateException` at startup via `@Observes StartupEvent`. Consumers needing multiple policies compose them in a single `@ApplicationScoped` implementation.

**Contract:**
- `Optional.empty()` → message silently ignored, no WorkItem created
- `Optional.of(request)` → WorkItem created under the event's `tenancyId`
- Throw → logged at WARN, no WorkItem created, no case impact (see Exception handling below)

**`createdBy`:** stamped unconditionally by the bridge (`"casehub-engine-inbound"`). The policy sets every other field; it must not concern itself with the bridge's identity.

**`callerRef` interop:** if the policy sets `callerRef` to `case:{caseId}/pi:{planItemId}` format AND `casehub-engine-work-adapter` is on the classpath, the WorkItem's lifecycle events (COMPLETED, REJECTED, EXPIRED) will be wired back to that PlanItem — which may or may not be the intent. Bridge-created WorkItems with no case backing should use `null` or a custom `callerRef` format to avoid unintended adapter pickup.

**Channel filtering:** the policy returns `Optional.empty()` to ignore irrelevant messages. `MessageObserver.channels()` is not used — CaseHub channels are dynamic (per-case UUIDs, per-PR names); static exact-match filtering at the dispatcher level does not fit the platform's channel model.

---

## Bridge: `InboundWorkItemBridge`

```java
@ApplicationScoped
public class InboundWorkItemBridge implements MessageObserver {

    private static final Logger LOG = Logger.getLogger(InboundWorkItemBridge.class);

    @Inject Instance<InboundWorkItemPolicy> policy;
    @Inject WorkItemService workItemService;
    @Inject TenantContextRunner tenantContextRunner;

    void onStartup(@Observes StartupEvent ignored) {
        if (policy.isAmbiguous()) {
            throw new IllegalStateException(
                "Multiple InboundWorkItemPolicy beans found — compose them in a single " +
                "@ApplicationScoped implementation");
        }
    }

    @Override
    public void onMessage(MessageReceivedEvent event) {
        if (policy.isUnsatisfied()) return;

        Optional<WorkItemCreateRequest> decision;
        try {
            decision = policy.get().decide(event);
        } catch (Exception e) {
            LOG.warnf(e, "InboundWorkItemPolicy.decide() threw for channel %s — message ignored",
                event.channelName());
            return;
        }

        decision.ifPresent(request ->
            tenantContextRunner.runInTenantContext(event.tenancyId(), () ->
                workItemService.create(stamp(request))));
    }

    private WorkItemCreateRequest stamp(WorkItemCreateRequest request) {
        return request.toBuilder()
            .createdBy("casehub-engine-inbound")
            .build();
    }
}
```

**Exception handling:** policy call is isolated in its own `try/catch` — a policy exception logs with channel context and returns early. Infrastructure calls (`TenantContextRunner`, `WorkItemService`) are outside the `try` block and propagate uncaught out of `onMessage()`, landing in `MessageObserverDispatcher.dispatchToHandles()`'s `catch(Exception e)` which logs at WARN with observer class context. Both failure paths are fully handled; neither is silently swallowed; the log messages are accurate to the actual failure.

**`stamp()` uses `request.toBuilder()`**, not `builder().from()`. `WorkItemCreateRequest.toBuilder()` is confirmed to exist: `public Builder toBuilder() { return new Builder(this); }`.

---

## Test structure

**Two test classes:**

### `InboundWorkItemBridgeTest` — `@QuarkusTest`

CDI context with `RecordingPolicy` and `RecordingTenantContextRunner` as test alternatives.

**CDI test setup:**
- `RecordingPolicy` — static inner `@Alternative @Priority(1) @ApplicationScoped`; captures last `MessageReceivedEvent`, returns configurable `Optional<WorkItemCreateRequest>`; reset in `@BeforeEach`
- `RecordingTenantContextRunner` — static inner `@Alternative @Priority(1) @ApplicationScoped` subclass of `TenantContextRunner`; records the `tenancyId` passed to `runInTenantContext()` before delegating to `super`; used to verify tenant threading without relying on `WorkItem.tenancyId` (which is set by JPA multi-tenancy at DB layer, not by `WorkItemService.create()`)
- `StubWorkloadProvider` — static inner `@Alternative @Priority(1) @ApplicationScoped`; returns `0` from `getActiveWorkCount()`; required because `WorkItemAssignmentService` constructor-injects `WorkloadProvider workloadProvider` directly (not `Instance<>`), and `JpaWorkloadProvider` is excluded — leaving `WorkloadProvider` unsatisfied without this stub
- `NoOpPreferenceProvider` — static inner `@Alternative @Priority(1) @ApplicationScoped`
- `InMemoryWorkItemStore` — activated via `quarkus.arc.selected-alternatives`

**Test cases:**
```
// Happy path
decide_returnsRequest_workItemCreated()
decide_returnsRequest_createdByIsStampedByBridge()
decide_returnsRequest_tenancyIdThreadedToTenantContextRunner()  // asserts RecordingTenantContextRunner.lastTenancyId

// Policy filtering
decide_returnsEmpty_noWorkItemCreated()
decide_policyReceivesFullEvent_channelName_messageType_senderId_correlationId()

// Fault tolerance
decide_throws_noWorkItemCreated_noExceptionPropagated()

// Wiring
messageObserver_bridgeIsRegistered()  // Instance<MessageObserver> contains InboundWorkItemBridge
```

### `InboundWorkItemBridgeNoPolicyTest` — plain unit test (no `@QuarkusTest`)

Tests the `isUnsatisfied()` guard without CDI overhead. Instantiates `InboundWorkItemBridge` directly, injects a mock `Instance<InboundWorkItemPolicy>` returning `isUnsatisfied() = true`, calls `onMessage()`, asserts no WorkItem created and no exception thrown.

```
noPolicy_messageReceived_silentlyIgnored()
```

**Ambiguity guard** (`ambiguousPolicy_atStartup_throwsIllegalStateException()`) is also a plain unit test: mock `Instance` returning `isAmbiguous() = true`, call `bridge.onStartup(null)`, assert `IllegalStateException`. Cannot be `@QuarkusTest` — deploying two policy beans would fail Quarkus startup itself.

**`src/test/resources/application.properties`:**
```properties
quarkus.http.test-port=0
quarkus.quartz.store-type=ram
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.datasource.username=sa
quarkus.datasource.password=
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
# Suppress named query validation errors from casehub-ledger schema drift
quarkus.hibernate-orm.unsupported-properties."hibernate.query.validate_named_queries"=false
# Exclude JPA stores and mock providers — InMemory alternatives and test stubs replace them
quarkus.arc.exclude-types=\
  io.casehub.work.runtime.repository.jpa.JpaWorkItemStore,\
  io.casehub.work.runtime.service.JpaWorkloadProvider,\
  io.casehub.platform.mock.MockCurrentPrincipal,\
  io.casehub.platform.mock.MockGroupMembershipProvider,\
  io.casehub.platform.mock.MockPreferenceProvider
quarkus.arc.selected-alternatives=\
  io.casehub.work.testing.InMemoryWorkItemStore,\
  io.casehub.engine.inbound.InboundWorkItemBridgeTest$NoOpPreferenceProvider
```

---

## Known limitations

**WorkItem creation is at-most-once.** `onMessage()` runs in the qhorus `afterCompletion(STATUS_COMMITTED)` callback — the qhorus message is durably committed. If `WorkItemService.create()` fails (transient DB timeout, connection reset), no WorkItem is created and no retry occurs. The message remains in qhorus and can be replayed manually, but the engine has no mechanism to detect or recover from this automatically. This is an inherent property of the `MessageObserver` dispatch model and is not specific to this bridge.

---

## Out of scope

- Full `InboundMessage → ConnectorChannelBackend → MessageReceivedEvent` chain — tested in `casehub-qhorus-connector-backend` and application-level integration tests
- `WorkItemService` internals — absorbed by `InMemoryWorkItemStore`
- Reactive variant — blocking `WorkItemService` is sufficient; reactive path added when a consumer requires it
