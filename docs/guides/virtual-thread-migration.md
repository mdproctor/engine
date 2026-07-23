# Virtual Thread Migration Guide

Migration cookbook for CaseHub repos moving from reactive Uni to virtual threads.
Applies to: engine, platform, qhorus, neocortex, ledger, eidos, work, and all app repos.

Refs: casehubio/parent#379 (ADR-0005), casehubio/parent#381 (engine), casehubio/parent#384 (all repos).

---

## Guiding Principle

The codebase should read as though virtual threads were the original design.
No dual-stack remnants, no bridge classes, no `runSubscriptionOn` wrappers.
Reactive I/O stays (Vert.x event bus, SSE endpoints) — the reactive
*programming model* (`Uni<T>`, Mutiny chains) goes.

---

## 1. SPIs — Single Interface Per Concern

Delete every `Reactive*` interface. The blocking interface IS the SPI.

```
# Before (28 interfaces)
CaseInstanceRepository              + ReactiveCaseInstanceRepository
WorkerProvisioner                   + ReactiveWorkerProvisioner

# After (14 interfaces)
CaseInstanceRepository
WorkerProvisioner
```

**Method signatures change:**
- `Uni<T>` → `T`
- `Uni<Void>` → `void`
- `Uni<List<T>>` → `List<T>`

**SPI evolution:** when adding new methods to an SPI, add a `default` method
to the single blocking interface. No reactive mirror needed.

---

## 2. Event Handlers — @RunOnVirtualThread + void

Every `@ConsumeEvent` handler follows one pattern:

```java
@ConsumeEvent(value = EventBusAddresses.SOME_EVENT)
@RunOnVirtualThread
void onSomeEvent(SomeEvent event) {
    // sequential blocking code — virtual thread handles it
    repository.save(entity, tenancyId);
    eventBus.publish(NEXT_ADDRESS, nextEvent);
}
```

**What to change:**

| Before | After |
|--------|-------|
| `Uni<Void>` return type | `void` |
| `blocking = true` | `@RunOnVirtualThread` |
| No annotation (event loop) | `@RunOnVirtualThread` |
| `return Uni.createFrom().voidItem()` | just return |
| `.chain(() -> nextOp())` | call `nextOp()` on the next line |
| `.onFailure().invoke(e -> log(e))` | try/catch |

**Error handling:** handlers must catch exceptions explicitly. On the event
loop, an unhandled exception in a `Uni` chain was swallowed or logged by
Mutiny. On a virtual thread, an unhandled exception kills the thread silently.
Wrap handler bodies in try/catch with logging.

**CDI events:** `Event.fireAsync()` stays — it's CDI, not Mutiny. `fire()`
(synchronous) also stays. Neither changes.

---

## 3. Persistence — Blocking JPA

Replace Panache Reactive with standard JPA.

### Dependencies

```xml
<!-- Remove -->
<artifactId>quarkus-hibernate-reactive-panache</artifactId>
<artifactId>quarkus-reactive-pg-client</artifactId>

<!-- Add (or keep if already present) -->
<artifactId>quarkus-hibernate-orm</artifactId>
<artifactId>quarkus-jdbc-postgresql</artifactId>
```

### Entities

Plain `@Entity`. No Panache base class.

```java
// Before
@Entity
public class MyEntity extends PanacheEntity {
    // id inherited from PanacheEntity
    public String name;
}

// After
@Entity
public class MyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String name;
}
```

### Repositories

Inject `EntityManager`. Use `@Transactional`. Write JPQL directly.

```java
// Before — reactive canonical
@ApplicationScoped
public class JpaReactiveThingRepository extends TenantAwareRepository
        implements ReactiveThingRepository {

    @Override
    public Uni<Thing> save(Thing thing, String tenancyId) {
        return withTenantTransaction(tenancyId, () -> {
            var entity = toEntity(thing, tenancyId);
            return session.persist(entity).replaceWith(() -> fromEntity(entity));
        });
    }
}

// Before — blocking delegate
@ApplicationScoped
public class JpaThingRepository implements ThingRepository {
    @Inject JpaReactiveThingRepository delegate;

    @Override
    public Thing save(Thing thing, String tenancyId) {
        return delegate.save(thing, tenancyId).await().indefinitely();
    }
}

// After — single blocking implementation
@ApplicationScoped
public class JpaThingRepository extends TenantAwareRepository
        implements ThingRepository {

    @Override
    @Transactional
    public Thing save(Thing thing, String tenancyId) {
        setTenantContext(tenancyId);
        var entity = toEntity(thing, tenancyId);
        em.persist(entity);
        em.flush();
        thing.id = entity.id;
        return thing;
    }
}
```

### TenantAwareRepository Base Class

```java
public abstract class TenantAwareRepository {
    @Inject EntityManager em;

    @ConfigProperty(name = "casehub.rls.enabled", defaultValue = "false")
    boolean rlsEnabled;

    protected void setTenantContext(String tenancyId) {
        if (tenancyId == null || tenancyId.contains("'") || tenancyId.contains("\\")) {
            throw new IllegalArgumentException("Invalid tenancyId: " + tenancyId);
        }
        em.createNativeQuery("SET LOCAL \"casehub.tenancy_id\" = :tid")
            .setParameter("tid", tenancyId)
            .executeUpdate();
    }

    protected void setCrossTenantContext() {
        if (rlsEnabled) {
            em.createNativeQuery("SET LOCAL ROLE casehub_crosstenancy")
                .executeUpdate();
        }
    }
}
```

**Transaction boundary rule:** `@Transactional` goes on the repository method.
`SET LOCAL` runs inside that transaction. If a handler needs multiple repository
calls in one transaction, put `@Transactional` on the handler method instead —
the repository methods participate in the existing transaction.

### In-Memory Implementations

Delete all `InMemoryReactive*` classes. Keep only the blocking implementations
(`InMemory*Repository`). They already use `ConcurrentHashMap` — no changes needed.

### Delete List

Per repo, delete:
- All `Reactive*` SPI interfaces
- All `JpaReactive*` implementation classes
- All `InMemoryReactive*` wrapper classes
- All `NoOpReactive*` / `EmptyReactive*` no-op beans
- All `*Bridge` classes that connect blocking↔reactive
- All `Reactive*ContractTest` classes
- `AbstractJpaRepository` (Vert.x context safety — reactive concern)
- `ReactiveUtils` (if present)

---

## 4. Bridge and Adapter Classes — Eliminate

Bridge classes exist solely to connect blocking code to reactive pipelines.
With no reactive pipeline, they have no purpose.

```java
// Before — bridge wrapping blocking classifier for reactive chain
@ApplicationScoped
public class ChainedReactiveActionRiskClassifier
        implements ReactiveActionRiskClassifier {
    @Any Instance<ActionRiskClassifier> blockingClassifiers;

    public Uni<RiskDecision> classify(PlannedAction action, ClassificationContext ctx) {
        return Uni.createFrom().item(() -> {
            // iterate blocking classifiers, most restrictive wins
        }).runSubscriptionOn(workerPool);
    }
}

// After — direct iteration, no bridge
@ApplicationScoped
public class ChainedActionRiskClassifier implements ActionRiskClassifier {
    @Any Instance<ActionRiskClassifier> classifiers;

    public RiskDecision classify(PlannedAction action, ClassificationContext ctx) {
        // iterate classifiers, most restrictive wins
        // already on a virtual thread — blocking is fine
    }
}
```

**Pattern to search for and eliminate:**
- `runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` — delete the wrapper, call directly
- `Uni.createFrom().item(() -> blocking())` — call `blocking()` directly
- `.await().indefinitely()` — call the blocking method directly
- `BlockingToReactive*Bridge` classes — delete entirely

---

## 5. Public API Changes

### CaseHubRuntime

| Method | Before | After |
|--------|--------|-------|
| `signal(UUID, String, Object)` | `CompletionStage<Void>` | `void` |
| `signal(UUID, Map)` | `CompletionStage<Void>` | `void` |
| `signalAndAwait(UUID, Map, Duration)` | `CompletionStage<CaseContext>` | `CaseContext` |
| `startCase(...)` | `CompletionStage<UUID>` | `UUID` |
| `query(UUID, String)` | `CompletionStage<Object>` | `Object` |

### Consumer SPI Implementations

If your repo implements any engine `Reactive*` SPI:

1. Switch `implements ReactiveThingProvider` → `implements ThingProvider`
2. Change return types: `Uni<T>` → `T`, `Uni<Void>` → `void`
3. Remove Mutiny wrapping — return values directly
4. Remove `@RunOnVirtualThread` if the method is called from a handler
   that already runs on a virtual thread (avoid nesting)

---

## 6. Configuration Changes

```properties
# Remove (reactive datasource)
quarkus.datasource.reactive.url=...
quarkus.datasource.reactive.max-size=...

# Keep / Add (JDBC datasource)
quarkus.datasource.jdbc.url=jdbc:postgresql://...
quarkus.datasource.jdbc.max-size=20

# Keep
quarkus.quartz.store-type=ram
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
```

### Test properties

```properties
# Remove
quarkus.arc.selected-alternatives=InMemoryReactiveCaseInstanceRepository,...

# Keep only blocking alternatives
quarkus.arc.selected-alternatives=InMemoryCaseInstanceRepository,...
```

---

## 7. Where Reactive Stays

Reactive primitives are fine at specific integration points:

- **SSE endpoints** — `Multi<T>` for server-sent events (streaming by nature)
- **Qhorus channel I/O** — if the channel backend is genuinely async
- **Message broker consumers** — Kafka/AMQP if using reactive connectors
- **WebSocket endpoints** — long-lived connections, same as SSE

The rule: reactive is a *local choice* at a specific site, not a global
constraint that shapes the SPI layer. If a method is called from a
`@RunOnVirtualThread` handler, it should be blocking. If it IS the
streaming endpoint, it can be reactive.

**`@RunOnVirtualThread` must NOT be used on SSE or WebSocket endpoints.**
SSE `SseEventSink` + `void` return endpoints are long-lived streaming
connections. The interaction with `@RunOnVirtualThread` is undocumented
in Quarkus and risks virtual thread monopolisation. Instead, keep SSE
endpoints on the event loop and offload blocking calls explicitly:

```java
private static final ExecutorService VIRTUAL_EXECUTOR =
    Executors.newVirtualThreadPerTaskExecutor();

// SSE setup — runs on event loop, offloads blocking call
VIRTUAL_EXECUTOR.execute(() -> {
    long count = store.unreadCount(userId, tenancyId);
    sendUnreadCount(eventSink, sse, count);
});
```

CDI `@ObservesAsync` handlers run on managed executor threads — blocking
calls are safe there without offloading.

See protocol: `sse-endpoint-no-virtual-thread` in the garden.

---

## 8. Validation Checklist

After migration, verify:

- [ ] No `Reactive*` interfaces remain in `src/main/java`
- [ ] No `Uni<` appears in handler return types
- [ ] No `blocking = true` on `@ConsumeEvent` (use `@RunOnVirtualThread`)
- [ ] No `runSubscriptionOn` calls remain
- [ ] No `.await().indefinitely()` calls remain
- [ ] No `Uni.createFrom().item()` wrapping blocking code
- [ ] No `*Bridge` classes connecting blocking↔reactive
- [ ] No `quarkus-hibernate-reactive-panache` in any pom.xml
- [ ] No `quarkus-reactive-pg-client` in any pom.xml (unless genuinely needed)
- [ ] `@Transactional` on repository methods that do writes
- [ ] RLS `SET LOCAL` runs inside the `@Transactional` boundary
- [ ] Entity classes use `@Id @GeneratedValue`, not `PanacheEntity`
- [ ] Tests pass with in-memory implementations (blocking only)
- [ ] CLAUDE.md updated — no dual-stack documentation remains

---

## 9. Common Mistakes

| Mistake | Why it's wrong | Fix |
|---------|---------------|-----|
| Keeping `Uni<Void>` return type on a handler "for compatibility" | Creates a hybrid that's harder to reason about than either pure state | Convert to `void` + `@RunOnVirtualThread` |
| Adding `@Transactional` to the handler AND the repository method | Double transaction — the inner `@Transactional` is a no-op (joins the existing) | Put `@Transactional` on whichever layer owns the transaction scope |
| Using `blocking = true` instead of `@RunOnVirtualThread` | `blocking = true` uses the worker thread pool (bounded). `@RunOnVirtualThread` uses virtual threads (unbounded, cheap) | Always `@RunOnVirtualThread` |
| Deleting reactive but keeping `AbstractJpaRepository` | It exists only for Vert.x context safety — a reactive concern | Delete it |
| Leaving `InMemoryReactive*` "in case someone needs it" | Dead code. No reactive SPIs remain to inject it | Delete it |
| Using `em.getTransaction()` manually | Quarkus manages JTA transactions via `@Transactional` | Never call `em.getTransaction()` — use the annotation |
| Adding `@RunOnVirtualThread` to an SSE endpoint | SSE connections are long-lived. Virtual thread stays pinned for the connection lifetime. Undocumented interaction in Quarkus | Keep SSE on event loop. Offload blocking calls to `Executors.newVirtualThreadPerTaskExecutor()` |
