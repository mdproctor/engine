# Persistence Decoupling — Pre-Plan Notes
**Date:** 2026-04-15
**Status:** Ready for plan writing — break into 3 separate plan files, one per PR

Spec: `docs/superpowers/specs/2026-04-15-persistence-decoupling-design.md`

---

## Key constraint discovered during planning

The engine handlers currently use `Panache.withTransaction()`. After decoupling, **the engine has no Panache dependency**. So handlers cannot call `Panache.withTransaction()` directly.

Two handlers need ATOMIC writes across both `CaseInstance` (state update) and `EventLog` (event append):
- `CaseStatusChangedHandler`
- `WorkerRetriesExhaustedEventHandler`

**Solution:** Add `updateStateAndAppendEvent(CaseInstance, EventLog)` to `CaseInstanceRepository` SPI. JPA impl wraps both in one `Panache.withTransaction()`. Memory impl does both synchronously.

Updated SPI (add this method to spec):
```java
/** Atomically update case state and append a lifecycle event. */
Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog);
```

---

## 3-PR decomposition

**PR 1: `feat/persistence/hibernate`** → targets `feat/rename-binding-casedefinition`
- Add 3 SPI interfaces to `engine/spi/`
- Create `casehub-persistence-hibernate` module
- JPA entity classes + JPA repository implementations
- Move Flyway migrations from `engine` to `casehub-persistence-hibernate`
- Integration tests (@QuarkusTest, TestContainers, PostgreSQL)

**PR 2: `feat/persistence/memory`** → targets `feat/persistence/hibernate`
- Create `casehub-persistence-memory` module
- In-memory implementations of 3 SPI interfaces
- Unit tests (no Docker, no Quarkus)

**PR 3: `feat/persistence/engine-decoupling`** → targets `feat/persistence/memory`
- Strip JPA from `CaseInstance`, `EventLog`, `CaseMetaModel` → plain POJOs
- Refactor all 14 handlers/services to inject repositories
- Remove `quarkus-hibernate-reactive-panache`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `testcontainers-postgresql` from engine pom
- Add `casehub-persistence-memory` (test scope) to engine pom
- Rewrite engine test `application.properties` (RAM Quartz, in-memory alternatives)
- All existing engine tests pass without Docker

---

## Branch setup (run before starting each plan)

```bash
cd /Users/mdproctor/dev/casehub-engine

# PR 1
git checkout feat/rename-binding-casedefinition
git checkout -b feat/persistence/hibernate

# PR 2 (after PR 1 done)
git checkout feat/persistence/hibernate
git checkout -b feat/persistence/memory

# PR 3 (after PR 2 done)
git checkout feat/persistence/memory
git checkout -b feat/persistence/engine-decoupling
```

---

## Repo locations

- Engine: `/Users/mdproctor/dev/casehub-engine/`
- Maven: `/opt/homebrew/bin/mvn`
- Upstream repo: `casehubio/engine`
- Fork: `mdproctor/engine`

---

## Existing Flyway migrations (move all to casehub-persistence-hibernate)

```
engine/src/main/resources/db/migration/
  V1.0.0__Create_Quartz_Tables.sql       ← Quartz JDBC tables
  V1.1.0__Create_Application_Tables.sql  ← case_meta_model, case_instance, event_log
  V1.2.0__Align_CaseStatus_With_Serverless_Workflow.sql
  V1.3.0__Add_Parent_Plan_Item_Id.sql
```

Note: V1.0.0 (Quartz) only needed with JDBC store, but include it in hibernate module so it's part of the full schema.

---

## Key DB schema facts

From `V1.1.0`:
- `case_meta_model.id` — uses sequence `case_meta_model_seq`
- `case_instance.case_definition_id` — FK to `case_meta_model(id)` (column name IS `case_definition_id`, not `case_meta_model_id`)
- `event_log.seq` — `BIGINT GENERATED ALWAYS AS IDENTITY` (separate from `id`)
- `event_log.id` — uses sequence `event_log_seq`

---

## SPI interfaces (in `engine/src/main/java/io/casehub/engine/spi/`)

### CaseMetaModelRepository.java
```java
package io.casehub.engine.spi;

import io.casehub.engine.internal.model.CaseMetaModel;
import io.smallrye.mutiny.Uni;

public interface CaseMetaModelRepository {
    Uni<CaseMetaModel> findByKey(String namespace, String name, String version);
    Uni<CaseMetaModel> save(CaseMetaModel metaModel);
}
```

### CaseInstanceRepository.java
```java
package io.casehub.engine.spi;

import io.casehub.engine.internal.history.EventLog;
import io.casehub.engine.internal.model.CaseInstance;
import io.smallrye.mutiny.Uni;
import java.util.UUID;

public interface CaseInstanceRepository {
    Uni<CaseInstance> save(CaseInstance instance);
    Uni<CaseInstance> update(CaseInstance instance);
    Uni<CaseInstance> findByUuid(UUID uuid);
    /** Atomically update case state and append a lifecycle event. */
    Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog);
}
```

### EventLogRepository.java
```java
package io.casehub.engine.spi;

import io.casehub.engine.internal.history.CaseHubEventType;
import io.casehub.engine.internal.history.EventLog;
import io.smallrye.mutiny.Uni;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EventLogRepository {
    Uni<Void> append(EventLog eventLog);
    Uni<Long> appendAndReturnId(EventLog eventLog);
    Uni<EventLog> findById(Long id);
    Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId);
    Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types);
    Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types);
    Uni<List<EventLog>> findByCaseAndWorkerAndType(UUID caseId, String workerId, CaseHubEventType type);
}
```

---

## casehub-persistence-hibernate module

### pom.xml location
`casehub-persistence-hibernate/pom.xml`
Dependencies: `engine`, `quarkus-hibernate-reactive-panache`, `quarkus-reactive-pg-client`, `quarkus-jdbc-postgresql`, `quarkus-flyway`, `quarkus-arc`, TestContainers (test)

### JPA Entity classes (`io.casehub.persistence.jpa`)

#### CaseMetaModelEntity.java
```java
@Entity
@Table(name = "case_meta_model",
    uniqueConstraints = @UniqueConstraint(columnNames = {"namespace", "name", "version"}))
public class CaseMetaModelEntity extends PanacheEntity {
    @Column(nullable = false, length = 255) public String name;
    @Column(length = 255) public String namespace;
    @Column(nullable = false, length = 50) public String version;
    @Column(length = 500) public String title;
    @Column(length = 50) public String dsl;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") public JsonNode definition;
    @Column(name = "created_at", nullable = false, updatable = false) public Instant createdAt;
}
```

#### CaseInstanceEntity.java
```java
@Entity
@DynamicUpdate
@Table(name = "case_instance")
public class CaseInstanceEntity extends PanacheEntity {
    @Column(name = "uuid", nullable = false, unique = true, updatable = false) public UUID uuid;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false) public CaseStatus state;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "case_definition_id") public CaseMetaModelEntity caseMetaModel;
    @Column(name = "parent_plan_item_id", nullable = true) public Long parentPlanItemId;
}
```

#### EventLogEntity.java
```java
@Entity
@Table(name = "event_log")
public class EventLogEntity extends PanacheEntity {
    @Column(name = "seq", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    public Long seq;

    @Column(name = "case_id", nullable = false, updatable = false) public UUID caseId;
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 255) public CaseHubEventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type", nullable = false, length = 255) public EventStreamType streamType;
    @Column(name = "worker_id", nullable = true, length = 255) public String workerId;
    @Column(name = "timestamp", nullable = false) public Instant timestamp;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb") public JsonNode payload;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb") public JsonNode metadata;
}
```

### JPA Repository pattern

All repositories follow this pattern:
- `@ApplicationScoped` (default, no @Alternative)
- `Panache.withTransaction()` for writes
- `Panache.withSession()` for reads
- Private `toDomain(entity)` and `toEntity(domain)` converter methods

#### JpaCaseMetaModelRepository key methods
```java
public Uni<CaseMetaModel> findByKey(String namespace, String name, String version) {
    return Panache.withSession(() ->
        CaseMetaModelEntity.<CaseMetaModelEntity>find(
            "namespace = ?1 and name = ?2 and version = ?3", namespace, name, version)
        .firstResult()
    ).map(e -> e == null ? null : toDomain(e));
}

public Uni<CaseMetaModel> save(CaseMetaModel metaModel) {
    CaseMetaModelEntity entity = toEntity(metaModel);
    entity.createdAt = Instant.now();
    return Panache.withTransaction(() -> entity.persist())
        .map(v -> { metaModel.id = entity.id; metaModel.setCreatedAt(entity.createdAt); return metaModel; });
}
```

#### JpaCaseInstanceRepository key methods
```java
public Uni<CaseInstance> save(CaseInstance instance) {
    CaseInstanceEntity entity = toEntity(instance);
    return Panache.withTransaction(() -> entity.persist())
        .map(v -> { instance.id = entity.id; return instance; });
}

public Uni<CaseInstance> update(CaseInstance instance) {
    return Panache.withTransaction(() ->
        CaseInstanceEntity.<CaseInstanceEntity>findById(instance.id)
            .chain(entity -> {
                entity.state = instance.getState();
                entity.parentPlanItemId = instance.getParentPlanItemId();
                return Panache.getSession().chain(s -> s.merge(entity));
            })
    ).map(e -> instance);
}

public Uni<CaseInstance> findByUuid(UUID uuid) {
    return Panache.withSession(() ->
        CaseInstanceEntity.<CaseInstanceEntity>find(
            "from CaseInstanceEntity ci join fetch ci.caseMetaModel where ci.uuid = ?1", uuid)
        .firstResult()
    ).map(e -> e == null ? null : toDomain(e));
}

public Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog) {
    EventLogEntity logEntity = toEntity(eventLog);
    return Panache.withTransaction(() ->
        CaseInstanceEntity.<CaseInstanceEntity>findById(instance.id)
            .chain(entity -> {
                entity.state = instance.getState();
                return Panache.getSession().chain(s -> s.merge(entity));
            })
            .chain(merged -> logEntity.persist())
    ).invoke(() -> eventLog.id = logEntity.id).replaceWithVoid();
}
```

#### JpaEventLogRepository key methods
```java
public Uni<Void> append(EventLog eventLog) {
    EventLogEntity entity = toEntity(eventLog);
    return Panache.withTransaction(() -> entity.persist())
        .invoke(() -> { eventLog.id = entity.id; eventLog.setSeq(entity.seq); })
        .replaceWithVoid();
}

public Uni<Long> appendAndReturnId(EventLog eventLog) {
    EventLogEntity entity = toEntity(eventLog);
    return Panache.withTransaction(() -> entity.persistAndFlush())
        .map(v -> { eventLog.id = entity.id; eventLog.setSeq(entity.seq); return entity.id; });
}

public Uni<EventLog> findById(Long id) {
    return Panache.withSession(() -> EventLogEntity.<EventLogEntity>findById(id))
        .map(e -> e == null ? null : toDomain(e));
}

public Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId) {
    return Panache.withSession(() ->
        EventLogEntity.<EventLogEntity>find(
            "caseId = ?1 and workerId = ?2 and eventType in (?3, ?4, ?5)",
            caseId, workerId,
            CaseHubEventType.WORKER_SCHEDULED,
            CaseHubEventType.WORKER_EXECUTION_STARTED,
            CaseHubEventType.WORKER_EXECUTION_COMPLETED)
        .list()
    ).map(list -> list.stream().map(this::toDomain).toList());
}

public Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types) {
    return Panache.withSession(() ->
        EventLogEntity.<EventLogEntity>find("eventType in ?1 order by seq asc", types).list()
    ).map(list -> list.stream().map(this::toDomain).toList());
}

public Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    return Panache.withSession(() ->
        EventLogEntity.<EventLogEntity>find(
            "caseId = ?1 and eventType in ?2 order by seq asc", caseId, types).list()
    ).map(list -> list.stream().map(this::toDomain).toList());
}

public Uni<List<EventLog>> findByCaseAndWorkerAndType(UUID caseId, String workerId, CaseHubEventType type) {
    return Panache.withSession(() ->
        EventLogEntity.<EventLogEntity>find(
            "caseId = ?1 and workerId = ?2 and eventType = ?3", caseId, workerId, type).list()
    ).map(list -> list.stream().map(this::toDomain).toList());
}
```

### Test application.properties for casehub-persistence-hibernate
```properties
%test.quarkus.hibernate-orm.mapping.format.global=ignore
%test.quarkus.flyway.clean-at-start=true
quarkus.index-dependency.engine.group-id=io.casehub
quarkus.index-dependency.engine.artifact-id=engine
quarkus.index-dependency.api.group-id=io.casehub
quarkus.index-dependency.api.artifact-id=api
```

---

## casehub-persistence-memory module

### pom.xml location
`casehub-persistence-memory/pom.xml`
Dependencies: `engine`, `casehub-persistence-hibernate` (to get SPI interfaces via engine), `quarkus-arc`
Test: `quarkus-junit5`, `assertj-core`

Wait — memory module only needs `engine` (which has the SPI interfaces). It does NOT need `casehub-persistence-hibernate`.

### InMemoryCaseMetaModelRepository.java
```java
@Alternative @ApplicationScoped
public class InMemoryCaseMetaModelRepository implements CaseMetaModelRepository {
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ConcurrentHashMap<String, CaseMetaModel> store = new ConcurrentHashMap<>();

    @Override
    public Uni<CaseMetaModel> findByKey(String namespace, String name, String version) {
        return Uni.createFrom().item(store.get(key(namespace, name, version)));
    }

    @Override
    public Uni<CaseMetaModel> save(CaseMetaModel metaModel) {
        metaModel.id = idSeq.incrementAndGet();
        if (metaModel.getCreatedAt() == null) metaModel.setCreatedAt(Instant.now());
        store.put(key(metaModel.getNamespace(), metaModel.getName(), metaModel.getVersion()), metaModel);
        return Uni.createFrom().item(metaModel);
    }

    private String key(String ns, String name, String ver) { return ns + ":" + name + ":" + ver; }
}
```

### InMemoryCaseInstanceRepository.java
```java
@Alternative @ApplicationScoped
public class InMemoryCaseInstanceRepository implements CaseInstanceRepository {
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ConcurrentHashMap<UUID, CaseInstance> store = new ConcurrentHashMap<>();

    @Override
    public Uni<CaseInstance> save(CaseInstance instance) {
        instance.id = idSeq.incrementAndGet();
        store.put(instance.getUuid(), instance);
        return Uni.createFrom().item(instance);
    }

    @Override
    public Uni<CaseInstance> update(CaseInstance instance) {
        store.put(instance.getUuid(), instance);
        return Uni.createFrom().item(instance);
    }

    @Override
    public Uni<CaseInstance> findByUuid(UUID uuid) {
        return Uni.createFrom().item(store.get(uuid));
    }

    @Override
    public Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog) {
        store.put(instance.getUuid(), instance);
        // EventLog append handled separately — caller also calls eventLogRepository.append() if needed
        // For atomic in-memory: just do both here but eventLog needs an id from InMemoryEventLogRepository
        // Simpler: this method is called from handlers that ALSO call eventLogRepository directly.
        // In-memory: just update the instance. The caller ensures eventLog is also appended.
        // See note below.
        return Uni.createFrom().voidItem();
    }
}
```

**NOTE on `updateStateAndAppendEvent` in memory impl:** The JPA version handles this atomically in one DB transaction. The memory version can do both synchronously — but `InMemoryCaseInstanceRepository` doesn't have access to `InMemoryEventLogRepository`. 

Better approach: the memory impl does the case update AND appends the event via an `@Inject InMemoryEventLogRepository`. But that creates circular concerns.

**Simpler final design:** Change the method signature to not include event log:
- Keep handlers that need atomic updates using `update(instance)` + `append(eventLog)` separately (two calls)
- The JPA implementation wraps both in one `Panache.withTransaction()`
- The memory implementation: two separate calls, both synchronous, effectively atomic in single-threaded tests

So remove `updateStateAndAppendEvent` from the SPI. Instead:
- `CaseStatusChangedHandler` and `WorkerRetriesExhaustedEventHandler` call:
  ```java
  caseInstanceRepository.update(caseInstance)
      .chain(() -> eventLogRepository.append(eventLog))
  ```
- JPA implementations: each uses `Panache.withTransaction()`. The outer one (update) commits first, then append in second transaction. **Not atomic.** But consistent enough — EventLog is append-only, and if append fails, the case state is still updated correctly.

Actually, for the JPA case, the REAL transaction risk is: what if `update` commits but `append` fails? The case state is updated but the event is missing. This is actually acceptable because:
1. The case state is the authoritative truth
2. EventLog is observability/audit — a missing event is bad but recoverable
3. The original code DID do this atomically, so we're degrading slightly

For production quality, keep `updateStateAndAppendEvent` in the SPI. The memory impl does both:
```java
@Inject InMemoryEventLogRepository eventLogRepo;  // package-private injection

@Override
public Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog) {
    store.put(instance.getUuid(), instance);
    eventLog.id = eventLogRepo.nextId();  // needs a package-private nextId()
    eventLog.setSeq(eventLogRepo.nextSeq());
    eventLogRepo.store(eventLog);
    return Uni.createFrom().voidItem();
}
```

This is getting complex. **Final decision: keep `updateStateAndAppendEvent` in SPI, implement it properly in both.**

For in-memory, the simplest approach: `InMemoryCaseInstanceRepository` and `InMemoryEventLogRepository` share a package-private storage coordinator, OR `InMemoryCaseInstanceRepository` has an optional `@Inject` of the event log repository.

Actually: **inject `EventLogRepository` into `InMemoryCaseInstanceRepository`**:
```java
@Inject EventLogRepository eventLogRepository;  // will be InMemoryEventLogRepository in tests

@Override
public Uni<Void> updateStateAndAppendEvent(CaseInstance instance, EventLog eventLog) {
    store.put(instance.getUuid(), instance);
    return eventLogRepository.append(eventLog);
}
```

This works cleanly — CDI injects the active `EventLogRepository` (which is `InMemoryEventLogRepository` in tests). **Use this approach.**

### InMemoryEventLogRepository.java
```java
@Alternative @ApplicationScoped
public class InMemoryEventLogRepository implements EventLogRepository {
    private final AtomicLong idSeq = new AtomicLong(0);
    private final AtomicLong seqCounter = new AtomicLong(0);
    private final ConcurrentHashMap<Long, EventLog> store = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> append(EventLog eventLog) {
        eventLog.id = idSeq.incrementAndGet();
        eventLog.setSeq(seqCounter.incrementAndGet());
        store.put(eventLog.id, eventLog);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Long> appendAndReturnId(EventLog eventLog) {
        eventLog.id = idSeq.incrementAndGet();
        eventLog.setSeq(seqCounter.incrementAndGet());
        store.put(eventLog.id, eventLog);
        return Uni.createFrom().item(eventLog.id);
    }

    @Override
    public Uni<EventLog> findById(Long id) {
        return Uni.createFrom().item(store.get(id));
    }

    @Override
    public Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId) {
        return Uni.createFrom().item(store.values().stream()
            .filter(e -> caseId.equals(e.getCaseId()) && workerId.equals(e.getWorkerId()))
            .filter(e -> e.getEventType() == CaseHubEventType.WORKER_SCHEDULED
                || e.getEventType() == CaseHubEventType.WORKER_EXECUTION_STARTED
                || e.getEventType() == CaseHubEventType.WORKER_EXECUTION_COMPLETED)
            .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types) {
        return Uni.createFrom().item(store.values().stream()
            .filter(e -> types.contains(e.getEventType()))
            .sorted(java.util.Comparator.comparingLong(EventLog::getSeq))
            .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
        return Uni.createFrom().item(store.values().stream()
            .filter(e -> caseId.equals(e.getCaseId()) && types.contains(e.getEventType()))
            .sorted(java.util.Comparator.comparingLong(EventLog::getSeq))
            .collect(java.util.stream.Collectors.toList()));
    }

    @Override
    public Uni<List<EventLog>> findByCaseAndWorkerAndType(UUID caseId, String workerId, CaseHubEventType type) {
        return Uni.createFrom().item(store.values().stream()
            .filter(e -> caseId.equals(e.getCaseId())
                && workerId.equals(e.getWorkerId())
                && type == e.getEventType())
            .collect(java.util.stream.Collectors.toList()));
    }

    /** For test reset between test methods. */
    public void clear() { store.clear(); idSeq.set(0); seqCounter.set(0); }
}
```

---

## PR 3: Domain object changes

### CaseInstance.java changes
- Remove `extends PanacheEntity`
- Add `public Long id;`
- Remove `@Entity`, `@Table`, `@Column`, `@ManyToOne`, `@JoinColumn`, `@OneToMany`, all JPA imports
- Keep all business logic, `caseContext`, `uuid`, `state`, `caseMetaModel`, `parentPlanItemId`
- `caseMetaModel` stays as `CaseMetaModel` reference (no JPA annotation)

### EventLog.java changes
- Remove `extends PanacheEntity`
- Add `public Long id;`
- Add `public void setSeq(Long seq) { this.seq = seq; }` (was managed by JPA `@Generated`)
- Add `public Long getSeq() { return seq; }` if not already present
- Remove `persistScheduledEvent()` method — deleted entirely
- Remove `findSchedulingEvents()` static method — deleted entirely
- Remove all JPA/Hibernate annotations and imports

### CaseMetaModel.java changes
- Remove `extends PanacheEntity`
- Add `public Long id;`
- Remove `@Entity`, `@Table`, `@Column`, `@OneToMany`, `@PrePersist`, `@UniqueConstraint`
- Remove `List<CaseInstance> caseInstance` field (JPA bidirectional, not needed)
- Keep all getters/setters
- Remove all JPA imports

---

## PR 3: Handler refactoring map

For each handler below, the injection pattern is:
```java
@Inject CaseMetaModelRepository caseMetaModelRepository;
@Inject CaseInstanceRepository caseInstanceRepository;
@Inject EventLogRepository eventLogRepository;
```

### CaseDefinitionRegistry.java
Remove `@Inject Mutiny.SessionFactory` (no longer needed here if it was used).
```java
// Before:
CaseMetaModel.find("namespace = ?1 and name = ?2 and version = ?3", ns, name, ver).firstResult()
definition.persistAndFlush()

// After:
caseMetaModelRepository.findByKey(namespace, name, version)
caseMetaModelRepository.save(definition)
```

### CaseHubReactor.java
```java
// Before: instance.persist()
// After: caseInstanceRepository.save(instance)
// Remove: @Inject Mutiny.SessionFactory sessionFactory (if present)
```

### CaseStartedEventHandler.java
```java
// Before: eventLog.persist()
// After: eventLogRepository.append(eventLog)
// Remove: Panache.withTransaction() wrapper — repository handles it
```

### CaseStatusChangedHandler.java
```java
// Before:
Panache.withTransaction(() ->
    Panache.getSession().chain(session -> session.merge(caseInstance))
        .chain(merged -> eventLog.persist()))

// After:
caseInstanceRepository.updateStateAndAppendEvent(caseInstance, eventLog)
```

### WorkerRetriesExhaustedEventHandler.java
```java
// Before:
Panache.withTransaction(() ->
    Panache.getSession().chain(session -> session.merge(caseInstance))
        .chain(merged -> eventLog.persist()))

// After:
caseInstanceRepository.updateStateAndAppendEvent(caseInstance, eventLog)
```

### WorkflowExecutionCompletedHandler.java
```java
// Before: eventLog.persist() inside Panache.withTransaction()
// After:
return eventLogRepository.append(eventLog)
    .invoke(() -> eventBus.publish(CONTEXT_CHANGED, ...))
    .replaceWithVoid();
// Note: contextDiffStrategy injection stays (from EventLog enrichment PR)
```

### MilestoneReachedEventHandler.java, GoalReachedEventHandler.java, SignalReceivedEventHandler.java
```java
// All: replace eventLog.persist() with eventLogRepository.append(eventLog)
// GoalReachedEventHandler also: EventLog.find(...GOAL_REACHED) → eventLogRepository.findByCaseAndTypes(...)
```

### WorkerScheduleEventHandler.java
```java
// Before:
EventLog.findSchedulingEvents(instance.getUuid(), worker.getName())
eventLog.persistScheduledEvent()  // returns id

// After:
eventLogRepository.findSchedulingEvents(instance.getUuid(), worker.getName())
eventLogRepository.appendAndReturnId(eventLog)
```

### WorkerExecutionManager.java
```java
// Before: EventLog.findById(eventLogId)
// After: eventLogRepository.findById(Long.parseLong(eventLogId))
// Note: eventLogId from Quartz is stored as String, needs Long.parseLong()
```

### WorkerExecutionJobListener.java
```java
// Before: PanacheEntityBase.persist(eventLog)
// After: eventLogRepository.append(eventLog) — but this is a reactive Uni,
//        and JobListener runs synchronously. Need to block:
//        eventLogRepository.append(eventLog).await().indefinitely()
//        OR use ReactiveUtils.runOnSafeVertxContext(vertx, () -> eventLogRepository.append(eventLog))
//            .subscribe().with(...)

// Before: EventLog.find(caseId, workerId, WORKER_EXECUTION_FAILED).list()
// After: eventLogRepository.findByCaseAndWorkerAndType(caseId, workerId, WORKER_EXECUTION_FAILED)
//        Note: result list then filtered by inputDataHash in Java (same as before)
```

### WorkerExecutionTask.java
```java
// Before: Panache.withSession(() -> EventLog.findById(eventLogId))
// After: eventLogRepository.findById(Long.parseLong(eventLogId))
// Wrapped in ReactiveUtils.runOnSafeVertxContext() as before (Quartz thread → Vert.x)
```

### WorkerExecutionRecoveryService.java
```java
// Remove: @Inject Mutiny.SessionFactory sessionFactory

// loadOrRestoreCaseInstance — before: sessionFactory.withSession(HQL join fetch query)
// After:
caseInstanceRepository.findByUuid(caseId)
    .onItem().ifNull().failWith(() -> new IllegalStateException("CaseInstance not found: " + caseId))
    .chain(instance ->
        rebuildStateContext(caseId).map(ctx -> {
            instance.setCaseContext(ctx);
            caseInstanceCache.put(instance);
            return instance;
        }))

// recoverPendingScheduledWorkers — before: sessionFactory.withSession(query all events by type)
// After:
eventLogRepository.findByTypes(RELEVANT_RECOVERY_EVENTS)
    .chain(this::reschedulePendingEvents)

// rebuildStateContext — before: sessionFactory.withSession(query by case + types)
// After:
eventLogRepository.findByCaseAndTypes(caseId,
    EnumSet.of(CaseHubEventType.CASE_STARTED,
               CaseHubEventType.WORKER_EXECUTION_COMPLETED,
               CaseHubEventType.SIGNAL_RECEIVED))
```

---

## PR 3: Engine pom.xml changes

### Remove these dependencies:
```xml
<dependency><artifactId>quarkus-hibernate-reactive-panache</artifactId></dependency>
<dependency><artifactId>quarkus-reactive-pg-client</artifactId></dependency>
<dependency><artifactId>quarkus-jdbc-postgresql</artifactId></dependency>
<dependency><artifactId>quarkus-flyway</artifactId></dependency>
<dependency><artifactId>testcontainers-postgresql</artifactId><scope>test</scope></dependency>
```

### Add test dependency:
```xml
<dependency>
    <groupId>io.casehub</groupId>
    <artifactId>casehub-persistence-memory</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```

### Keep:
- `quarkus-quartz` (still needed, uses RAM store in tests)
- All other existing deps

---

## PR 3: Engine test application.properties (complete replacement)

```properties
# Engine tests use in-memory persistence — no Docker, no PostgreSQL needed

# Quartz: use in-memory store (no DB required)
quarkus.quartz.store-type=ram

# Select in-memory repository implementations
quarkus.arc.selected-alternatives=\
  io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,\
  io.casehub.persistence.memory.InMemoryCaseInstanceRepository,\
  io.casehub.persistence.memory.InMemoryEventLogRepository

# Index the memory module so CDI discovers its @Alternative beans
quarkus.index-dependency.memory.group-id=io.casehub
quarkus.index-dependency.memory.artifact-id=casehub-persistence-memory
```

---

## Test strategy per PR

### PR 1 tests (casehub-persistence-hibernate — @QuarkusTest with TestContainers)

**JpaCaseMetaModelRepositoryTest:**
- `save_populatesIdAndCreatedAt`
- `findByKey_returnsNullForUnknown`
- `findByKey_returnsRegisteredMetaModel`
- `save_thenFindByKey_roundTrip`

**JpaCaseInstanceRepositoryTest:**
- `save_populatesId`
- `findByUuid_returnsNullForUnknown`
- `findByUuid_returnsSavedInstanceWithMetaModel`
- `update_changesState`
- `updateStateAndAppendEvent_atomicallyUpdatesAndPersistsEvent`

**JpaEventLogRepositoryTest:**
- `append_populatesIdAndSeq`
- `append_seqIsMonotonicallyIncreasing`
- `appendAndReturnId_returnsId`
- `findById_returnsNullForUnknown`
- `findById_returnsAppendedEvent`
- `findSchedulingEvents_filtersCorrectly`
- `findByTypes_returnsOrderedBySeq`
- `findByCaseAndTypes_filtersByCaseId`
- `findByCaseAndWorkerAndType_filtersCorrectly`

### PR 2 tests (casehub-persistence-memory — pure unit tests, no Quarkus)

Same test names as JPA tests, but instantiated directly:
```java
InMemoryCaseMetaModelRepository repo = new InMemoryCaseMetaModelRepository();
// test using .subscribe().asCompletionStage().toCompletableFuture().join()
```

### PR 3 tests (engine decoupling — existing engine tests must all pass)

No new tests needed — the existing test suite (331 engine tests + blackboard tests) is the E2E validation. If all pass without Docker, the decoupling is complete.

Add one new verification test: `PersistenceDecouplingTest` that asserts no Panache/Hibernate classes are imported from the engine's main source (compile-only check via class loading).

---

## Parent pom.xml changes (PR 1 and PR 2)

Add modules in this order:
```xml
<module>casehub-blackboard</module>
<module>casehub-resilience</module>
<module>casehub-persistence-hibernate</module>  <!-- PR 1 -->
<module>casehub-persistence-memory</module>     <!-- PR 2 -->
```

---

## GitHub issues to create

- PR 1: Issue for "Add repository SPI interfaces + casehub-persistence-hibernate module"
- PR 2: Issue for "Add casehub-persistence-memory module — in-memory test backend"
- PR 3: Issue for "Engine persistence decoupling — strip JPA, use repository SPI"
All under epic #30 (casehubio/engine).

---

## Known gotchas

1. `eventLogId` in Quartz job data is stored as String — use `Long.parseLong(eventLogId)` when calling `eventLogRepository.findById()`
2. `EventLog.seq` was `@Generated(INSERT)` — in POJO, add `setSeq(Long)` setter
3. `WorkerExecutionJobListener` runs on Quartz thread — reactive Uni calls need `ReactiveUtils.runOnSafeVertxContext()` wrapper
4. `findByTypes(Collection)` in JPA: Panache/HQL handles collections in `in ?1` — no need to unpack
5. `CaseMetaModel.id` — currently `Long id` inherited from PanacheEntity; after decoupling, add `public Long id` directly
6. `@Alternative` without `@Priority` — CDI 4.0/Quarkus gotcha: `@Priority` globally activates; use only `@Alternative`, activate via `quarkus.arc.selected-alternatives`
7. Quartz JDBC tables migration (V1.0.0) moves to casehub-persistence-hibernate but only runs when using JDBC store; with RAM store, Flyway doesn't run at all (no datasource)
8. `WorkerExecutionRecoveryService` used `ReactiveUtils.runOnSafeVertxContext()` — keep this wrapper pattern when calling repositories from startup events
