# CLAUDE.md

**Name:** casehub-engine

## Project Type

**Type:** java

---

## Work Tracking

**Issue tracking:** enabled

All implementation work must be linked to a GitHub issue:
- Before starting implementation, create an epic + child issues (or confirm an existing issue)
- All commits reference an issue: `Refs #N` (work in progress) or `Closes #N` (completes the issue)
- When staged changes span multiple concerns, split into separate commits with separate issue references

**Automatic behaviors:**
- Phase 1 (Pre-Implementation): Create epic + child issues before coding begins
- Phase 2 (Task Intake): Detect cross-cutting concerns and suggest breaking into separate issues
- Phase 3 (Pre-Commit): Verify issue linkage; suggest commit splits when staged changes span multiple concerns

**Repository:** casehubio/engine

---

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design journal | workspace | JOURNAL.md (epic artifact) stays in workspace permanently |
| DESIGN.md  | project     | canonical design doc — `docs/DESIGN.md` in the project repo |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

## Document Locations

Key documents and where to find them. All paths are relative — no absolute paths.
Convention: `proj/` in workspace reaches the project repo; `wksp/` in the project repo reaches the workspace.

| Document | Path from project root | Path from workspace root |
|----------|----------------------|------------------------|
| DESIGN.md | `docs/DESIGN.md` | `proj/docs/DESIGN.md` |
| ADR index | `docs/adr/INDEX.md` | `proj/docs/adr/INDEX.md` |
| HANDOFF.md | `wksp/HANDOFF.md` | `HANDOFF.md` |
| Blog entries | `wksp/blog/` | `blog/` |
| Plans | `wksp/plans/` | `plans/` |
| Epic journal | `wksp/design/JOURNAL.md` | `design/JOURNAL.md` |
| Platform architecture | remote: `https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md` | — |
| Protocol index | remote: `https://raw.githubusercontent.com/casehubio/garden/main/docs/protocols/casehub/FOUNDATION-INDEX.md` | — |

---

## Platform Docs
- [Platform Index](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) — discovery index (start here)
- [Building Platform](https://raw.githubusercontent.com/casehubio/parent/main/docs/guides/building-platform.md) — platform contributor guide

## Repo Guide

This repo owns its own documentation, synced to parent via CI:
- `docs/guides/consumer-guide.md` — for app builders: modules, APIs, quick start
- `docs/guides/contributor-guide.md` — for platform builders: architecture, SPIs, internals

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

Read `docs/guides/consumer-guide.md` for app-level work. Only read `docs/guides/contributor-guide.md` when modifying this repo's internals or extension points.

## No Migration Tooling

This project has no installed instances to migrate. Do not add:

- Flyway or Liquibase dependencies
- SQL migration files in the generic `db/migration/` path. Exception: `casehub-engine-ledger` ships V2000/V2001 at `db/engine-ledger/migration/` (scoped per PP-20260525-607b33); consumers must explicitly add `classpath:db/engine-ledger/migration` to their datasource Flyway locations
- `quarkus.flyway.*` or `quarkus.liquibase.*` properties
- JDBC-only dependencies (`quarkus-jdbc-postgresql`, `quarkus-agroal`) unless required for a non-migration reason

Schema is managed by Hibernate directly:
```properties
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
```

If a schema change is needed, update the `@Entity` class. Hibernate recreates the schema on next startup.

## Persistence Architecture

Domain objects, SPI interfaces, and shared CDI infrastructure live in `casehub-engine-common` (no JPA):

- `casehub-engine-common/src/main/java/io/casehub/engine/internal/model/` — `CaseMetaModel`, `CaseInstance`, `SubCaseGroup`, `PlanItemRecord` (read model)
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/history/` — `EventLog`, `CaseHubEventType`, `EventStreamType`
- `casehub-engine-common/src/main/java/io/casehub/engine/spi/` — `CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`, `SubCaseGroupRepository`, `PlanItemStore`, `CrossTenantCaseInstanceRepository`, `CrossTenantEventLogRepository`. All blocking. Implementations: memory is canonical, JPA uses EntityManager + `@Transactional`. **Injection convention:** repos must be injected by SPI interface (e.g. `CaseInstanceRepository`), NOT by concrete class (e.g. `InMemoryCaseInstanceRepository`). Concrete-class injection prevents `@Alternative @Priority(1)` test wrappers from being substituted — two separate stores are created, causing silent tenant mismatches. Refs engine#663, GE-20260707-f3bece.
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/jq/` — `JQEvaluator` (@ApplicationScoped), `ValidationResult` — canonical jq evaluation; lives here so `scheduler-quartz` can inject it without circular dependency. See protocol `PP-20260522-jq-evaluation-canonical`. Follow-on platform extraction tracked in engine#317.
- `casehub-engine-common/src/main/java/io/casehub/engine/common/internal/executor/` — `WorkerExecutor` (SPI), `WorkerExecutionConfig` (@ApplicationScoped, default timeout), `RetryPolicies` (static utility, backoff computation), `RetryDecision` (sealed: Retry | Exhaust), `ExecutionMetadata` (lineage record for flow path)
- `io.casehub.api.model/` — `TaskStatus` (enum, shared lifecycle — replaces `PlanItemStatus`), `TaskDescriptor` (behavioral interface — `PlanItem` implements it), `TaskSnapshot` (read model), `ExecutorRef` (shared executor identity), `OutcomeKind` (shared outcome taxonomy — includes COMPLETED for scoped worker lifecycle completion), `RoutingResult` (sealed: `Selected`, `Unresolvable`, `Escalated` — replaces `AgentAssignment`), `Assignment` (unified selection record)

Both `engine` and both persistence modules depend on `casehub-engine-common`. Neither persistence module depends on `engine`. `scheduler-quartz` also depends on `casehub-engine-common` directly.

**Test classpath note:** `casehub-engine-common` must be added to `quarkus.index-dependency` in any test `application.properties` that needs `JQEvaluator` discovered as a CDI bean — it is a library JAR, not a Quarkus application module.

**Production implementation:** `casehub-persistence-hibernate` (JPA/Panache, PostgreSQL)
**Test implementation:** `casehub-persistence-memory` (in-memory, thread-safe)

Modules needing in-memory tests add `casehub-persistence-memory` as a test dependency and activate the implementations via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` — no Docker required.

**`TenantAwareRepository` — RLS base class (persistence-hibernate only):** All JPA repositories in `casehub-persistence-hibernate` extend `TenantAwareRepository` (which extends `AbstractJpaRepository`). It provides two helpers that inject PostgreSQL session variables inside `@Transactional` methods:
- `withTenantTransaction(tenancyId, work)` — sets `SET LOCAL "casehub.tenancy_id" = <tenancyId>` before any SQL. tenancyId is an explicit parameter (not read from `CurrentPrincipal` — removed in engine#680). Used by all tenant-scoped repos (EventLog, CaseInstance, CaseMetaModel, SubCaseGroup, PlanItem).
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS). Used by cross-tenant repos (`JpaCrossTenantEventLogRepository`, `JpaCrossTenantCaseInstanceRepository`) and recovery methods.

`SET LOCAL` resets automatically at transaction end — no cleanup needed. All reads AND writes go through `withTenantTransaction()` because `SET LOCAL` only applies inside an explicit PostgreSQL transaction.

**`JpaCrossTenantEventLogRepository`** — separate class (not inside `JpaEventLogRepository`) implementing `CrossTenantEventLogRepository`. `JpaEventLogRepository` implements `EventLogRepository` only. Both extend `TenantAwareRepository`.

**Row Level Security:** Controlled by `casehub.rls.enabled` (default `false`). When enabled, `RlsPolicyApplicator` runs at `@Priority(100)` startup and:
1. Creates the `casehub_crosstenancy` PostgreSQL role with `BYPASSRLS` (requires `CREATEROLE` — pre-create via DBA if the app user lacks it)
2. Applies `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, and `tenant_isolation` policy to 5 engine tables
3. Policy: `USING (tenancy_id = current_setting('casehub.tenancy_id', true))`

**Cross-tenant tests:** Any `@QuarkusTest` that calls `withCrossTenantTransaction()` (e.g. via `CrossTenantEventLogRepository`) requires the `casehub_crosstenancy` role to exist. Add a Dev Services init script:
```properties
# src/test/resources/application.properties
quarkus.datasource.devservices.init-script-path=db/init-crosstenancy-role.sql
```
And create `db/init-crosstenancy-role.sql` in test resources — see `runtime/src/test/resources/` for the pattern.

**SPI contract tests:** Abstract contract tests live in `casehub-engine-common/src/test` (e.g. `PlanItemStoreContractTest`). Modules that provide concrete implementations extend the abstract class. To access these test-only classes, add `casehub-engine-common` with `<type>test-jar</type>` and `<scope>test</scope>` — see `persistence-hibernate/pom.xml` and `persistence-memory/pom.xml` for the pattern.

**`JpaPlanItemStore.updateStatus` flush requirement:** JPQL queries bypass the first-level cache. If `save()` and `updateStatus()` run in the same transaction, the entity from `save()` may not be in the database yet. `updateStatus()` calls `entityManager.flush()` before issuing the JPQL UPDATE to ensure the entity is visible. Two overloads: `updateStatus(planItemId, status)` (cross-tenant, uses `withCrossTenantTransaction`) and `updateStatus(planItemId, status, tenancyId)` (tenant-scoped, uses `withTenantTransaction`). `findDelegated(UUID caseId)` renamed to `findDelegatedCrossTenant(UUID caseId)` — explicit RLS bypass. New tenant-scoped `findDelegated(UUID caseId, String tenancyId)` added. Refs engine#680.

**casehub-ledger on test classpath:** If `casehub-ledger` is a transitive dependency (via `engine`), its JPA entities appear in `@QuarkusTest` contexts and require a datasource even in in-memory test suites. Fix: add `quarkus-jdbc-h2` + `casehub-ledger` as test dependencies, then in the module's test `application.properties`:
```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.datasource.username=sa
quarkus.datasource.password=
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
quarkus.flyway.migrate-at-start=false
```
And add a `NoOpLedgerEntryRepository` (`@Alternative @Priority(1) @ApplicationScoped`) to the module's test sources — see `engine/src/test/java/io/casehub/engine/NoOpLedgerEntryRepository.java`. Applied to: `engine`, `casehub-blackboard`, `casehub-resilience`.

**Modules with `casehub-engine-ledger` as a test dependency** (e.g. `casehub-engine` runtime) must additionally exclude the ledger capture beans from CDI — they observe `CaseLifecycleEvent` and call `LedgerSequenceAllocator` which requires a `ledger_subject_sequence` table not present in the runtime test schema. Without this, cases time out silently with no direct error:
```properties
quarkus.arc.exclude-types=\
  ...,\
  io.casehub.ledger.service.CaseLedgerEventCapture,\
  io.casehub.ledger.service.WorkerDecisionEventCapture
```
See protocol PP-20260610-18a084.

Domain objects (`CaseMetaModel`, `CaseInstance`, `EventLog`) are plain POJOs. The `id` field
is public (`public Long id`) and set by the repository after save.

**`CaseDefinitionRegistry` uses `CaseKey` record:** `DefaultCaseDefinitionRegistry` stores definitions in `Map<CaseKey, RegistryEntry>`. `CaseKey` is an immutable record `(namespace, name, version)` — eliminates the mutable-hashCode map key bug (engine#410). `RegistryEntry` is an inner record `(CaseDefinition, CaseMetaModel)` — a single atomic map put covers both, with no consistency window between two separate maps. `findByIdentity(namespace, name, version)` returns `Optional<CaseMetaModel>` — clean existence query without the throw-on-not-found of `getCaseMetaModel()`. `findByName(String name)` returns `Optional<CaseDefinition>` — name-only lookup for Tier 1 orchestration (`WorkerRuntime.spawnCase()`); throws `IllegalArgumentException` on ambiguity (multiple definitions with the same name across namespaces). All three added as `default` methods per SPI evolution protocol. Refs engine#525, engine#636.

**`CaseInstanceRepository` query methods:** `findByStatus(CaseStatus, tenancyId)`, `findAll(tenancyId)`, `findByNamespaceAndName(namespace, name, tenancyId)` — all return `List<CaseInstance>`. Added as `default` methods (return `List.of()`) per SPI evolution protocol. Implemented in both `InMemoryCaseInstanceRepository` (stream filter) and `JpaCaseInstanceRepository` (JPQL with `join fetch` on `caseMetaModel`). Refs engine#523.

**`@CrossTenant` qualifier:** Cross-tenant SPIs (`CrossTenantEventLogRepository`, `CrossTenantCaseInstanceRepository`) are only injectable via `@CrossTenant`. `CrossTenantProducer` (in `runtime/internal/identity/`) produces both beans, guarded by `@EngineSystem SystemCurrentPrincipal`. Convention-based — CDI does not prevent unqualified injection; code review and the qualifier annotation are the enforcement mechanism. `SystemCurrentPrincipal` is `@ApplicationScoped @EngineSystem` (not `@DefaultBean`) — it does not conflict with `MockCurrentPrincipal`. All 6 engine injection sites (`PendingWorkRegistry`, `DefaultWorkerExecutionRecoveryService`, `QuartzWorkerExecutionJob`, `QuartzWorkerExecutionManager`, `MilestoneSLATimeoutJob`, `DeadLetterReplayService`) use `@CrossTenant`. See protocol PP-20260520-e6a5f0.

## CaseOutcomeObserver SPI

`CaseOutcomeObserver` — lifecycle hook called by the engine when a case reaches a terminal state (COMPLETED, FAULTED, CANCELLED). Implementations write CBR case entries to `CaseMemoryStore` or perform other outcome-based learning operations. Fired from `CaseStatusChangedHandler` on a worker thread (`blocking = true`). `@DefaultBean` no-op in `runtime/internal/worker/`. Consumer implementations are `@ApplicationScoped` and discovered automatically via CDI `Instance<CaseOutcomeObserver>`. `CaseOutcomeEvent` carries `tenancyId` (String) for tenant-scoped persistence operations. Refs engine#477, engine#703. `CaseLifecycleEvent` carries three enrichment fields: `caseDefinitionName` (String, from `CaseMetaModel.getName()`), `namespace` (String, from `CaseMetaModel.getNamespace()`), `contextSnapshot` (JsonNode, working layer at fire time — point-in-time semantics, read-only). Static factories: `CaseLifecycleEvent.of(CaseInstance, commandType, eventType, actorId, actorRole, traceId)` extracts all fields; `CaseLifecycleEvent.of(UUID, String, ...)` (8-arg overload) passes null for enrichment fields (used by `QuartzWorkerExecutionJobListener` and tests). Consumers can discriminate by case type and extract context without a repository round-trip. Refs engine#571.

`ActionGatePolicy` — shared enum (`ALWAYS`, `THRESHOLD`, `CONDITIONAL`) in `api/spi/` for domain classifiers (AML, clinical, devtown, life) to reference instead of defining their own gate policy vocabulary. Refs engine#472.

## CaseContextStore SPI

`CaseContextStore` (`api/context/`) — pluggable storage backend for a single context layer. Flat key-value interface (`get`, `put`, `remove`, `containsKey`, `keySet`, `snapshot`, `clear`, `putAll`, `size`, `isEmpty`). Extends `AutoCloseable` for resource cleanup. Optional hybrid observation via `supportsExternalChangeNotification()` and `onExternalChange(Consumer<ContextChangeEvent>)` — store implementations that can detect external writes (e.g. Redis pub/sub) fire events; self-echo filtering is the store's responsibility, not the framework's. `CaseContextStoreFactory extends NamedStrategy` creates stores per layer per case. `loadStore()` for existing cases (persistent stores return pre-populated state). `isDurable()` signals whether stores survive JVM restarts (controls recovery path: EventLog replay vs direct load). `InMemoryCaseContextStoreFactory` (`@DefaultBean @ApplicationScoped`, id `"in-memory"`) is the default. `EngineStrategyResolver` discovers factory beans via `Instance<CaseContextStoreFactory>`. `CaseDefinition.contextStoreFactory` (nullable String) selects the factory by strategy ID. Refs engine#419.

**CaseContextStoreFactory wiring (engine#725):** `CaseHubRuntimeImpl.startCase()` resolves the factory via `StrategyResolver.resolve(CaseContextStoreFactory.class, definition.getContextStoreFactory())`. UUID is generated early and threaded through to `CaseHubReactor.buildInstance()`. Durable factories (`isDurable()=true`) throw `UnsupportedOperationException` until recovery path migration (engine#732). YAML: `context: { storeFactory: "auditing" }` — nested under `context:` block, read from raw node. `SubCaseExecutionHandler` delegates to `CaseHubRuntime.startCase()` — no separate wiring needed. `snapshot()` and `fromLayerDocument()` intentionally use in-memory factory (detached copies). Refs engine#725.

## DAG Execution Driver

`io.casehub.engine.plan` — DAG-aware parallel execution driver for topological dispatch with dependency scheduling. Pure `java.util.concurrent` — no CDI, no Mutiny. Refs engine#695. Plan-definition types (`DagPlan`, `DagNode`, `JoinType`) live in `engine-api`; execution types (`DagDriver`, `DagResult`, `NodeState`, `DagEventListener`) stay in `engine-common`. See protocol PP-20260727-5267d2.

**Types:**
- `DagPlan<T>` — immutable validated DAG (`engine-api`). Construction rejects cycles, dangling references, and plans with no entry nodes. `entryNodeIds()`, `exitNodeIds()`, `topologicalSort()`, `sequentialMerge(List<DagPlan<T>>)`. Factories: `singleton(T)` (auto-ID), `singleton(String id, T)`, `sequence(List<? extends T>)` (auto-wired chain), `fromNodes(List<DagNode<T>>)` (pre-wired nodes), `parallel(List<? extends T>)`. Blocks uses `DagPlan<LeafTask<T>>` — the type parameter carries the blocks constraint, not the plan type itself.
- `DagNode<T>` — record `(id, task, dependsOn, joinType)`. `dependsOn` defaults to empty, `joinType` defaults to `ALL_OF`.
- `JoinType` — `ALL_OF` (conjunction, fire when every predecessor completes) | `ANY_OF` (disjunction, fire when any predecessor succeeds).
- `NodeState<R>` — sealed interface: `Pending`, `Dispatched`, `Completed(R)`, `Failed(reason, cause)`, `Skipped(reason)`, `Cancelled`. `isTerminal()` and `toTaskStatus()` mapping to `io.casehub.api.model.TaskStatus`.
- `DispatchMode` — `STREAMING` (default, dispatch on each completion) | `BARRIER` (wave-based, all ready → await all → next wave).
- `DagResult<R>` — record `(nodeStates, completedResults, allSucceeded, elapsed)`. `taskStatuses()` projects to `TaskStatus` map.
- `DagEventListener<T, R>` — observation callbacks: `onNodeDispatched`, `onNodeCompleted`, `onNodeFailed`, `onNodeSkipped`, `onNodeCancelled`, `onExecutionComplete`. Listener exceptions isolated — never crash the scheduler.
- `DagDriver<T, R>` — single-use executor. `execute(Function<T, R>)` or `execute(Function<T, R>, Executor)`. `cancel()` marks pending nodes CANCELLED. Continue-by-default failure: failed node dependents transitively SKIPPED per join rules, independent paths unaffected.

**Unified with blocks (blocks#60 Phase 4):** `ExecutionPlan<T>` deleted from blocks. Blocks now uses `DagPlan<LeafTask<T>>` directly from engine-api. `fromNodes()` (renamed from `sequence(List<DagNode<T>>)`) takes pre-wired nodes; `sequence(List<? extends T>)` auto-wires a sequential chain with auto-generated IDs.

**HTN decomposition SPI (blocks#60 Phase 5):** Task tree and decomposition types promoted from blocks to engine-api (`io.casehub.engine.plan`):
- `TaskNode<T>` — sealed: `LeafTask<T>` (non-sealed, extends `TaskDescriptor`) + `CompoundTask<T>` (record with `DecompositionMethod` list). `LeafTask` is non-sealed so blocks can define concrete task types (`PrimitiveTask`, `PlannedTask`) without engine-api knowing about them.
- `DecompositionStrategy<T>` — SPI extending `NamedStrategy`. `decompose(TaskNode<T>, DecompositionContext<T>) → Uni<DagPlan<LeafTask<T>>>`. Default id `"identity"`. Wired into `EngineStrategyResolver` (Phase 6) — YAML: `decompositionStrategy:` on spec block.
- `DecompositionMethod<T>` — record `(Predicate<T> guard, DecompositionStrategy<T> strategy)`. HTN method concept — guard-gated decomposition.
- `DecompositionContext<T>` — interface with `state()` and `depth()`. Blocks provides `AgenticDecompositionContext<T>` (adds `agents()`) — strategies cast when they need the richer context.
- Blocks' `PrimitiveTask<T>` and `PlannedTask<T>` promoted from `TaskNode` inner records to top-level records implementing `TaskNode.LeafTask<T>`. `executor()` delegates to blocks-specific `agent()` field.

`MutableCaseContext` (`api/context/`) — engine-internal extension of `CaseContext`. Adds `writableLayer(String name)` returning `WritableLayer`, `freezeLayer(String name)`, and `close()` (default no-op). `CaseContextImpl` implements `MutableCaseContext`. All engine-internal code (`CaseHubReactor`, `EpisodicLayerUpdater`, handlers) programs to `MutableCaseContext` — zero `instanceof CaseContextImpl` checks remain. `WritableLayerImpl` delegates storage to `CaseContextStore`. `engineSet()` and `engineUpdate()` remain on `WritableLayerImpl` only (not on the `WritableLayer` interface) — `EpisodicLayerUpdater` uses a localized cast. Refs engine#419.

## CaseContext Change Listeners

`CaseContext.onChange(key, listener)` / `onAnyChange(listener)` — per-key change listeners on the working layer. `ContextChangeEvent(key, oldValue, newValue)` with atomic old-value capture via `WritableLayerImpl.setPrev()`. Listeners fire after write lock release (no deadlock). Error isolation per listener. `engineSet()` and `applyDiff()` do NOT fire listeners. Default methods return `Subscription.NOOP`. Refs engine#619.

## CBR Retrieval Bridge

`CbrRetrievalService` (`runtime/internal/routing/`) — runtime bridge for Case-Based Reasoning retrieval. Evaluates `FeatureExtractor` (sealed: `JqFeatureExtractor` | `LambdaFeatureExtractor`) against the case context, builds a `CbrQuery`, calls `CbrCaseMemoryStore` (blocking), and maps results to engine-owned `RetrievedExperience` types. Generalized beyond `PlanCbrCase` (engine#704) — retrieves any `CbrCase` subtype via `CbrConfig.cbrType()` (string discriminator, defaults to `"plan"`). Built-in type map: `"plan"` → `PlanCbrCase`, `"feature-vector"` → `FeatureVectorCbrCase`, `"textual"` → `TextualCbrCase`. Extensible via `CbrCaseTypeRegistration` CDI bean (`api/model/cbr/`, fail-fast on duplicate keys). Generic overload: `retrieve(definition, instance, Class<C>)` for explicit case type. For `PlanCbrCase` results, calls `PlanAdapter.adapt(caseType, scored, features)` inside `mapScoredCase()` — adaptation and mapping are one operation, no intermediate type. REMOVED steps filtered out; adapter failure falls back to raw plan trace mapping (adaptation fields null). Non-plan case types skip adaptation. `PlanAdapter` injected as blocking SPI (same pattern as `CbrCaseMemoryStore`). `NoOpPlanAdapter` (`@DefaultBean` in `casehub-neocortex-memory`) passes through with RETAINED action. `TrackingPlanAdapter` (`@Decorator` in `casehub-neocortex-memory-cbr-tracking`) fires `CbrAdaptationRecorded` CDI event — the engine does not fire this event separately. CBR failure never blocks case progression — exceptions are caught and logged, returning empty results. Injects `CbrCaseMemoryStore` and `PlanAdapter` (both blocking) directly. Refs engine#478, engine#738.

`CbrConfig` on `CaseDefinition` — configures CBR retrieval per case type: `featureExtractor` (JQ string mode or lambda), `domain`, `caseType`, `topK`, `minSimilarity`, `weights`, `vectorWeight`, `timing`, `cbrType` (string, nullable — identifies which `CbrCase` Java class to use for deserialization; distinct from `caseType` which is a query filter), `temporalDecayHalfLifeDays` (Integer, nullable — half-life in days for temporal decay; null = no decay, default). YAML `cbr:` block in case definition schema. Domain falls back to `EpisodicMemoryConfig.domain()` when not explicitly set. `CbrRetrievalTiming` — `PER_EVALUATION` (default) or `CASE_LIFETIME`. Case-lifetime caching stores retrieval results in `CbrRetrievalService` per `caseId`, evicted on terminal case status via `CbrCacheEvictionHandler`. Max 1000 entries. YAML: `cbr: { timing: case-lifetime }`. When `temporalDecayHalfLifeDays` is non-null, `CbrRetrievalService` converts to `TemporalDecay.HalfLife(Duration.ofDays(n))` on the `CbrQuery`. Refs engine#671, engine#733.

`RetrievedExperience` and `ExperiencePlanStep` (`api/spi/routing/`) — engine-owned types representing past case outcomes and their plan traces. `RetrievedExperience` carries `Map<String, Double> featureSimilarities` — per-feature similarity contributions from `CbrSimilarityScorer.scoreDetailed()` (never null, empty when unavailable). For FEATURE_ONLY retrieval, values sum to `similarityScore`. For HYBRID/reranked results, values are diagnostic only. Refs engine#672. `ExperiencePlanStep` carries nullable `adaptationAction` (String — convention matches `AdaptationAction` enum names: RETAINED, SUBSTITUTED, BOOSTED, SUPPRESSED, ADDED) and `adaptationReason` (String). Both null when no adaptation applied (non-plan types, `NoOpPlanAdapter`). String-typed to avoid engine-api dependency on neocortex-memory-api. 6-arg convenience constructor sets both to null. `ExperienceAnalyser.workerSuccessRates()` skips ADDED steps (adapter recommendations — no historical backing) and SUBSTITUTED steps (outcome belongs to the original worker, not the substitute — misattribution risk). `CbrRoutingPromptSection` annotates non-RETAINED adapted steps in case detail lines (`[ACTION: reason]`) and excludes ADDED/SUBSTITUTED from outcome-by-agent aggregates. `CbrAgentRoutingStrategy.analyseExperiences()` delegates to `ExperienceAnalyser.workerSuccessRates()` — single implementation, no duplication. Refs engine#752. Refs engine#738. Populated by `CbrRetrievalService` and threaded through routing contexts (`AgentRoutingContext.experiences()`, `ImplementationRoutingContext.experiences()`, `ProvisionContext`). All three routing context types carry `tenancyId` and `experiences` fields. Retrieved experiences also flow to worker execution via `WorkerScheduleEvent.experiences` → EventLog metadata → `QuartzWorkerExecutionJob` → `WorkerContext.experiences`. Workers access them via `((WorkerRuntime) scope).context().experiences()`. Refs engine#707.

`CbrCaseRetainObserver` (`runtime/internal/memory/`) — `@ApplicationScoped` `CaseOutcomeObserver` that stores `PlanCbrCase` entries in `CbrCaseMemoryStore` on case terminal state. Extracts features via `CbrConfig.featureExtractor()` against `caseFileSnapshot`, reconstructs plan trace from `PlanItemStore` (filtered to terminal `CapabilityTarget` items with non-null `executorName`). Domain resolution follows the same chain as `CbrRetrievalService.resolveDomain()`. Returns early on: definition not found, no `CbrConfig`, domain unresolvable, empty features, empty filtered trace. All exceptions caught and logged — never blocks case progression. `SnapshotCaseContext` (`runtime/internal/memory/`) provides a read-only `CaseContext` adapter wrapping the `caseFileSnapshot` Map for Lambda feature extraction at retain time (only WORKING layer populated). Refs engine#703.

**Test infrastructure:** `casehub-neocortex-memory-cbr-inmem` (test scope in `runtime/pom.xml`) provides `InMemoryCbrCaseMemoryStore`. Requires `quarkus.index-dependency.cbr-inmem` and inclusion in `quarkus.arc.selected-alternatives` (see `application-memory.properties`). `RecordingCbrAgentRoutingStrategy` (`@Alternative @Priority(100)`, id `"cbr-recording"`) captures `AgentRoutingContext` for test assertions.

## JQ Expression Evaluation Surface

All JQ expressions (binding filters, `when` conditions, goals, milestones, `inputProjection`, `outputProjection`) evaluate against the **working layer** (`context.layer(ContextLayer.WORKING).asJsonNode()`), NOT the full layer document (`context.asJsonNode()`). YAML definitions use unqualified field paths (`.transaction`, `.entityResolution`) — the layer structure is an engine implementation detail.

`CaseHubRuntime.signal()` returns `CompletionStage<Void>` (engine#493). The CompletionStage resolves when the signal has been applied, the event log written, and CONTEXT_CHANGED dispatched — it does NOT guarantee goal evaluation completion. The 5-arg overload carries `triggerChannelId` and `triggerCorrelationId` (both nullable) for Qhorus causal lineage. CDI lifecycle events in all handlers (`SignalReceivedEventHandler`, `CaseStartedEventHandler`, `WorkflowExecutionCompletedHandler`) use fire-and-forget `.invoke()` — never `.chain()` — to prevent slow observers from blocking case state progression. Refs engine#231, engine#493.

## Worker Provisioner SPIs

Four interfaces in `api/src/main/java/io/casehub/api/spi/`:

- `WorkerProvisioner` — provision and terminate workers
- `WorkerStatusListener` — lifecycle callbacks (started, completed, stalled)
- `CaseChannelProvider` — open/close/post to backend-agnostic channels. **`postToChannel` takes 6 parameters**: `(CaseChannel, String from, String content, MessageType, String correlationId, String deadline)`. The 3-arg overload is a `default` delegating with three `null`s. `correlationId` and `deadline` are first-class SPI params (engine#343) — consumers no longer parse them from `CommandContent` JSON.
- `WorkerContextProvider` — build startup context from ledger lineage
- `WorkerExecutionManager` — `getActiveCaseIds(String workerId): List<UUID>` is a `default` method added in engine#56 that returns Quartz job case UUIDs currently scheduled for the given worker; used by `casehub-engine-actor-state` to populate the active-cases slice of the actor state view

**Default implementations** in `engine/src/main/java/io/casehub/engine/internal/worker/`:
- `NoOpWorkerProvisioner`, `NoOpWorkerStatusListener`, `NoOpCaseChannelProvider`, `EmptyWorkerContextProvider`
- `NoOpCapabilityHealth` — returns `Ready` for all probes; deployments without `casehub-eidos-api` get transparent no-op
- `NoOpVocabularyRegistry` — exact-match-only semantics; when `casehub-eidos-runtime` is on the classpath, `CdiVocabularyRegistry` displaces this automatically. Injected by `AgentCandidateFactory` for vocabulary-grounded subsumption matching. Refs engine#609.

All are `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) — they yield automatically to any consumer-provided implementation without requiring `selected-alternatives` configuration. See protocol `PP-20260514-engine-spi-noops-defaultbean`.

**Default routing strategies** in `runtime/src/main/java/io/casehub/engine/internal/routing/`:
- `FirstSupportedRoutingStrategy` — `@DefaultBean @ApplicationScoped`; iterates `@WorkerBackend` managers by `@Priority` (descending), returns first where both `supports()` and `canExecute()` return true (engine#461, engine#587)

**Composite execution manager** in `runtime/src/main/java/io/casehub/engine/internal/worker/`:
- `CompositeWorkerExecutionManager` — `@ApplicationScoped` (not `@DefaultBean`); routes `submit()` via `WorkerExecutionRoutingStrategy`, aggregates `getActiveWorkCount`/`getActiveCaseIds`/`canExecute` across `@WorkerBackend` backends (engine#461). When no backends are discovered, `submit()` throws `ProvisioningException`. `canExecute(WorkerFunction)` delegates to backends — returns `true` if any backend can execute the function type (engine#587). Overrides the 6-arg `submit(... bindingName)` to thread `bindingName` through to the selected backend (engine#676).

**`ContextDiffStrategy`** is engine-internal strategy selection, not a consumer-replaceable SPI. Selected via `casehub.engine.diff-strategy` config (`none` | `top-level` | `json-patch`, default `none`). A `@Produces @DefaultBean` producer in `engine/internal/diff/ContextDiffStrategyProducer` instantiates the chosen POJO — consumer `@ApplicationScoped` impl still wins automatically.

**SPI placement rule:** Operational SPIs (worker provisioning, lifecycle, channels) go in `api/spi/`; persistence SPIs (`CaseMetaModelRepository`, etc.) go in `casehub-engine-common/spi/`. **Exception:** if an operational SPI takes `CaseInstance` or other `common/internal/` types as parameters, it must go in `common/spi/` to avoid a circular dependency (`api` ← `common` ← `api`). `WorkOrchestrator` is the current example — it uses `CaseInstance`, so it lives in `common/spi/` alongside the persistence SPIs.

To add a new operational SPI: define the interface in `api/spi/`, add a `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) no-op default in `engine/internal/worker/`, add it to the beans table in protocol `PP-20260514-engine-spi-noops-defaultbean`, add contract tests in `api/src/test/java/io/casehub/api/spi/`, and add engine unit tests in `engine/src/test/java/io/casehub/engine/internal/worker/DefaultWorkerSpiImplementationsTest.java`.

**Engine wiring — which SPIs are called and where (Refs #191):**

| SPI | Called in | When |
|-----|-----------|------|
| `WorkerStatusListener.onWorkerStarted` | `WorkerExecutionJobListener` | Quartz job begins |
| `WorkerStatusListener.onWorkerCompleted` | `WorkflowExecutionCompletedHandler` | Worker function returns |
| `WorkerStatusListener.onWorkerStalled` | `WorkerRetriesExhaustedEventHandler` | All retries exhausted |
| `CaseChannelProvider.openChannel` | `CaseStartedEventHandler` | Case starts |
| `CaseChannelProvider.openChannel` + `postToChannel(..., MessageType.COMMAND, correlationId, deadline)` | `WorkerScheduleEventHandler.dispatchCommand` | Worker scheduled — opens channel, posts COMMAND with `correlationId` (eventLogId) and `deadline` (ISO-8601 from PropagationContext, null if no budget) as first-class SPI params. Content JSON still carries both fields for the worker agent. |
| `CaseChannelProvider.closeChannel` | `CaseStatusChangedHandler` | Case reaches terminal state |
| `WorkerContextProvider.buildContext` | `WorkerScheduleEventHandler` | Before Quartz job is submitted (timing contract) |
| `WorkerContextProvider.buildContext` | `QuartzWorkerExecutionJob` | Immediately before worker function — context passed via `WorkerRuntime` |
| `WorkerProvisioner.provision` (→ `ProvisionResult`) + `CaseLifecycleEvent("WorkerStarted")` | `CaseContextChangedEventHandler.tryProvision` | Successful external provisioning — fires `WorkerStarted` (commandType `ProvisionWorker`) after provisioner returns |
| `WorkerProvisioner.provision` | `CaseContextChangedEventHandler.tryProvision` | No pre-defined workers match capability |

`WorkerProvisioner.provision()` is called when a capability binding fires and no pre-defined workers match. `ProvisioningException` is caught and logged; the binding stays eligible for the next context-change tick. The no-op default returns empty capabilities, so it is never called unless a real provisioner is wired in.

**AgentDescriptor association (engine#543):** `AgentDescriptor` is stored on `CaseDefinition` (not Worker). `CaseDefinition.agentDescriptorFor(workerName)` returns `Optional<AgentDescriptor>`. `AgentCandidateFactory.buildCandidates()` takes `CaseDefinition` as a parameter and looks up descriptors via this method. Workers are pure foundation-tier records with no eidos dependency.

**`AgentCandidateFactory` subsumption matching (engine#609):** `AgentCandidateFactory` is `@ApplicationScoped` (not a static utility), injecting `VocabularyRegistry` for vocabulary-grounded capability matching. Two-tier matching: exact string match on `Worker.capabilityNames()` first (fast path), then `CapabilityResolver.resolve()` when the worker has an `AgentDescriptor` with grounded capabilities (subsumption fallback). Workers without descriptors get exact-only matching. `MatchDegree` is surfaced on `AgentCandidate` via the `matchDegree` field (nullable) — propagated from `CandidateMatchingStrategy.match()` which returns `MatchedWorker` (Worker + MatchDegree). `ExactMatchStrategy` returns `MatchDegree.Exact`; `SubsumptionMatchStrategy` propagates the degree from `CapabilityResolver`. Refs engine#638. Three auxiliary matching points (`SchedulerService`, `WorkflowExecutionCompletedHandler`, `PlanningStrategyLoopControl`) remain exact-only.

**`WorkerProvisioner.provision()` returns `ProvisionResult`**. `ProvisionResult(UUID causedByEntryId)` carries the ledger entry ID of the Qhorus COMMAND that triggered provisioning for causal audit linkage. Provisioner implementations that cannot resolve a causal entry return `ProvisionResult.empty()`. No-op defaults still throw `ProvisioningException` on `provision()`. `ProvisionResult` lives in `api/src/main/java/io/casehub/api/spi/ProvisionResult.java`. See protocol `PP-20260529-bcbbb5`. Claudony wiring tracked in claudony#140.

**`ProvisionContext` fields:** `caseId`, `tenancyId`, `taskType`, `workerContext` (nullable), `propagationContext`, `triggerChannelId` (nullable String), `triggerCorrelationId` (nullable String). `tenancyId` identifies the tenant owning the case — populated from `CaseInstance.tenancyId` at the construction site in `tryProvision()`. Provisioner implementations use this to resolve tenant-specific endpoints via `EndpointRegistry`. The trigger fields carry the Qhorus channel ID and correlation ID of the COMMAND that caused provisioning — allowing provisioner implementations to establish causal linkage in the ledger. Engine-internal call sites pass `null` for both until engine#231 threads Qhorus trigger context through the CaseFile-update API.

**`tryProvision()` capabilities gate removed (engine#531):** `CaseContextChangedEventHandler.tryProvision()` no longer gates on `getCapabilities().contains(capability)`. The capabilities set is still passed to `provision()` — the provisioner decides whether it can handle the request based on full context (capabilities + `ProvisionContext` with `tenancyId`).

`WorkerScope` (`io.casehub.worker.api`, worker-api tier 1) — minimal execution scope passed to worker functions as an explicit `BiFunction` parameter: `caseId()`, `taskId()`, `execute(WorkerFunction<T,R>, T)`, `execute(String, Map)`. Replaces the deleted `WorkerExecutionContext` ThreadLocal (engine#693). `WorkerResult<R>` carries typed output as a top-level record component. `WorkerOutcome<R>` is parameterized. `WorkerFunction.Sync<T, R>` takes `BiFunction<T, WorkerScope, WorkerResult<R>>`. YAML `outputType:` field parsed by `CaseDefinitionYamlMapper`; `QuartzWorkerExecutionJob` converts POJO output→Map via Jackson at the scheduler boundary. Workers access context via `WorkerScope` (the second parameter to `WorkerFunction.Sync`'s BiFunction). Cast to `WorkerRuntime` for engine-specific methods: `((WorkerRuntime) scope).context()` for `WorkerContext` (channels, experiences).

**To test SPI wiring:** use `@Alternative @Priority(1) @ApplicationScoped` static inner classes in `@QuarkusTest` with `static` recording fields reset in `@BeforeEach`. This activates the recording bean globally across the test suite without Mockito. See `SpiWiringIntegrationTest` for the pattern. To test provisioner wiring, define a `CaseHub` subclass with a capability binding and no workers — the engine will fall through to `tryProvision()`.

## ActionRiskClassifier SPI

Platform-level oversight gate for consequential worker actions. Workers declare what they are about to do; the engine classifies the risk before applying output and advancing the case. Implemented in engine#402.

**Worker return type — `WorkerResult` (breaking change):** All worker functions now return `WorkerResult` instead of `Map<String, Object>`. `Agent.execute()` also returns `WorkerResult`.
```java
// No consequential action — unchanged behaviour
.function(input -> WorkerResult.of(Map.of("result", "done")))

// Declares a consequential action
.function(input -> WorkerResult.of(
    Map.of("output", "value"),
    PlannedAction.of("File SAR report", "sar.file", Map.of("accountId", "ACC-123"))))
```

**SPI interfaces** in `api/src/main/java/io/casehub/api/spi/`:
- `ActionRiskClassifier` — consumer implementations use this
- `RiskDecision` — sealed: `Autonomous` | `GateRequired(reason, reversible, candidateGroups, expiresIn, scope, resolutionType, quorum)`. `quorum` (`@Nullable QuorumConfig`) — M-of-N multi-party approval config; null = single-approver (backward compat). `QuorumConfig(instances, required, onThresholdReached, allowSameAssignee)` — governance semantics; validated at construction (instances >= 2, required in 1..instances). Factory methods: `majority(N)`, `unanimous(N)`, `atLeast(N, M)`. Uses engine's `OnThresholdReached` enum (KEEP, CANCEL); adapter maps to work-api's enum. `candidateGroups` is `CandidateSetStrategy` (not `List<String>`) — supports dynamic evaluation via `StaticSetStrategy.of(...)`, `ExpressionSetStrategy`, or named CDI strategies resolved via `StrategyResolver`. `resolutionType` (`@Nullable Class<?>`) — declares the expected type for gate WorkItem resolution; threaded as `resolutionTypeName` (String) through `PendingActionGate` → `ActionGateScheduleEvent` → `ActionGateApprovedEvent`. `ActionGateApprovedHandler` validates via `BridgeResolver` and includes the typed resolution in the `actionGateApproved` context entry under `resolution`. Refs engine#634, engine#742, engine#810.
- `PlannedAction` — from `io.casehub.worker.api.PlannedAction` (foundation tier); carries `description`, `actionType`, `parameters` only — no identity fields
- `ClassificationContext` — carries `workerId`, `caseId`, `tenancyId`, `caseDefinitionName`, `capabilityName`, `bindingName`; constructed by engine at classify() call site
- `@RiskClassifier` — CDI qualifier; consumer implementations must use this to avoid CDI conflict with the chain

**Composition pattern:** Multiple consumer classifiers are supported via `@RiskClassifier @ApplicationScoped`:
```java
@RiskClassifier @ApplicationScoped
public class AmlActionRiskClassifier implements ActionRiskClassifier {
    @Override public RiskDecision classify(PlannedAction action, ClassificationContext context) { ... }
}
```
`ChainedActionRiskClassifier` (`@ApplicationScoped`, NOT `@DefaultBean`) discovers all `@RiskClassifier` beans and applies "most restrictive wins". Comparison order: quorum presence (any quorum > no quorum), then `required` count (higher wins), then `instances` count (lower wins — higher approval ratio), then candidateGroups size (fewer wins), then expiresIn (shorter wins). Classifier failure → fail-safe `GateRequired`.

**Gate mechanism:** When `GateRequired` fires:
1. `WorkflowExecutionCompletedHandler` stores `PendingActionGate` in-memory on `CaseInstance` (**not persisted by JPA in v1 — restart loses the gate**; tracked as engine#433)
2. Publishes `ActionGateScheduleEvent` (carrying `resolvedCandidateGroups` — the evaluated `Set<String>` from `CandidateSetStrategy`) → `ActionGateWorkItemHandler` (engine-adapter, work repo) creates a WorkItem (single-approver) or an M-of-N `WorkItemSpawnGroup` via `WorkItemCreator.createMultiInstance()` (multi-approver, when `quorum != null`). Children get no callerRef — only the parent carries the gate ref. `WorkItemLifecycleAdapter.onWorkItemGroupLifecycle()` routes group terminal events to `ActionGateCompletionApplier.applyGroupCompletion()`. Refs engine#810.
3. Human approves/rejects via work inbox
4. `ActionGateCompletionApplier` (engine-adapter, work repo) publishes `ActionGateApprovedEvent` or `ActionGateRejectedEvent`
5. `ActionGateApprovedHandler` (runtime) re-fires `WorkflowExecutionCompleted` with `outcome=Success(null)` — normal completion path applies deferred output
6. `ActionGateRejectedHandler`/`ActionGateExpiredHandler` write context signals (`actionGateRejected`, `actionGateExpired`), fire `CONTEXT_CHANGED`, publish `ACTION_GATE_WORKER_FAULTED` for blackboard PlanItem fault

**Binding guard requirement:** Case definitions with consequential workers MUST include rejection handler bindings. The binding trigger condition must also exclude gate signal paths to prevent re-scheduling while a gate is pending:
```java
.on(new ContextChangeTrigger(".result == null and .actionGateRejected == null and .actionGateApproved == null"))
```

**Startup warning:** `ActionGateDeploymentHealthCheck` warns if `@RiskClassifier` classifiers are registered but `casehub-work-engine-adapter` is absent (gate WorkItem would never be created).

**New event bus addresses** (in `EventBusAddresses`): `ACTION_GATE_SCHEDULE`, `ACTION_GATE_APPROVED`, `ACTION_GATE_REJECTED`, `ACTION_GATE_EXPIRED`, `ACTION_GATE_CANCELLED`, `ACTION_GATE_WORKER_FAULTED` (distinct from `WORKER_RETRIES_EXHAUSTED` — gate faults must not fault the CaseInstance).

See design spec: `docs/specs/2026-06-05-action-risk-classifier-design.md`. Consumer exploration issues: life#20, devtown#56, aml#42, clinical#47, openclaw#6.

## ImplementationRoutingStrategy SPI

Selects which binding(s) handle a capability when multiple bindings target the same capability. Symmetric to `AgentRoutingStrategy` (which selects which worker instance handles a task). Package: `io.casehub.api.spi.routing`. Refs engine#476.

**Pipeline:** Binding eligibility → Compound gating → **ImplementationRouting** → PlanningStrategy → **AgentRouting** → Worker scheduling.

**Sealed result:** `ImplementationSelection` — `Selected(List<String> bindingNames)` | `RunAll()` | `RunNone()`. `Selected` enforces non-empty via constructor validation.

**Default:** `NoOpImplementationRoutingStrategy` (`@DefaultBean @ApplicationScoped` in `runtime/internal/routing/`) returns `RunAll`.

**Integration:** `PlanningStrategyLoopControl.applyImplementationRouting()` runs at step 3.5 — after `stageLifecycleEvaluator.evaluate()`, before `planningStrategy.select()`. Routing filters bindings before PlanItem creation (no create-then-cancel).

## HumanTaskRoutingStrategy SPI

Enriches humanTask candidate sets with historical data from CBR plan traces. Symmetric with `AgentRoutingStrategy` — follows the routing strategy convention (engine#634): `select()` method, context/candidates separation, sealed result type. Package: `io.casehub.api.spi.routing`. Refs engine#741.

**Pipeline:** Binding eligibility → Compound gating → ImplementationRouting → PlanningStrategy → **HumanTaskRouting** (for HumanTaskTarget bindings) → HumanTaskScheduleEvent.

**SPI:** `HumanTaskRoutingStrategy extends NamedStrategy` — `select(HumanTaskRoutingContext, HumanTaskCandidates) → HumanTaskRoutingResult`. `HumanTaskRoutingContext` carries `caseId`, `bindingName`, `tenancyId`, `caseContext` (`CaseContext`, not `JsonNode` — strategies needing JSON call `caseContext.layer(WORKING).asJsonNode()`), `caseDefinition` (`CaseDefinition` — gives strategies access to definition-level configuration), `experiences`. `HumanTaskCandidates` carries pre-resolved `groups`, `users`, and `groupMembership` (`Map<String, Set<String>>` — group name → member actor IDs, expanded by handler via `GroupMembershipProvider`). `allUsers()` returns the union of direct users and all group members. `HumanTaskCandidates.of(groups, users)` backward-compat factory creates empty membership. `HumanTaskRoutingResult` is sealed: `Enriched(candidateGroups, candidateUsers, candidateScores)` | `Unchanged()` | `Escalated(reason)`. `candidateScores` keys are individual actor IDs (direct or group-expanded), never group names. Invariant: `candidateScores.keySet() ⊆ candidateUsers`. Refs engine#754, engine#755, engine#757.

**Default:** `NoOpHumanTaskRoutingStrategy` (`@DefaultBean @ApplicationScoped @Unremovable` in `runtime/internal/routing/`) returns `Unchanged`.

**Implementations:**
- `CbrHumanTaskRoutingStrategy` (`@ApplicationScoped @Unremovable`, id `"cbr"`, `runtime/internal/routing/`) — scores `allUsers()` (direct + group-expanded) using `ExperienceAnalyser.workerSuccessRates()` with `bindingName`-based plan trace matching. Enrichment only — never filters or escalates. Refs engine#754, engine#757.
- `ConstraintHumanTaskRoutingStrategy` (`@ApplicationScoped @Unremovable`, id `"constraint"`, `runtime/internal/routing/`) — declarative rules for candidate filtering and scoring. Two constraint types: `ContextConstraint` (global condition via `ExpressionEvaluator` → `Prefer`/`Exclude` effects on named users and groups) and `WorkloadConstraint` (per-candidate thresholds via `WorkloadDataProvider` SPI). CAN filter (Exclude, maxActiveTaskCount), CAN escalate (all excluded). Uses `ExpressionEngineRegistry` for polymorphic condition evaluation. `WorkloadDataProvider extends NamedStrategy` — `getWorkload(userIds, tenancyId) → Map<String, WorkloadSnapshot>`. `NoOpWorkloadDataProvider` (`@DefaultBean`) returns empty map. `CaseDefinition` gains `humanTaskContextConstraints` (`List<ContextConstraint>`) and `humanTaskWorkloadConstraint` (`WorkloadConstraint`, nullable). Group effects: `Exclude(groups)` removes groups from `candidateGroups` and members from eligible users (policy override — directly-nominated members also removed); `Prefer(groups)` boosts member scores with deduplication. `ContextConstraint.Builder` accumulates within the same effect type (`preferGroups(g).preferUsers(u)` → `Prefer(g, u)`); switching type replaces. Combined factories: `prefer(groups, users)`, `exclude(groups, users)`. Eligible users initialized from `allUsers()`. Refs engine#755, engine#757.

**Integration:** `CaseContextChangedEventHandler.publishHumanTaskSchedule()` expands candidate groups to members via `GroupMembershipProvider.membersOf()` (per-group error isolation — failed groups treated as empty), then resolves the strategy via `EngineStrategyResolver` from `CaseDefinition.getHumanTaskRouting()`. Called between candidate set resolution and `HumanTaskScheduleEvent` publishing. `Escalated` returns early without publishing the event — PlanItem stays PENDING. Consistent with bridge validation failure early return. Refs engine#755, engine#757.

**HumanTaskScheduleEvent** carries `List<RetrievedExperience> experiences` and `Map<String, Double> candidateScores` — threaded from the handler, not from the strategy result (matching the agent routing path where `scheduleWorker()` passes experiences directly).

**ExperienceAnalyser generalization:** `workerSuccessRates(experiences, eligibleIds, Predicate<ExperiencePlanStep>, weights)` — predicate overload replaces the hardcoded `capabilityName` matching. Existing `String capabilityName` overload delegates. For humanTask, callers pass `step -> bindingName.equals(step.bindingName())`.

**Retention:** `CbrCaseRetainObserver.buildRoutingKeyMap()` (was `buildCapabilityNameMap`) includes `HumanTaskTarget` bindings with null `capabilityName`. `PlanTrace.capabilityName`, `AdaptedStep.capabilityName`, and `ExperiencePlanStep.capabilityName` are nullable — null means "no capability" (humanTask trace).

**CaseDefinition** gains `humanTaskRouting` (nullable String, strategy ID). Builder: `.humanTaskRouting("cbr")`. Resolved by `EngineStrategyResolver`.

## Universal Routing Strategy Architecture

`NamedStrategy` marker interface (`io.casehub.platform.api.routing`) and `StrategyResolver` CDI bean provide a consistent named-strategy convention across the platform. All per-case-selectable routing strategies extend `NamedStrategy`, declare `id()`, and are resolved by `StrategyResolver`. Resolution: YAML-specified ID → `@DefaultBean` fallback.

**CandidateSetStrategy** (`api/spi/routing/`) — replaces sealed `ListEvaluator`. Returns `Set<String>`. Two creation modes: value objects (`StaticSetStrategy`, `ExpressionSetStrategy`, `JqCandidateSetStrategy`) for YAML mapper/builder, and named CDI beans for `StrategyResolver` lookup. `HumanTaskTarget` stores `CandidateSetSpec` (sealed: `Inline(CandidateSetStrategy)` | `Named(strategyId, config)`).

**HumanTaskTarget typed context** — `HumanTaskTarget` gains `payloadType` (Class<?>, nullable) and `resolutionType` (Class<?>, nullable) for ContextBridge validation at the WorkItem boundary. `payloadType` validates inputMapping output via `bridge.initialise()` at dispatch time (fail-fast). `resolutionType` validates WorkItem resolution via `bridge.deserialise()` at completion time in `PlanItemCompletionApplier` (work repo engine-adapter). On validation failure, writes `workItemValidationFailed` signal to case context. `BridgeResolver.resolveByTypeNameStrict()` throws on unknown class (unlike `resolveByTypeName()` which falls back to `MapBridge`). `HumanTaskScheduleEvent` carries `payloadTypeName`/`resolutionTypeName` (String, nullable). Work repo stores type names on `WorkItem` entity as opaque metadata (`payload_type_name`, `resolution_type_name` columns, Flyway V10). YAML: `payloadType`/`resolutionType` on `humanTask:` block, resolved via `Class.forName()` at definition load time. ActionGate resolutionTypeName threading tracked in engine#742. Linked data references tracked in engine#740. Refs engine#689, engine#203.

**CandidateMatchingStrategy** (`api/spi/routing/`) — replaces hardcoded `AgentCandidateFactory` matching. Returns `Uni<List<MatchedWorker>>` (`MatchedWorker` record pairs `Worker` with `MatchDegree`; `MatchedWorker.exact()` factory for exact-match sites). Built-in: `ExactMatchStrategy` (id=`"exact"`), `SubsumptionMatchStrategy` (id=`"subsumption"`, `@DefaultBean`). `AgentCandidateFactory` delegates matching, retains health probing and candidate construction.

**CaseDefinition** gains `agentRouting`, `implementationRouting`, `candidateMatching`, `decompositionStrategy` (all nullable String strategy IDs). Resolved at dispatch time via `StrategyResolver`. `decompositionStrategy` also parsed from YAML `spec.decompositionStrategy:` by `CaseDefinitionYamlMapper`. Refs blocks#60 Phase 6.

`CaseDefinition` gains `types: Set<Path>` and `labels: Set<Path>` (both `io.casehub.platform.api.path.Path`, empty by default, unmodifiable via `Set.copyOf()`). `types` = behavioral contracts (implements semantics); `labels` = operational classification. Parsed from YAML via `Path.parse()` in `CaseDefinitionYamlMapper`. `CaseDefinitionRegistry` gains `findByType(Path)` and `findByLabel(Path)` default methods — ancestor matching via `Path.isAncestorOf()`. YAML schema `tags` (dead `type: object`) and `metadata` (dead) removed. Refs engine#652.

**EngineStrategyResolver** (`runtime/internal/routing/`) — `@Alternative @Priority(1)` resolver using per-domain `Instance<>` injection (Quarkus ARC workaround — `Instance<NamedStrategy>` does not discover sub-interface beans). Detects `@DefaultBean` strategies via `InjectableBean.isDefaultBean()` — YAML-specified IDs take precedence over defaults. Adding new strategy SPI types requires updating this resolver's constructor. Refs engine#634, engine#641, GE-20260704-d6aacc.

## Composable Routing Signal Architecture

Layer 3 agent routing uses `ComposableAgentRoutingStrategy` (`@DefaultBean`, id=`"composable"`) which blends scores from independent `RoutingSignalProvider` implementations. Each provider scores candidates independently; the compositor computes a weighted sum. `CandidateSignal` is a sealed interface: `Score(double, String)` | `Exclude(String)` | `Escalate(EscalationReason, String)`. Absent candidates (not in the signal map) have their weight redistributed among contributing providers. Layer 4 strategies (blocks' `LlmAgentRoutingStrategy`, `CbrAgentRoutingStrategy`) remain as `AgentRoutingStrategy` implementations and override the compositor via `@Priority`. Refs engine#790.

**Signal providers:**
- `WorkloadSignalProvider` (id=`"workload"`, runtime) — `1/(1+runningJobs)` availability
- `TrustSignalProvider` (id=`"trust"`, ledger) — trust maturity scoring with Exclude/Escalate for phase 2b/3/borderline
- `ExperienceSignalProvider` (id=`"experience"`, runtime) — `ExperienceAnalyser.workerSuccessRates()` from CBR history
- `PersonalitySignalProvider` (id=`"personality"`, runtime) — cosine similarity between effective personality weights and task `CognitiveDemand`
- `SemanticSignalProvider` (id=`"semantic"`, engine-ai) — embedding similarity between case context and agent vocabulary

**Per-case weight configuration:** `CaseDefinition.routingSignalWeights` (`Map<String, Double>`, nullable). When present, only named providers are called with given weights. When absent, all discovered providers run with equal weights. YAML: `routingSignalWeights:` block under `spec:`. `AgentRoutingContext` carries `cognitiveDemand` (nullable `CognitiveDemand` from `Capability`) and `routingSignalWeights` (nullable `Map<String, Double>` from `CaseDefinition`).

**CognitiveDemand** (`api/model/`) — weighted cognitive function demand profile on `Capability`. `Map<String, Double>` keyed by Jungian function names (Ti, Te, Fi, Fe, Si, Se, Ni, Ne), summing to 1.0. Stored on `CaseDefinition` as `Map<String, CognitiveDemand> cognitiveDemands` (keyed by capability name, NOT on the foundation-tier `Capability` record). YAML: `cognitiveDemand:` nested under each capability. Refs engine#795.

**CaseDefinition authorization** — `CaseDefinition` gains `authorization` (`Map<AclAction, List<String>>`, nullable). Declares which groups receive ACL grants when a case is started. `CaseHubReactor.startCaseInternal()` calls `AccessControlProvider.grantBatch()` before `onCaseStarted()`. Case creator receives automatic ADMIN. If absent, no grants are created (NoOp default). YAML: `authorization:` block under `spec:` with keys `read`, `write`, `admin`, `claim`. Refs platform#219.

**Deleted strategies (engine#790):** `LeastLoadedAgentStrategy`, `TrustWeightedAgentStrategy`, `SemanticAgentRoutingStrategy` — replaced by signal providers + compositor. ADR-0003 (reactive SPI) superseded — virtual threads removed the reactive requirement.

## JPAF Personality-Adaptive Routing

JPAF (arXiv:2601.10025) personality adaptation via `DispositionSignalStore` activation signals and `DispositionHealth` effective weight computation. Engine records signals and routes; eidos computes effective weights, evaluates reflection, updates descriptors. Refs engine#790.

**PersonalitySignalRecorder** (`runtime/internal/routing/`, `@ApplicationScoped`) — records disposition signals on worker task completion. Injected into `WorkflowExecutionCompletedHandler` on both success and failure paths. On SUCCESS: reinforces the engaged cognitive function (whichever of dom/aux has higher demand in the task's `CognitiveDemand`). On DECLINE/FAILURE/EXPIRED: activates the compensatory function (highest-demand function NOT in dom/aux). Uses `DispositionSignalStore.recordActivation(agentId, tenancyId, functionTerm)`. After recording, probes `DispositionHealth` for `EvolutionPending` — if triggered, calls `DispositionEvolution.evaluate()` and handles `Evolved` (calls `signalStore.clear()` — structural profile rewrite invalidates old activations) or `Dampened` (calls `signalStore.decay()`). Refs engine#791, engine#793.

**Effective weight computation (eidos-side):** `effectiveWeight(f) = baseWeight(f) + activationCount(f) × Δw` where `Δw = 0.06` (`DispositionPreferenceKeys.REINFORCEMENT_DELTA`). No materialized TemporaryWeight state — computed at probe time from `DispositionSignalStore.activationCounts()`. `DispositionSignalStore.decay(decayFactor)` — `decayFactor` is the retention fraction (0.0 = instant reset, 1.0 = no decay). Default 0.20 via `DispositionPreferenceKeys.DECAY_FACTOR` (configurable per tenancy). Called on reflection rejection (`Dampened`). On reflection acceptance (`Evolved`), `signalStore.clear()` resets all activations. `DefaultDispositionEvolution.evaluate()` is side-effect-free — callers own state mutation. Refs engine#792, engine#796.

## Goal Abandonment

`GoalAbandonmentEvaluator` (`runtime/internal/routing/`, `@ApplicationScoped`) — queries `BehavioralSignalStore` for per-goal DECLINE signal counts against a configurable threshold (`casehub.engine.goal.abandonment-threshold`, default 5). `isAbandoned(agentId, tenancyId, goalName)` returns true when count >= threshold. `activeGoals(AgentDescriptor)` filters abandoned goals. Uses `Instance<BehavioralSignalStore>` — when no store is available, all goals are active (transparent no-op). Sentinel capability `"__goal__"` separates goal signals from capability health signals. Refs engine#807.

`GoalFailureRecorder` (`runtime/internal/routing/`, `@ApplicationScoped`) — records DECLINE signals for all agent goals on non-success worker outcomes. Injected into `WorkflowExecutionCompletedHandler` at the `handleSemanticFailure` call site. Looks up `AgentDescriptor` via `CaseDefinitionRegistry`, records one signal per goal. Uses `Instance<BehavioralSignalStore>` — no-op when unavailable. Current limitation: all goals increment on each decline — per-goal discrimination requires goal-capability mapping (engine#860). Refs engine#807.

## Repeatable Compound

`PlanItemDefinition.Compound` gains `repeatable` (boolean, builder). Repeatable compound lifecycle is tracked via `PlanItemExecutionState` CAS transitions. Refs engine#482. Stage-based repeatability infrastructure (StageResetOutcomesCleaner, StageActivatedEvent, resetForRepetition) was removed in the Stage retirement (blocks#60 Phase 3C.3).

## Lifecycle Scopes

`LifecycleScope` (`api/model/`) — scope governing worker lifetime: `BINDING` (default, single dispatch), `COMPOUND` (lives for compound duration), `CASE` (lives for case duration). `Participation` — `PARTICIPANT` (blocks completion) or `COMPANION` (sidecar, excluded from completion). `ExecutionMode` — `TRANSIENT` (fire-and-forget, default), `PERSISTENT` (long-running virtual thread with mailbox), `REINVOKED` (re-invoked on each trigger with accumulated state). All three are declared on `Binding` and validated at build time. `ScopeActivatedTrigger` (`api/model/`) fires when the owning scope becomes active. YAML: `lifecycleScope:`, `participation:`, `executionMode:` on binding definitions; `scopeActivated: {}` as trigger type.

`Compound.scopedBindings()` returns `Map<String, Participation>` (was `Set<String>`). `evaluateCompletion()` excludes COMPANION bindings from completion count — only PARTICIPANT bindings block compound completion.

`ScopedWorkerRegistry` (`common/internal/worker/scope/`, `@ApplicationScoped`) — tracks active scoped worker sessions per case. `ScopedWorkerSession` (sealed: `Persistent`, `Reinvoked`) carries mailbox or accumulated state. `Reinvoked` gains `lastInputDataHash` for cycle detection and `executorName` for re-dispatch without re-routing. `CaseContextChangedEventHandler` checks the registry before dispatch — existing sessions receive context events instead of creating new PlanItems. For REINVOKED, re-dispatch skips agent routing and uses the session's `executorName`. For PERSISTENT, context changes are put on the session's mailbox. `registerScopedSession()` registers both session types BEFORE dispatch (prevents race window). `computeInputHash()` breaks REINVOKED feedback loops by suppressing re-invocation when projected input is unchanged. Per-binding `ReentrantLock` in `ScopedWorkerRegistry.executionLock()` serializes REINVOKED read-execute-write sequences. `ScopedWorkerTerminationHandler` (`runtime/internal/engine/handler/`) consumes `COMPOUND_COMPLETED` and terminates scoped workers owned by the completed compound. `CaseStatusChangedHandler` calls `terminateByCase()` on case terminal state. `ScopedWorkerOutputHandler` (`runtime/internal/engine/handler/`, `@ConsumeEvent @RunOnVirtualThread`) applies scoped worker intermediate output to the case context using `ConflictResolver` per-key strategy. Guards on session existence — discards output if scope ended during in-flight execution. Publishes `CONTEXT_CHANGED` for downstream re-evaluation. `PersistentWorkerFunctionHandler` (`runtime/internal/executor/`, `@ApplicationScoped`) handles `WorkerFunction.Persistent` — retrieves the pre-registered session from the registry, builds `DefaultPersistentScope` backed by the session's mailbox, spawns a virtual thread running the persistent handler. Thread faults publish `WorkflowExecutionCompleted` with fresh `CaseInstance` (loaded via `WorkerExecutionRecoveryService`). `DefaultPersistentScope` (`runtime/internal/worker/scope/`) implements `PersistentScope<T>` — `nextEvent()` applies input projection and deserializes, `emit()` applies output schema projection then publishes `ScopedWorkerOutputEvent`. Refs engine#823, engine#824, engine#825, engine#826.

`CompoundCompletedEvent` and `CompoundActivatedEvent` (`common/internal/event/`) — cross-module event types for compound lifecycle. Published by planning module (`CompoundCompletionEvaluator`, `CompoundLifecycleEvaluator`), consumed by runtime (`ScopedWorkerTerminationHandler`). Address constants in `EventBusAddresses`: `COMPOUND_COMPLETED`, `COMPOUND_ACTIVATED`. Planning module's `BlackboardEventBusAddresses` delegates to these common constants.

Completion suppression: `QuartzWorkerExecutionJob` checks `executionMode` from EventLog metadata. Non-TRANSIENT workers returning `WorkerOutcome.Success` suppress `WorkflowExecutionCompleted` — PlanItem stays RUNNING. Only `WorkerOutcome.Completed` triggers PlanItem completion. Refs engine#237.

## Agent Worker AI Model

AI agent workers live in `api/src/main/java/io/casehub/api/model/ai/`:

- `Agent` — immutable execution unit; holds systemPrompt, transformers, ChatModel, optional responseSchema. **`execute()` returns `io.casehub.worker.api.WorkerResult`** — worker-api foundation type. Agent workers that want to declare a consequential action return `WorkerResult.of(output, PlannedAction.of(...))`. PlannedAction is on `WorkerOutcome.Success` — the type system prevents non-success outcomes from carrying actions.
- `AgentWorkerFunction(Agent)` — `implements io.casehub.worker.api.WorkerFunction` (marker interface); dispatched by `SyncAgentWorkerFunctionHandler` via `agent.agent()::execute`
- `FlowWorkerFunction(Workflow)` — lives in `casehub-engine-flow` (not api); dispatched by `FlowWorkerFunctionHandler`. See casehub-engine-flow Module.
- `AgentBuilder` — fluent builder; JQ string mode (`inputSchema(String)`) or lambda mode (`inputTransformer(UnaryOperator<JsonNode>)`) for transformers; mutually exclusive per direction
- `ChatModelProvider` — SPI interface; implementations use reflection (`Class.forName`) to avoid compile-time LLM SDK dependencies
- `ModelType` — enum: OPENAI, OLLAMA, ANTHROPIC, MISTRAL, GOOGLE_AI_GEMINI
- `JqTransformer` — standalone JQ evaluator (jackson-jq 1.6); thread-safe after construction
- `AgentException` — unchecked exception for agent failures (invalid JSON, JQ errors, template errors)

Provider implementations in sub-packages (`openai/`, `anthropic/`, `mistral/`, `gemini/`, `ollama/`) use `ServiceLoader` for discovery and reflection-based builder construction. All schema-declared fields are wired through each provider's builder and `AgentConverter`: OpenAI supports `baseUrl`, `organizationId`, `frequencyPenalty`, `presencePenalty`; Anthropic supports `baseUrl`, `version`; Mistral supports `baseUrl`. `baseUrl` enables OpenAI-compatible servers (Ollama, vLLM, OpenClaw). Refs engine#527.

`AgentConverter` (`api/.../converter/AgentConverter.java`) bridges jsonschema2pojo schema models (`io.casehub.model.Agent`) to API `Agent` instances. Called by `CaseDefinitionYamlMapper` when a worker has an `agent` YAML block.

**Test pattern:** Mock `ChatModel` via `AgentBuilder.model(ChatModel)` for unit tests. For `@QuarkusTest` integration tests, define inner `CaseHub` subclasses with mock `ChatModelProvider` returning canned JSON. No Mockito needed — use anonymous `ChatModel` implementations.

## YamlCaseHub Augmentation

`YamlCaseHub.getDefinition()` is `final` — subclasses that need to add programmatic workers override `protected void augment(CaseDefinition)` instead. The hook is called once, inside the double-checked lock, between YAML loading and caching. CDI-injected fields are available. Workers added in `augment()` use `Worker.builder().capabilityName("name")` (string name, not `Capability` instance). Refs engine#591.

## casehub-engine-planning Module

Core planning infrastructure. Provides PlanningStrategy, CasePlanModel, PlanItem, and compound PlanItem types (`PlanItemDefinition`, `CompletionSemantics`, `DispatchMode`). Package: `io.casehub.engine.planning`. Renamed from `casehub-engine-blackboard` / `io.casehub.blackboard` in blocks#60. `PlanningStrategyLoopControl` is `@ApplicationScoped` (sole `LoopControl`); `ChoreographyStrategy` (id=`"default"`) is the fallback strategy. Stage is fully retired — replaced by `PlanItemDefinition.Compound` (blocks#60 Phase 3C.3).

**Compound PlanItemDefinition hierarchy (blocks#60):** `PlanItemDefinition` is a sealed interface with two permits: `Primitive` (leaf — has executor, entry condition) and `Compound` (container — has children, planning strategy, CompletionSemantics, DispatchMode, entry/exit conditions, repeatable, scopedBindings). `Primitive` has no `dispatchMode` — only `Compound` controls dispatch. `Compound.builder("name")` provides a fluent builder with defaults (CHOREOGRAPHED dispatch, All completion). `Compound.scopedBindings()` declares which binding names the compound gates — bindings only dispatch when their owning compound is RUNNING. `PlanItemExecutionState` tracks compound lifecycle via CAS transitions (PENDING→RUNNING→COMPLETED). `CompoundLifecycleEvaluator` evaluates entry/exit conditions. `CompoundStrategyDispatcher` (`@ApplicationScoped`) groups bindings by compound parent and delegates to per-compound strategies. `CompoundCompletionEvaluator` propagates completion up the compound tree. `evaluateCompletion()` on `DefaultCasePlanModel` checks both structural children (definition status) AND scoped bindings (PlanItem status). `CasePlanModel` gains `getAllCompounds()` and `getCompoundsByStatus(TaskStatus)`. `PlanningStrategyLoopControl.select()` uses compound-based gating (scopedBindings + definition status), `CompoundLifecycleEvaluator` for activation, and `CompoundStrategyDispatcher` for dispatch. Stage-based gating is removed from the dispatch path. Stage is fully retired — all infrastructure deleted and integration tests migrated to Compound (blocks#60 Phase 3C.3). `CompoundLifecycleEvaluator` evaluates BEFORE gating so compounds activated in the same cycle immediately gate their scoped bindings. `CompoundStrategyDispatcher` uses case-level `planningStrategy` for free-floating bindings (not hardcoded "default").

**Build and test:**
```bash
mvn install -DskipTests -q          # install deps to local repo first
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl planning
```

**Test conventions:**
- `@QuarkusTest` classes MUST be named `*Test.java` — never `*IT.java`
  (`*IT` is picked up by failsafe instead of surefire; produces `Tests run: 0` with no error)
- Uses `casehub-persistence-memory` as a test dependency for in-memory SPI implementations
- `src/test/resources/application.properties` sets `quarkus.http.test-port=0`, indexes the
  persistence-memory module via `quarkus.index-dependency`, and activates the in-memory
  alternatives via `quarkus.arc.selected-alternatives` (including `InMemorySubCaseGroupRepository`)
- `@ObservesAsync` CDI event delivery is **unreliable in `@QuarkusTest`** — observer methods
  are silently never invoked. When testing observer logic, inject the listener bean and call the
  observer method directly rather than relying on `Event.fireAsync()`.
- `@ConsumeEvent` (Vert.x event bus) handlers are async — `eventBus.publish()` is fire-and-forget,
  so tests that call `publish()` require `Awaitility.await()` or `Thread.sleep()` to bridge the
  async gap. Prefer injecting the handler bean and calling the `@ConsumeEvent` method directly;
  `@Transactional` is enforced by the CDI proxy identically to production. Keep one wiring test
  per handler that uses `eventBus.publish()` + `await()` to confirm `@ConsumeEvent` routing.
  See `HumanTaskScheduleHandlerTest` (engine#290).

**Key blackboard handlers:**
- `PlanItemCompletionHandler` — marks PlanItems COMPLETED on `WORKER_EXECUTION_FINISHED` and `SUBCASE_EXECUTION_COMPLETED`; delegates compound completion to `CompoundCompletionEvaluator` (passes `item.getBindingName()`, not planItemId)
- `WorkerRetryExhaustionHandler` — marks CapabilityTarget PlanItems FAULTED on `WORKER_RETRIES_EXHAUSTED` (both guard-blocked and Quartz-exhausted paths); delegates compound completion to `CompoundCompletionEvaluator`. Refs engine#331.
- `CompoundCompletionEvaluator` — walks the compound parent chain from a changed binding name; evaluates completion semantics (All, MOfN, FirstWins) across structural children and scoped bindings; fires `COMPOUND_COMPLETED` event. Replaces `StageAutocompleteEvaluator`.
- `SubCaseExecutionHandler` — consumes `SUBCASE_SCHEDULE` events. Detects self-reference (parent definition == child SubCase identity) and enforces bounded recursion via `SubCase.maxRecursionDepth()` (int, default 0 = hard block). Depth is computed by walking the `parentCaseId` chain via `CaseInstanceCache`, counting ALL same-definition ancestors (total counting, not consecutive — prevents trampoline bypass via A→B→A chains). Short-circuits at `maxRecursionDepth`. If `depth >= maxRecursionDepth` → faults the PlanItem. The cache walk relies on `CaseInstanceCacheImpl` having no eviction (bare `ConcurrentHashMap`, no `remove()` method) — all ancestors in a recursive chain are WAITING and remain cached. **Single-node assumption:** `CaseInstanceCache` is per-JVM. Clustering would require a distributed cache or repository query. **Known limitation:** mutual recursion (A→B→A cycles) is unbounded — B spawning A bypasses the self-reference check. Refs engine#573.
- `SubCaseCompletionService` — handles grouped sub-case completion (M-of-N threshold). Fires `Event<SubCaseGroupLifecycleEvent>.fireAsync()` for every non-null `GroupStatus` transition (IN_PROGRESS, COMPLETED, REJECTED). Observers (monitoring, audit, Claudony dashboard) subscribe without coupling to the engine. Refs engine#249.

**PlanItem CAS transitions:** `PlanItem.tryMarkRunning()` is a CAS-based transition (PENDING→RUNNING, AVAILABLE→RUNNING) that returns `true` on success, `false` when already RUNNING or terminal. Used by handlers to avoid duplicate CONTEXT_CHANGED fan-out. Per-case serialization of CONTEXT_CHANGED is tracked in engine#646. Refs engine#636.

**PlanItem implements TaskDescriptor:** `PlanItem` implements `TaskDescriptor` (`api/model/`), providing `id()`, `description()`, `executor()`, `status()`, `createdAt()`, `snapshot()`. Stores `ExecutorRef` instead of bare `workerName`. `executorName()` is a derived convenience returning `executor().name()`. `getPlanItemId()` is deprecated — use `id()` (from `TaskDescriptor`). `PlanItem.create()` takes `ExecutorRef` instead of `String workerName`. `PlanItem.restore()` takes `ExecutorRef` as a nullable parameter. Persistence: `PlanItemRecord`, `PlanItemSaveRequest`, `PlanItemEntity` carry `executorName`/`executorDescription` (flat strings); `PlanItemRestorer` reconstructs `ExecutorRef.of(name, description)`. Refs engine#700.

## Quartz

Use RAM store — no JDBC store, no Quartz tables:
```properties
quarkus.quartz.store-type=ram
```

## Ecosystem Conventions

All casehubio projects align on these conventions:

**Quarkus version:** `version.quarkus.platform` in root `pom.xml`, currently `3.32.2`. All ecosystem projects must match. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Root `pom.xml` has `<repositories>` with `id=github` pointing to `https://maven.pkg.github.com/casehubio/*`. CI uses `server-id: github` + `GITHUB_TOKEN` in `actions/setup-java`.

**Cross-project dependency versions** are properties in root `pom.xml`:
- `version.io.casehub.work` — casehub-work-api and casehub-work-core (`0.2-SNAPSHOT`)
- `version.io.casehub.ledger` — casehub-ledger (`0.2-SNAPSHOT`)

Submodule poms reference `${version.io.casehub.work}` etc. — no hardcoded versions.

**Publishing:** `maven.deploy.skip=false` is the default in root `pom.xml` properties — the root parent POM (`io.casehub:parent`) IS published to GitHub Packages. Downstream consumers need it to resolve the effective POM of child artifacts (`api`, `engine`, etc.). Modules that should not be published override with `<maven.deploy.skip>true</maven.deploy.skip>` in their own `<properties>`.

## IntelliJ MCP Tools

Two IntelliJ MCP servers are available (`mcp__intellij__*` and `mcp__intellij-index__*`).
Before using Bash tools, check whether the operation can be performed via IntelliJ — it is
often more correct, faster, and less error-prone (symbol lookup, rename refactoring, diagnostics,
file search). Verify both are responsive at session start; stop and report to the user if either
is unavailable.

## casehub-work-adapter Module (relocated)

Relocated to `casehub-work-engine-adapter` in the casehub-work repo (`io.casehub.work.engine` package). See work's CLAUDE.md for module documentation. Refs casehubio/work#290.

## casehub-engine-actor-state Module

Optional module providing a unified actor workload view (`GET /actors/{actorId}/state`). Aggregates active cases (via `WorkerExecutionManager.getActiveCaseIds`), open WorkItems (via `casehub-work-api`), and open Qhorus obligations (via `CommitmentStore.findOpenByObligor`) using the `ActorStateContributor` SPI from `casehub-platform-api`. Activated by adding `casehub-engine-actor-state` to the consumer's classpath.

## casehub-engine-flow Module

Optional module enabling `Worker(Workflow)` to dispatch casehub workers from within Serverless Workflow steps and await their results. Activated by adding `casehub-engine-flow` to the consumer's classpath.

`FlowWorkerFunction` (record, implements `WorkerFunction`) lives here — the serverlessworkflow SDK never leaves this module. `FlowWorkerFunctionProvider` (`@ApplicationScoped`, implements `WorkerFunctionProvider`) handles YAML `do:` block construction — receives raw `JsonNode`, deserializes to `Workflow` via `WorkflowReader`. `FlowWorkerFunctionHandler` (`@ApplicationScoped`, implements `WorkerFunctionHandler`) executes workflows using `WorkflowApplication` singleton and `FlowExecutionRegistry`, running on `@VirtualThreads ExecutorService`. `CasehubCallableTaskBuilder implements CallableTaskBuilder<CallFunction>` (registered via Java SPI) handles `call: casehub:dispatch` YAML steps. Note: `CallFunction` and `FunctionArguments` are in `io.serverlessworkflow.api.types` — not the `.func` experimental subpackage.

## casehub-engine-queue Module

Optional case queue operational layer. Activated via CDI when on the classpath — same pattern as `casehub-blackboard`.

**Build and test:**
```bash
mvn install -DskipTests -q          # install deps to local repo first
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl queue
```

**Key components:**
- `CaseLabelEvaluator` — `@ObservesAsync CaseLifecycleEvent`, evaluates `LabelRule` conditions against context snapshot, applies Add/Remove actions (clean-slate), delegates to `SubjectViewOrchestrator.evaluateAndTrack()`. Per-case `ReentrantLock` serializes concurrent evaluations.
- `CaseQueueEntryManager` — `@Observes CaseQueueEvent`, manages `CaseQueueEntry` lifecycle: ADDED creates PENDING, REMOVED deletes PENDING or revokes CLAIMED (fires `CaseQueueEntryRevoked`), CHANGED is no-op in v1.
- `CaseQueueService` — `@ApplicationScoped`, operational actions: `claim()`, `release()`, `escalate()`. All methods enforce tenancy. `claimIfPending()` is atomic CAS.
- `CaseQueueViewManager` — wraps `SubjectViewOrchestrator.saveView()/deleteView()` with deterministic UUID via `UUID.nameUUIDFromBytes(tenancyId:name)`.
- `CaseLabelReconciler` — startup crash recovery at `@Priority(200)`, re-evaluates all active cases per tenancy via `CrossTenantSubjectViewStore.findDistinctTenancyIds()`.
- `CaseQueueEntryStore` — SPI interface; `InMemoryCaseQueueEntryStore` for tests.

**Dependencies:** `casehub-engine-common`, `casehub-engine-api`, `casehub-platform-view`, `casehub-platform-api`. Test: `casehub-persistence-memory`, `casehub-platform-view-inmem`.

**Data model:**
- `CaseInstance` gains `Set<String> labels` (engine-common, mutable, empty default). JPA: `@ElementCollection` on `CaseInstanceEntity` (`case_instance_label` table).
- `CaseDefinition` gains `List<LabelRule> labelRules` (engine-api, platform's `LabelRule` directly). Builder: `.labelRule(LabelRule)`. YAML: `labelRules:` block with `name`, `when` (JQ), `actions` (`add:`/`remove:`).
- `CaseQueueEntry` — operational record: `id`, `caseId`, `tenancyId`, `viewId`, `viewName`, `status` (PENDING/CLAIMED/REVOKED), `assignedTo`, `claimedAt`, `escalatedAt`, `previousViewId`, `previousViewName`, `createdAt`.

**CDI events:** `CaseQueueEvent` (ADDED/REMOVED/CHANGED), `CaseQueueEntryClaimed`, `CaseQueueEntryReleased`, `CaseQueueEntryEscalated`, `CaseQueueEntryRevoked`. All fired via `Event.fireAsync()`.

Refs engine#730.

## Worker Execution Architecture

`WorkerExecutor` (`common/internal/executor/`) abstracts how to run a worker function — independent of any scheduler. `DefaultWorkerExecutor` (`runtime/internal/executor/`) is a composite over `WorkerFunctionHandler` instances — it iterates `Instance<WorkerFunctionHandler>`, finds the first handler that `supports()` the function, delegates execution, and applies output schema evaluation as `.map()` post-processing. `SyncAgentWorkerFunctionHandler` (`runtime`) handles `Sync` and `AgentWorkerFunction` on `@VirtualThreads ExecutorService` with timeout enforcement. `FlowWorkerFunctionHandler` (`flow`) handles `FlowWorkerFunction` — see casehub-engine-flow Module. `WorkerFunctionHandler` (`common/internal/executor/`) is the engine-internal SPI; `outputProjection` is deliberately absent from the handler interface (cross-cutting concern owned by the composite executor). `WorkerFunctionProvider` and `WorkerFunctionProviderRegistry` (`api/spi/`) delegate YAML worker function construction to modules — the flow module registers `FlowWorkerFunctionProvider` for `do:` blocks; Agent and Sync construction stays inline in `CaseDefinitionYamlMapper`. Worker/Capability/WorkerFunction/WorkerResult/WorkerOutcome are from `io.casehub.worker.api` (foundation tier); `WorkerFunction` is a marker interface with no `execute()` method. `Worker` carries `Set<String> capabilityNames` (not `Capability` instances) — workers declare support by name; the engine resolves authoritative `Capability` instances from `CaseDefinition.getCapabilities()` via the binding's `CapabilityTarget`. Refs engine#591. `WorkerFunction.None` (engine#586) models external workers with no in-process function — `WorkerFunction.NONE` is the singleton constant. `Worker.Builder.noFunction()` is the convenience method. `CaseDefinitionYamlMapper` uses `NONE` for workers without an agent block or flow provider. `WorkerExecutionManager.canExecute(WorkerFunction)` is a `default true` method (engine#587) — Quartz overrides with positive handler delegation (iterates `WorkerFunctionHandler` instances, returns `true` only when a handler supports the function). `WorkerRecoveryCoordinator` (`runtime/internal/engine/recovery/`) initiates recovery at `@Priority(22)` via `WorkerExecutionRecoveryService.recoverPendingScheduledWorkers()` with a configurable timeout (`casehub.engine.recovery.timeout`, default 60s). Tracks `RecoveryStatus` (`PENDING`/`COMPLETED`/`FAILED`). `WorkerRecoveryHealthCheck` (`@Readiness`) reports the status at `/q/health/ready`. `QuartzWorkerExecutionManager.onStart(@Priority(20))` retains only Quartz job listener registration. Refs engine#593. ExecutionPolicy/RetryPolicy/BackoffStrategy are from `io.casehub.platform.api.governance`.

`QuartzWorkerExecutionJob` is a thin fire-and-forget Quartz adapter: resolves context (EventLog, CaseInstance, Worker, Capability), delegates to `WorkerExecutor.execute()`, and subscribes with success/failure callbacks. Success publishes `WORKER_EXECUTION_FINISHED`; failure routes to `QuartzRetryService`.

`QuartzRetryService` (`scheduler-quartz`) owns failure handling: persists `WORKER_EXECUTION_FAILED` event log, resolves retry policy from the worker's `ExecutionPolicy`, counts prior failures, and uses `RetryPolicies.evaluate()` to decide retry vs exhaust. On retry, reschedules via `QuartzWorkerSchedulerService`; on exhaust, publishes `WORKER_RETRIES_EXHAUSTED`.

`RetryPolicies` (`common/internal/executor/`) is a pure static utility for backoff computation — no CDI, no dependencies. `RetryDecision` is a sealed type: `Retry(Duration delay)` or `Exhaust(String reason)`. Moved from `QuartzWorkerExecutionJobListener` so any scheduler adapter can reuse the same backoff logic.

`WorkerExecutionConfig` (`common/internal/executor/`) provides the default worker timeout (`casehub.engine.worker.default-timeout-ms`, default 60000ms). Per-worker overrides come from `ExecutionPolicy.timeoutMs()`.

**WorkerRuntime — Tier 1 orchestration surface (engine#490, #485, #693):** `WorkerRuntime extends WorkerScope` (`api/engine/`) is a per-invocation handle letting workers call other functions and spawn sub-cases. Methods: `caseId()`, `taskId()`, `context()`, `execute(WorkerFunction<T,R>, T)` (generic, from WorkerScope), `execute(String workerName, Map)`, `spawnCase(String, Map)`, `awaitCase(UUID, Duration)`, `spawnAndAwaitCase(String, Map, Duration)`. Passed as the second parameter to `WorkerFunction.Sync`'s `BiFunction<T, WorkerScope, WorkerResult<R>>` — no ThreadLocal. `WorkerRuntimeFactory` (`runtime/internal/executor/`, `@ApplicationScoped`) creates per-invocation `DefaultWorkerRuntime` instances with `create(UUID caseId, String taskId, WorkerContext context)`. `execute()` never throws — runtime exceptions wrapped in `WorkerResult.failed()`. `execute(String workerName, Map)` converts Map→POJO via Jackson when `inputType != Map.class`. `WorkerFunctions` (`api/model/`) provides `sequence(WorkerFunction<?,?>...)` combinator using the scope parameter and `merge(Map, Map)` utility.

**CaseCompletionTracker** (`runtime/internal/engine/`, `@ApplicationScoped`) — tracks in-flight cases and throws `CaseTerminatedException` (runtime exception in `common/internal/exception/`) when a worker attempts to signal a case that has already terminated. Used by `DefaultCaseHubRuntime.signal()` to prevent workers from mutating completed/faulted/cancelled cases. Refs engine#629.

**signalAndAwait — bulk signal + settlement (engine#490, #483):** `CaseHubRuntime` gains three default methods: `signal(UUID, Map<String,Object>)` (bulk atomic context update, single CONTEXT_CHANGED), `signalAndAwait(UUID, Map, Duration)` → `CompletionStage<CaseContext>` (resolves when all triggered workers complete), `signalAndAwaitSync()` (blocking variant). `BulkSignalReceivedEvent` (`common/internal/event/`) carries the bulk payload + optional `signalId`. `SignalSettlementTracker` (`runtime/internal/engine/`, `@ApplicationScoped`) tracks per-signal expected/completed counts with `synchronized(state)` blocks on `SettlementState` instances. `signalId` (nullable UUID) threads through: `CaseContextChangedEvent` → `WorkerScheduleEvent` → EventLog metadata → `QuartzWorkerExecutionJob` → `WorkflowExecutionCompleted` → `WorkflowExecutionCompletedHandler.recordCompletion()`. On failure: `QuartzWorkerExecutionJob` → `WorkerRetryContext` → `QuartzRetryService` → `WorkerRetriesExhaustedEvent` → `WorkerRetriesExhaustedEventHandler.recordCompletion()`. Guard quarantine path also threads signalId. Settlement resolves when `expectedCount == completedCount AND fullyDispatched`. Only CapabilityTarget bindings count. With `SequentialPlanningStrategy`, settlement resolves after the first step only.

`ExecutionOrigin` (`api/model/event/`) — enum tagging EventLog entries with the origination path: `BINDING_DISPATCH`, `SIGNAL`, `SCHEDULE_TRIGGER`, `SUBCASE_COMPLETION`, `RECOVERY`. Set by each handler that creates EventLog entries. Available on `PlanExecutionContext.origin()` (nullable). Refs engine#618.

`RetryState` (`api/model/`) — record tracking every retry attempt: `attemptCount`, `List<RetryAttempt>` (timestamp, errorMessage, duration, succeeded), `firstAttemptTime`, `lastAttemptTime`. Available on `PlanExecutionContext.retryState()` (nullable — present only on retries) and `DeadLetterEntry.retryState()`. Populated by `QuartzRetryService` from `WORKER_EXECUTION_FAILED` EventLog entries. Refs engine#617.

## ContextBridge Protocol (engine#203)

`ContextBridge<T>` (`api/context/`) is the typed context protocol for worker input translation. `WorkerFunction<T, R>` carries `inputType(): Class<T>` and `outputType(): Class<R>` via the Reified Varargs Type Token pattern — `Worker.builder().<MyPojo>fn().returning(OutputPojo.class).apply((input, scope) -> ...)` captures runtime types despite erasure. Three-level DSL ceremony: Map→Map (no types, `Worker.builder().function(fn)`), T→Map (`fn().apply((input, scope) -> ...)`), T→R (`fn().returning(R.class).apply((input, scope) -> ...)`). Three built-in bridges: `MapBridge` (identity, `Map.class`), `JacksonPojoBridge<T>` (Jackson deserialisation to any POJO), `JsonNodeBridge` (raw `JsonNode`). `BridgeResolver` (`common/internal/context/`, `@ApplicationScoped`) resolves bridges via a priority chain: CaseDefinition default → CDI discovery → MapBridge fallback → JacksonPojoBridge auto-create. `BridgeResolver.resolveByType(Class<?>)` is the canonical resolution method; `resolveByTypeName(String)` delegates via `Class.forName()`. Pipeline integration: `WorkerScheduleEventHandler` calls `bridge.initialise()` + `bridge.serialise()` and writes `contextBridgeType` to EventLog metadata; `QuartzWorkerExecutionJob` reads `contextBridgeType` from metadata, calls `resolveByTypeName()` + `bridge.deserialise()` (or `initialise()` for live-view bridges), and calls `bridge.extractOutput()` for live-view output extraction. `WorkerFunctionHandler.execute()` and `WorkerExecutor.execute()` accept `Object inputData` (not `Map<String,Object>`). YAML support: `contextType:` on worker definitions creates typed `WorkerFunction.Sync<T>`. Refs engine#203.

**Serialisation boundary rule:** `bridge.serialise()` is called only at storage boundaries (EventLog, database persistence) and wire boundaries (Qhorus channels, HTTP). `bridge.deserialise()` is called only when reconstructing from stored or received data. Objects pass as POJOs between internal boundaries — no serialise→deserialise round-trip for same-JVM transfers.

## Typed Signals (engine#691)

`SignalType<T>` (`api/model/`) — platform-level typed signal declaration. Record with `name()` and `payloadType()`. `CaseDefinition` gains `List<SignalType<?>> signals` with builder `.signal(SignalType<?>)` and duplicate-name validation at build time. YAML: `signals:` array with `name` and `contextType` fields, parsed by `CaseDefinitionYamlMapper` via `Class.forName()`.

`CaseHubRuntime.signal(UUID caseId, SignalType<T> signalType, T payload)` — typed signal overload. Validates signal name and payload type against `CaseDefinition.signals` at `CaseHubRuntimeImpl` (fail-fast at API layer, before event publishing). Null payload rejected. When definition declares signals, undeclared names and mismatched payload types throw `SignalRejectedException` (`api/model/`). When no signals declared, all typed signals accepted (backward compat). Untyped `signal(caseId, path, value)` is never validated against declared signals.

`TypedSignalReceivedEvent` (`common/internal/event/`) — carries `caseId`, `signalName`, `payload` (POJO, not serialised), `payloadType` (Class<?>), `payloadTypeName` (String for EventLog), `tenancyId`. Published by `CaseHubReactor.signalTyped()` on `EventBusAddresses.TYPED_SIGNAL_RECEIVED`.

`SignalReceivedEventHandler.onTypedSignalReceived()` — writes payload to `.signals.{signalName}` in the working layer (namespaced to prevent collision). Serialises via `BridgeResolver.resolveByType()` for EventLog storage only (audit metadata: `signalTypeName`, `payloadType`, `typedPayload`). Publishes `CONTEXT_CHANGED`. Same concurrency model as untyped signals (per-(caseId, signalName) Vert.x local lock). Refs engine#691.

## SubCaseMapping (engine#690)

`SubCaseMapping` (`api/model/`) — sealed interface for SubCase input/output mappings. Permits `Expression(String expression)` (JQ strings, YAML path) and `Lambda(Function<CaseContext, Object> fn)` (Java DSL path). `SubCase.inputMapping()` and `outputMapping()` return `SubCaseMapping` (was `String`). `SubCase.Builder.inputMapping(String)` backward-compat overload creates `Expression`. New overload `inputMapping(SubCaseMapping)` for the typed path. Default input mapping is `SubCaseMapping.of(".")`.

`CaseContextChangedEventHandler.publishSubCaseSchedule()` dispatches on `SubCaseMapping` type: `Expression` evaluates via `evalJqAsMap()`, `Lambda` calls `fn.apply(caseContext)` directly. Input mapping failure logs error and skips dispatch (not silent `Map.of()`). `SubCaseScheduleEvent` carries `Object childInitialContext` (was `Map<String, Object>`) + nullable `contextBridgeType` + `bindingName`.

`SubCaseExecutionHandler` stores `bindingName` in `SUBCASE_STARTED` EventLog metadata alongside `outputMapping` (string, for Expression path). For Lambda mappings, only `bindingName` is stored (function is not serialisable).

`SubCaseCompletionService.applyOutputMapping()` dispatches on `SubCaseMapping` type. Lambda recovery: reads `bindingName` from EventLog metadata → looks up parent `CaseDefinition` via `CaseDefinitionRegistry` → finds `Binding` by name → gets `SubCaseTarget.subCase().outputMapping()`. Injects `CaseDefinitionRegistry`. Output mapping result must be `Map<String, Object>` or POJO convertible via Jackson `convertValue`. Refs engine#690.

## Hybrid Orchestration — Four-Tier Model (engine#490)

The engine supports orchestration at four tiers: **Tier 1 (Execution)** — `WorkerRuntime` for in-worker function composition, no durability; **Tier 2 (Simple plan)** — `SequentialPlanningStrategy` selects one binding at a time with natural durability from PlanItem state; **Tier 3 (Complex plan, future)** — `WorkflowPlanningStrategy` backed by Serverless Workflow for durable branching/compensation; **Tier 4 (Multi-case, future)** — blocks patterns for cross-case coordination. Tiers 2-3 share the `PlanningStrategy.select()` seam. `PlanningStrategyLoopControl` injects `Instance<PlanningStrategy>` and resolves by ID from `CaseDefinition.getPlanningStrategy()` (nullable String, defaults to `"default"`). `SequentialPlanningStrategy` (`blackboard/control/`, id=`"sequential"`) returns the first PENDING binding; halts on non-COMPLETED terminal states (FAULTED, REJECTED, OBSOLETE, CANCELLED). `CaseDefinitionYamlMapper` maps `planningStrategy:` and `sequence:` YAML keys — sequence uses two-pass resolution (build all workers, then resolve step references via `WorkerFunctions.sequence()`).

## Worker Outcome Handling

Workers declare semantic outcomes via `WorkerResult`: `Success` (default), `Declined(reason)`, `Failed(reason)`, `Expired(reason)`. The engine handles non-success outcomes via `OutcomePolicy` on the `Binding`:

- `REROUTE` (default): writes failure state to `_diagnostics.<bindingName>` in the working layer, marks PlanItem FAULTED, publishes CONTEXT_CHANGED. The binding re-fires with excluded agents filtered from candidates.
- `FAULT`: publishes `CASE_STATUS_CHANGED(FAULTED)` (case-level fault) + `WORKER_OUTCOME_RESOLVED(FAULT)` (PlanItem fault + stage autocomplete).

`Expired` outcomes originate from two sources: engine-internal worker timeout (`SyncAgentWorkerFunctionHandler` converts `TimeoutException` to `WorkerResult.expired()` — the SPI boundary never leaks exceptions) and Qhorus commitment expiration (future, qhorus#281). Both route through `OutcomePolicy.onExpired` using the same `handleSemanticFailure` path as `Declined` and `Failed`.

**Qhorus commitment bridge (engine#515):** `QhorusMessageSignalBridge` translates Qhorus DECLINE/FAILURE speech acts to `WorkerOutcome.Declined`/`WorkerOutcome.Failed` and publishes `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED`. The bridge resolves the original worker and binding from the EventLog via `correlationId` (= eventLogId from the original COMMAND). DONE/RESPONSE messages continue through the existing `channelMessage` signal path. Non-engine messages (non-numeric correlationId or EventLog not found) fall through to the signal path. `QhorusMessageSignalBridge` also routes `MessageType.STATUS` messages via `runtime.signal(caseId, "statusReport", payload)`. STATUS is informational (not commitment-resolving) — no correlationId lookup. Payload: `{from, content, timestamp}`. Milestone/sentry conditions evaluate `.statusReport.content` in JQ. Refs engine#661.

**`ConflictResolver`** (`api/model/`) — static utility for all conflict resolution strategies. Strategies: `LAST_WRITER_WINS` (default), `FIRST_WRITER_WINS`, `FAIL`, `DEEP_MERGE`. `DEEP_MERGE` recursively merges maps, preserving existing keys (`attempts`, `history`, `excludedAgents`) that incoming output does not overwrite. Used by both `WorkflowExecutionCompletedHandler` (worker output) and `PlanItemCompletionApplier` (humanTask output). `PlanItemCompletionApplier` looks up the binding's `conflictResolverStrategy` via `CaseDefinitionRegistry` instead of using bulk `setAll()`. Refs engine#508.

**`Binding.inputProjectionOverride`** — JQ expression overriding the capability's input projection for this specific binding. Threaded through `WorkerScheduleEvent.effectiveInputProjection()` and `tryProvision()`. Use for failure cascade scope reduction — same capability, narrower input. Refs engine#509.

**`Binding.producedKeys`** — `Set<String>` of context keys this binding is expected to produce. Populated from YAML `producedKeys:` array. Runtime audit: `WorkflowExecutionCompletedHandler` extracts actual produced keys from context diff into EventLog metadata alongside `contextChanges`. Refs engine#616.

**`Binding.contextWrite`** — `Map<String, Object>` applied to the case context before dispatch. Applied in `CaseContextChangedEventHandler.publishByTarget()` before the target-type switch. Prevents infinite condition re-evaluation loops in failure cascade bindings. Refs engine#511.

Failure state schema at `_diagnostics.<bindingName>`: `{status, attempts, history[], excludedAgents[]}`. Status values: `DECLINED`, `FAILED`, `EXPIRED`, `REROUTES_EXHAUSTED`, `COMPLETED`. Keyed by **binding name** (not capability name) — two bindings targeting the same capability maintain independent failure state. On successful completion after a reroute, `WorkflowExecutionCompletedHandler.recordSuccessOutcome()` updates status to `COMPLETED` and appends a history entry for the successful agent.

`WorkerOutcomeResolvedHandler` (blackboard, `blocking=true`) consumes `WORKER_OUTCOME_RESOLVED` and owns PlanItem lifecycle for non-success outcomes. `PlanItemCompletionHandler` gates on `WorkerOutcome.Success` and returns early for DECLINED/FAILED/EXPIRED — eliminates the fan-out race.

Agent exclusion: `CaseContextChangedEventHandler.publishWorkerSchedule()` filters excluded agents from `_diagnostics.<bindingName>.excludedAgents` before calling the routing strategy. All strategies benefit automatically. When all candidates are excluded, `handleAllCandidatesExhausted()` writes `REROUTES_EXHAUSTED` to `_diagnostics` and publishes `WORKER_OUTCOME_RESOLVED(EXHAUSTED)` — the blackboard faults the PlanItem and triggers stage autocomplete.

**GoalBasedCompletion generalization (engine#582):** `GoalKind` is an interface (not an enum) with `value(): String` and `terminalStatus(): CaseStatus`. `StandardGoalKind` enum provides built-in SUCCESS (→ COMPLETED) and FAILURE (→ FAULTED). `GoalKind.of(String, CaseStatus)` creates custom kinds (e.g. ESCALATED → FAULTED). `DefaultGoalKind` (package-private record) backs the factory — rejects CANCELLED as terminal status. `GoalBasedCompletion<K extends GoalKind>` stores a `LinkedHashMap<K, GoalExpression>` — insertion order is evaluation priority, first satisfied expression wins. `Goal.kind` is `String` (audit metadata, decoupled from completion). `Goal.Builder` has `kind(String)` and `kind(GoalKind)` overloads. `CaseStatusChanged` carries `String satisfiedGoalKind` (denormalized for transport). `GoalReachedEventHandler.evaluateCompletion()` iterates the ordered map — no hardcoded success/failure branches. YAML completion block is open: built-in kinds (success, failure) have implicit terminal status; custom kinds require explicit `status: COMPLETED|FAULTED`. `doneWhen` and goal kind entries are mutually exclusive. `"doneWhen"` is reserved as a kind name. `GoalExpression` is a sealed interface (`permits AllOfGoalExpression, AnyOfGoalExpression, SingleGoalExpression`) with three methods: `isSatisfiedBy(Set<String> reachedGoalNames)`, `goalNames()` (all leaf names), and `satisfiedGoalName(Set<String>)` (combined satisfaction check + name extraction — returns the representative goal name or null). `SingleGoalExpression(String goalName)` is the leaf node. `AllOfGoalExpression` and `AnyOfGoalExpression` hold `List<GoalExpression>` children and evaluate recursively. Backward-compatible factories: `GoalExpression.allOf(Goal...)` extracts names into `SingleGoalExpression` children. Composition factories: `GoalExpression.allOf(GoalExpression...)`, `GoalExpression.anyOf(GoalExpression...)`, `GoalExpression.goal(String)`. YAML `completion:` block supports nested composition — array elements can be strings (goal name → `SingleGoalExpression`) or objects (`allOf:` / `anyOf:` → recursive). Parse-time validation rejects unknown goal references and empty arrays. Refs engine#548.

Binding name threading: `WorkerScheduleEvent`, `WorkerScheduleEventHandler` (EventLog metadata), `QuartzWorkerExecutionJob`, `WorkflowExecutionCompleted`, `PlanItemCompletionHandler` all carry `bindingName` for precise PlanItem lookup. `findBindingByName()` replaces `findMatchingCapabilityBinding()` for direct binding resolution.

## InboundSignalBridge (casehub-engine-inbound)

Bridges inbound connector messages from `casehub-connectors` to typed case signals. Observes `@ObservesAsync InboundMessage`, evaluates JQ correlation and payload expressions, deserialises via `ContextBridge.deserialise()` (direct — no DataRef interception for external data), and delivers typed signals via `CaseHubRuntime.signal()`. Refs engine#692.

**`InboundSignalMapping`** (`api/model/`) — declared on `CaseDefinition`. Record: `signalName`, `connectorType`, `correlation` (ExpressionEvaluator), `payload` (ExpressionEvaluator), `correlationResolver` (nullable String, strategy ID). Builder String convenience methods auto-wrap to `JQExpressionEvaluator`. Build-time validation: `signalName` must reference a declared `SignalType` on the same definition. YAML: `inboundMappings:` block with `signal`, `connectorType`, `correlation`, `payload`, `correlationResolver`.

**`CaseCorrelationResolver`** (`api/spi/`) — `NamedStrategy`-based SPI for resolving correlation values to case UUIDs. `UuidCorrelationResolver` (id=`"uuid"`, `@DefaultBean`) parses direct UUIDs. Resolved via `EngineStrategyResolver`.

**Signal auto-activation:** `CaseHubRuntimeImpl.signal(UUID, SignalType<T>, T)` auto-loads cases from `CrossTenantCaseInstanceRepository` when not in `CaseInstanceCache`. Terminal cases throw `SignalRejectedException`. Non-existent cases throw `IllegalArgumentException`.

**Dependency:** `casehub-engine-inbound/pom.xml` depends on `casehub-connectors-core` (compile scope, zero casehubio transitive deps).

**DataRef interception scope:** `InboundSignalBridge` uses `bridge.deserialise()` directly, NOT `BridgeResolver.deserialise()` — connector data is external and must not trigger `$dataRef` interception. Engine pipeline (`QuartzWorkerExecutionJob`) and `ActionGateApprovedHandler` use `BridgeResolver.deserialise()` where DataRef interception IS active.

## DataRef Linked Data Reference Protocol

`DataRef<T>` (`api/context/`) — standard reference to externally-stored domain data. Record: `source` (resolver ID), `key` (opaque reference), `typeName` (String, not Class). JSON discriminator `$dataRef` — reserved top-level key in the ContextBridge protocol. `DataRef.isRef(JsonNode)` detects references. `DataRef.fromJson(JsonNode)` parses with structural validation. `DataRef.toJson(ObjectMapper)` serialises. Stores `typeName` as String — no `Class.forName()` on user-controlled data. Refs engine#740.

**`DataRefResolver`** (`api/spi/`) — `NamedStrategy`-based SPI for resolving references. `<T> T resolve(DataRef<T> ref)`. Blocking. CDI-discovered by `source` field. No `@DefaultBean` — unknown source fails fast.

**`DataRefRegistry`** (`common/internal/context/`) — CDI bean discovering resolvers and routing resolution by source ID.

**BridgeResolver integration — deferred resolution:** `BridgeResolver.initialise()` passes DataRef through (stored as reference in EventLog). `BridgeResolver.serialise()` passes DataRef through as reference JSON. `BridgeResolver.deserialise()` intercepts `$dataRef` and resolves via `DataRefRegistry` — runs only on Quartz worker threads (safe for blocking I/O). Known limitation: no caching across repeated resolutions of the same DataRef.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
