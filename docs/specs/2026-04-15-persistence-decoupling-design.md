# Persistence Decoupling — Repository SPI + casehub-persistence-memory + casehub-persistence-hibernate
**Date:** 2026-04-15
**Status:** Approved — ready for implementation

---

## Problem

The engine is tightly coupled to Hibernate Reactive and PostgreSQL. `CaseInstance`, `EventLog`,
and `CaseMetaModel` all extend `PanacheEntity`, and every event handler calls
`Panache.withTransaction()` directly. There is no storage abstraction.

Consequences:
- Every `@QuarkusTest` requires a real PostgreSQL container via TestContainers — slow, Docker-dependent
- There is no lightweight deployment option without a database
- Adding a second storage backend requires modifying the engine core

---

## Goal

Make persistence fully pluggable via three repository SPI interfaces. Provide two implementations:

| Module | Storage | Use case |
|---|---|---|
| `casehub-persistence-memory` | `ConcurrentHashMap` + `AtomicLong` | Fast tests, no Docker |
| `casehub-persistence-hibernate` | Separate JPA entities + Hibernate Reactive | Production |

The engine core has **no** Hibernate, Panache, or PostgreSQL dependency after this change.

---

## Design Rationale: Separate Entity Classes

The `orm.xml` approach (externalising JPA annotations on domain objects) was considered and
rejected on the advice of Francisco Javier Tirado Sarti (quarkus-flow co-creator), who has
implemented this pattern across multiple projects.

**The core issue:** Even with `orm.xml`, domain objects used as JPA entities are subject to
the Hibernate session lifecycle. Accessing lazy-loaded relationships after the session closes
throws `LazyInitializationException`. In casehub, `CaseInstance` objects live in
`CaseInstanceCache` indefinitely — well outside any transaction boundary — making this a
real production risk, not a theoretical one.

**The solution (following quarkus-flow conventions):** Define separate JPA entity classes
(`CaseInstanceEntity`, `EventLogEntity`, `CaseMetaModelEntity`) in `casehub-persistence-hibernate`.
These classes own the JPA annotations and `PanacheEntity` inheritance. The repository
implementations convert between entity objects and domain POJOs via private `from()` methods.
Domain objects in `engine` are completely clean — no JPA, no Panache, no session concerns.

This is exactly the pattern used by:
- quarkus-flow: `ProcessInstanceEntity` / `TaskInfoEntity` + `JpaInstanceOperations` with `from()` converters
- serverlessworkflow sdk-java: `PersistenceInstanceInfo` (domain) separate from JPA entities

---

## Architecture

```
api/                               ← no deps (unchanged)
engine/                            ← depends on api + Quartz; NO Hibernate/Panache
  spi/
    CaseMetaModelRepository        ← NEW: interface
    CaseInstanceRepository         ← NEW: interface
    EventLogRepository             ← NEW: interface
  internal/model/CaseInstance      ← POJO (remove PanacheEntity + all JPA annotations)
  internal/model/CaseMetaModel     ← POJO (remove PanacheEntity + all JPA annotations)
  internal/history/EventLog        ← POJO (remove PanacheEntity + all JPA annotations)
  internal/engine/handlers/*       ← refactored to inject repositories

casehub-persistence-memory/        ← NEW module; depends on engine; no DB deps
  package: io.casehub.persistence.memory
  InMemoryCaseMetaModelRepository  ← @Alternative @ApplicationScoped
  InMemoryCaseInstanceRepository   ← @Alternative @ApplicationScoped
  InMemoryEventLogRepository       ← @Alternative @ApplicationScoped

casehub-persistence-hibernate/     ← NEW module; depends on engine; Hibernate Reactive + Flyway
  package: io.casehub.persistence.jpa
  JpaCaseMetaModelRepository       ← @ApplicationScoped (default)
  JpaCaseInstanceRepository        ← @ApplicationScoped (default)
  JpaEventLogRepository            ← @ApplicationScoped (default)
  CaseMetaModelEntity              ← @Entity, extends PanacheEntity
  CaseInstanceEntity               ← @Entity, @DynamicUpdate, extends PanacheEntity
  EventLogEntity                   ← @Entity, extends PanacheEntity
  db/migration/                    ← Flyway migrations (moved from engine)

casehub-blackboard/                ← unchanged
casehub-resilience/                ← unchanged
```

---

## Repository SPI Interfaces

All interfaces return `Uni<T>` for consistency with the engine's reactive model.
Placed in `engine/src/main/java/io/casehub/engine/spi/`.

### CaseMetaModelRepository

```java
package io.casehub.engine.spi;

public interface CaseMetaModelRepository {

    /** Find by the unique (namespace, name, version) key. Returns null if not registered. */
    Uni<CaseMetaModel> findByKey(String namespace, String name, String version);

    /** Persist a new case meta model. Returns the saved instance with id populated. */
    Uni<CaseMetaModel> save(CaseMetaModel metaModel);
}
```

### CaseInstanceRepository

```java
package io.casehub.engine.spi;

public interface CaseInstanceRepository {

    /** Persist a new case instance. Returns the saved instance with id populated. */
    Uni<CaseInstance> save(CaseInstance instance);

    /** Merge state changes back to storage (status transitions). */
    Uni<CaseInstance> update(CaseInstance instance);

    /**
     * Load a case instance by UUID with its CaseMetaModel eagerly joined.
     * Returns null if not found.
     */
    Uni<CaseInstance> findByUuid(UUID uuid);
}
```

### EventLogRepository

```java
package io.casehub.engine.spi;

public interface EventLogRepository {

    /** Append an event to the log. */
    Uni<Void> append(EventLog eventLog);

    /**
     * Append an event and flush, returning the generated id.
     * Used by WorkerScheduleEventHandler to get the id before scheduling a Quartz job.
     */
    Uni<Long> appendAndReturnId(EventLog eventLog);

    /** Load an event by id. Returns null if not found. */
    Uni<EventLog> findById(Long id);

    /**
     * Find WORKER_SCHEDULED, WORKER_EXECUTION_STARTED, and WORKER_EXECUTION_COMPLETED events
     * for a specific case+worker. Used by WorkerScheduleEventHandler for idempotency checking.
     */
    Uni<List<EventLog>> findSchedulingEvents(UUID caseId, String workerId);

    /**
     * Find all events matching any of the given types, ordered by seq ascending.
     * Used by WorkerExecutionRecoveryService to find pending scheduled workers on startup.
     */
    Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types);

    /**
     * Find events for a specific case matching any of the given types, ordered by seq ascending.
     * Used by WorkerExecutionRecoveryService to rebuild CaseContext state.
     */
    Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types);

    /**
     * Find events for a specific case+worker of a specific type.
     * Used by WorkerExecutionJobListener to count failed attempts for retry logic.
     */
    Uni<List<EventLog>> findByCaseAndWorkerAndType(
            UUID caseId, String workerId, CaseHubEventType type);
}
```

---

## Domain Object Changes

### CaseInstance

- Remove `extends PanacheEntity`
- Add `public Long id` field (was inherited)
- Remove: `@Entity`, `@Table`, `@Column`, `@OneToMany`, `@ManyToOne`, `@JoinColumn`, all JPA imports
- Keep: `uuid`, `state`, `caseMetaModel`, `parentPlanItemId`, `caseContext`, all business logic
- `caseMetaModel` remains as a plain Java reference — set by the repository after loading

### EventLog

- Remove `extends PanacheEntity`
- Add `public Long id` field
- Remove: `@Entity`, `@Table`, `@Column`, `@Enumerated`, `@Generated`, `@JdbcTypeCode`, all JPA imports
- Remove: `persistScheduledEvent()` — moves to `EventLogRepository.appendAndReturnId()`
- Remove: `findSchedulingEvents()` static method — moves to `EventLogRepository`
- Keep: `seq`, `caseId`, `eventType`, `streamType`, `workerId`, `timestamp`, `payload`, `metadata` fields
- `seq` becomes a regular `Long` field — set by the repository implementation

### CaseMetaModel

- Remove `extends PanacheEntity`
- Add `public Long id` field
- Remove: `@Entity`, `@Table`, `@Column`, `@OneToMany`, `@PrePersist`, `@UniqueConstraint`, all JPA imports
- Remove: `List<CaseInstance> caseInstance` back-reference — JPA bidirectional concern only
- Keep: `name`, `namespace`, `version`, `title`, `dsl`, `definition`, `createdAt`, all getters/setters
- `createdAt` is set by the repository on save, not by a JPA lifecycle callback

---

## Engine Handler Refactoring

Each handler that currently calls Panache injects the relevant repository instead.
Transaction management moves into the JPA repository implementations.

| Handler / Service | Current Panache call | Becomes |
|---|---|---|
| `CaseHubReactor` | `instance.persist()` | `caseInstanceRepository.save(instance)` |
| `CaseStatusChangedHandler` | `session.merge(caseInstance)` + `eventLog.persist()` | `caseInstanceRepository.update(...)` + `eventLogRepository.append(...)` |
| `WorkerRetriesExhaustedEventHandler` | `session.merge(caseInstance)` + `eventLog.persist()` | `caseInstanceRepository.update(...)` + `eventLogRepository.append(...)` |
| `CaseStartedEventHandler` | `eventLog.persist()` | `eventLogRepository.append(...)` |
| `WorkflowExecutionCompletedHandler` | `eventLog.persist()` | `eventLogRepository.append(...)` |
| `GoalReachedEventHandler` | `eventLog.persist()` + `EventLog.find(...)` | `eventLogRepository.append(...)` + `eventLogRepository.findByCaseAndTypes(...)` |
| `MilestoneReachedEventHandler` | `eventLog.persist()` | `eventLogRepository.append(...)` |
| `SignalReceivedEventHandler` | `eventLog.persist()` | `eventLogRepository.append(...)` |
| `WorkerScheduleEventHandler` | `EventLog.findSchedulingEvents(...)` + `eventLog.persistScheduledEvent()` | `eventLogRepository.findSchedulingEvents(...)` + `eventLogRepository.appendAndReturnId(...)` |
| `WorkerExecutionManager` | `EventLog.findById(eventLogId)` | `eventLogRepository.findById(eventLogId)` |
| `WorkerExecutionJobListener` | `PanacheEntityBase.persist(eventLog)` + `EventLog.find(...)` | `eventLogRepository.append(...)` + `eventLogRepository.findByCaseAndWorkerAndType(...)` |
| `WorkerExecutionTask` | `EventLog.findById(eventLogId)` | `eventLogRepository.findById(eventLogId)` |
| `WorkerExecutionRecoveryService` | `sessionFactory.withSession(...)` queries | `caseInstanceRepository.findByUuid(...)` + `eventLogRepository.findByTypes(...)` + `eventLogRepository.findByCaseAndTypes(...)` |
| `CaseDefinitionRegistry` | `CaseMetaModel.find(...).firstResult()` + `definition.persistAndFlush()` | `caseMetaModelRepository.findByKey(...)` + `caseMetaModelRepository.save(...)` |

---

## casehub-persistence-memory

### Structure

```
casehub-persistence-memory/
  pom.xml                                          ← depends on engine + quarkus-arc
  src/main/java/io/casehub/persistence/memory/
    InMemoryCaseMetaModelRepository.java           ← @Alternative @ApplicationScoped
    InMemoryCaseInstanceRepository.java            ← @Alternative @ApplicationScoped
    InMemoryEventLogRepository.java                ← @Alternative @ApplicationScoped
```

No database, no Hibernate, no Flyway, no Quartz JDBC store.

### Activation

Projects using in-memory persistence add to `application.properties`:

```properties
quarkus.arc.selected-alternatives=\
  io.casehub.persistence.memory.InMemoryCaseMetaModelRepository,\
  io.casehub.persistence.memory.InMemoryCaseInstanceRepository,\
  io.casehub.persistence.memory.InMemoryEventLogRepository
quarkus.quartz.store-type=ram
```

No `casehub-persistence-hibernate` dependency → no datasource, no Flyway, no PostgreSQL.

### In-Memory Design

- IDs generated by `AtomicLong` counters (one per repository)
- `EventLog.seq` set from a shared `AtomicLong` — monotonically increasing, same semantics
  as PostgreSQL `GENERATED ALWAYS AS IDENTITY`
- `CaseMetaModel.createdAt` set on save to `Instant.now()`
- All operations return `Uni.createFrom().item(result)` — synchronous, no I/O
- Thread-safe: `ConcurrentHashMap` for stores
- No transaction management needed

---

## casehub-persistence-hibernate

Follows the conventions established by quarkus-flow's JPA persistence module
(`io.quarkiverse.flow.persistence.jpa`):
- Separate `*Entity` classes own JPA annotations and extend `PanacheEntity`
- Repository implementations hold private `from(entity)` converter methods
- Writes create entity objects from domain data; reads convert entities back to domain POJOs
- `@DynamicUpdate` on entities where partial updates occur (status changes)

### Structure

```
casehub-persistence-hibernate/
  pom.xml                                          ← depends on engine + Hibernate Reactive + Flyway
  src/main/java/io/casehub/persistence/jpa/
    CaseMetaModelEntity.java                       ← @Entity, extends PanacheEntity
    CaseInstanceEntity.java                        ← @Entity, @DynamicUpdate, extends PanacheEntity
    EventLogEntity.java                            ← @Entity, extends PanacheEntity
    JpaCaseMetaModelRepository.java                ← @ApplicationScoped (default)
    JpaCaseInstanceRepository.java                 ← @ApplicationScoped (default)
    JpaEventLogRepository.java                     ← @ApplicationScoped (default)
  src/main/resources/db/migration/                 ← Flyway migrations (moved from engine)
```

### Entity classes

Each entity class mirrors the database schema and owns all JPA/Panache concerns.
The domain POJO fields that make no sense in the DB (e.g. `caseContext` which is
transient/in-memory) are simply absent from the entity.

**`CaseInstanceEntity`** — maps to `case_instance` table:
```java
@Entity
@DynamicUpdate  // only changed columns sent on UPDATE (e.g. state transitions)
@Table(name = "case_instance")
class CaseInstanceEntity extends PanacheEntity {
    @Column(name = "uuid", nullable = false, unique = true, updatable = false)
    UUID uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    CaseStatus state;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "case_meta_model_id")
    CaseMetaModelEntity caseMetaModel;

    @Column(name = "parent_plan_item_id", nullable = true)
    Long parentPlanItemId;
}
```

**`EventLogEntity`** — maps to `event_log` table:
```java
@Entity
@Table(name = "event_log")
class EventLogEntity extends PanacheEntity {
    @Column(name = "seq", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    Long seq;

    @Column(name = "case_id", nullable = false, updatable = false)
    UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 255)
    CaseHubEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type", nullable = false, length = 255)
    EventStreamType streamType;

    @Column(name = "worker_id", nullable = true, length = 255)
    String workerId;

    @Column(name = "timestamp", nullable = false)
    Instant timestamp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    JsonNode payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    JsonNode metadata;
}
```

**`CaseMetaModelEntity`** — maps to `case_meta_model` table:
```java
@Entity
@Table(name = "case_meta_model",
    uniqueConstraints = @UniqueConstraint(columnNames = {"namespace", "name", "version"}))
class CaseMetaModelEntity extends PanacheEntity {
    @Column(nullable = false, length = 255)  String name;
    @Column(length = 255)                    String namespace;
    @Column(nullable = false, length = 50)   String version;
    @Column(length = 500)                    String title;
    @Column(length = 50)                     String dsl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    JsonNode definition;

    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
```

### Repository implementations

Each repository uses `Panache.withTransaction()` / `Panache.withSession()` for reactive
transaction management, converts entity ↔ domain POJO via private `from()` methods.

```java
@ApplicationScoped
class JpaCaseInstanceRepository implements CaseInstanceRepository {

    @Override
    public Uni<CaseInstance> save(CaseInstance instance) {
        CaseInstanceEntity entity = toEntity(instance);
        return Panache.withTransaction(() -> entity.persist())
                      .map(v -> fromEntity(entity, instance));
    }

    @Override
    public Uni<CaseInstance> update(CaseInstance instance) {
        return Panache.withTransaction(() ->
            CaseInstanceEntity.<CaseInstanceEntity>findById(instance.id)
                .chain(entity -> {
                    entity.state = instance.getState();
                    return Panache.getSession().chain(s -> s.merge(entity));
                })
        ).map(e -> instance);
    }

    @Override
    public Uni<CaseInstance> findByUuid(UUID uuid) {
        return Panache.withSession(() ->
            CaseInstanceEntity.<CaseInstanceEntity>find(
                "uuid = ?1 join fetch caseMetaModel", uuid).firstResult()
        ).map(entity -> entity == null ? null : fromEntity(entity, null));
    }

    private CaseInstance fromEntity(CaseInstanceEntity e, CaseInstance existing) {
        CaseInstance instance = existing != null ? existing : new CaseInstance();
        instance.id = e.id;
        instance.setUuid(e.uuid);
        instance.setState(e.state);
        instance.setParentPlanItemId(e.parentPlanItemId);
        if (e.caseMetaModel != null) {
            instance.setCaseMetaModel(fromEntity(e.caseMetaModel));
        }
        return instance;
    }

    private CaseInstanceEntity toEntity(CaseInstance instance) {
        CaseInstanceEntity e = new CaseInstanceEntity();
        e.uuid = instance.getUuid();
        e.state = instance.getState();
        e.parentPlanItemId = instance.getParentPlanItemId();
        if (instance.getCaseMetaModel() != null) {
            CaseMetaModelEntity meta = new CaseMetaModelEntity();
            meta.id = instance.getCaseMetaModel().getId();
            e.caseMetaModel = meta;
        }
        return e;
    }
    // fromEntity(CaseMetaModelEntity) defined similarly
}
```

---

## Quartz Store

Quartz is in the `engine` module. The store type is configurable:

- `casehub-persistence-hibernate` ships with: `quarkus.quartz.store-type=jdbc` (persisted)
- `casehub-persistence-memory` users set: `quarkus.quartz.store-type=ram` (in-memory)

---

## Flyway

Flyway migrations currently live in `engine/src/main/resources/db/migration/`. They move to
`casehub-persistence-hibernate/src/main/resources/db/migration/`. The engine module has no
migration files after this change.

---

## Testing

### Engine module tests

After this change, engine tests add `casehub-persistence-memory` as a test-scoped dependency
and select in-memory alternatives via `application.properties`. No TestContainers, no Docker.

### casehub-persistence-hibernate module tests

Integration tests exercise the JPA entity ↔ domain conversion and full SQL queries against
a real PostgreSQL container (TestContainers). These are the only tests that need Docker.

### casehub-persistence-memory module tests

Pure unit tests: ID generation, `seq` monotonicity, query filtering by type/status/workerId.
No Quarkus required.

---

## Implementation Scope

**Files modified in `engine`:**
- `CaseInstance.java` — remove PanacheEntity + JPA annotations, add `Long id`
- `EventLog.java` — remove PanacheEntity + JPA annotations, add `Long id`, remove static methods
- `CaseMetaModel.java` — remove PanacheEntity + JPA annotations, add `Long id`
- All 8+ handler/service classes — replace Panache calls with repository injection
- `engine/pom.xml` — remove Hibernate Reactive, PostgreSQL, Flyway dependencies
- `engine/src/test` — switch to `casehub-persistence-memory`; remove TestContainers

**New files in `engine`:**
- `spi/CaseMetaModelRepository.java`
- `spi/CaseInstanceRepository.java`
- `spi/EventLogRepository.java`

**New module: `casehub-persistence-memory`**
- `pom.xml`
- `InMemoryCaseMetaModelRepository.java`
- `InMemoryCaseInstanceRepository.java`
- `InMemoryEventLogRepository.java`
- Unit tests for each

**New module: `casehub-persistence-hibernate`**
- `pom.xml`
- `CaseMetaModelEntity.java`, `CaseInstanceEntity.java`, `EventLogEntity.java`
- `JpaCaseMetaModelRepository.java`, `JpaCaseInstanceRepository.java`, `JpaEventLogRepository.java`
- `db/migration/` (Flyway migrations moved from engine)
- Integration tests against PostgreSQL

**Parent `pom.xml`:** add two new modules.

---

## Out of Scope

- `CaseInstanceCache` — already in-memory, unchanged
- `CaseDefinitionRegistry` in-memory cache — unchanged
- Quartz job data — managed by Quartz independently
- `WorkerExecutionKeys` utility — unchanged
- Any changes to `api`, `casehub-blackboard`, or `casehub-resilience`
