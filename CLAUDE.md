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
- [This repo's deep-dive](https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-engine.md)

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

- `casehub-engine-common/src/main/java/io/casehub/engine/internal/model/` — `CaseMetaModel`, `CaseInstance`, `SubCaseGroup`, `PlanItemStatus` (enum), `PlanItemRecord` (read model)
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/history/` — `EventLog`, `CaseHubEventType`, `EventStreamType`
- `casehub-engine-common/src/main/java/io/casehub/engine/spi/` — `CaseMetaModelRepository` (blocking), `ReactiveCaseMetaModelRepository` (Uni<>), `ReactiveCaseInstanceRepository`, `ReactiveEventLogRepository`, `SubCaseGroupRepository` (blocking), `ReactiveSubCaseGroupRepository` (Uni<>), `PlanItemStore` (blocking), `ReactivePlanItemStore` (Uni<>), `CaseInstanceRepository` (blocking), `EventLogRepository` (blocking), `CrossTenantCaseInstanceRepository` (blocking), `CrossTenantEventLogRepository` (blocking). Dual-stack convention: unqualified = blocking, `Reactive` prefix = Uni-based. Implementations: memory blocking is canonical (reactive delegates), JPA reactive is canonical (blocking awaits). **Delegate injection convention:** reactive in-memory repos must inject blocking delegates by SPI interface (e.g. `CaseInstanceRepository`), NOT by concrete class (e.g. `InMemoryCaseInstanceRepository`). Concrete-class injection prevents `@Alternative @Priority(1)` test wrappers from being substituted — two separate stores are created, causing silent tenant mismatches. Refs engine#663, GE-20260707-f3bece.
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/jq/` — `JQEvaluator` (@ApplicationScoped), `ValidationResult` — canonical jq evaluation; lives here so `scheduler-quartz` can inject it without circular dependency. See protocol `PP-20260522-jq-evaluation-canonical`. Follow-on platform extraction tracked in engine#317.
- `casehub-engine-common/src/main/java/io/casehub/engine/common/internal/executor/` — `WorkerExecutor` (SPI), `WorkerExecutionConfig` (@ApplicationScoped, default timeout), `RetryPolicies` (static utility, backoff computation), `RetryDecision` (sealed: Retry | Exhaust), `ExecutionMetadata` (lineage record for flow path)

Both `engine` and both persistence modules depend on `casehub-engine-common`. Neither persistence module depends on `engine`. `scheduler-quartz` also depends on `casehub-engine-common` directly.

**Test classpath note:** `casehub-engine-common` must be added to `quarkus.index-dependency` in any test `application.properties` that needs `JQEvaluator` discovered as a CDI bean — it is a library JAR, not a Quarkus application module.

**Production implementation:** `casehub-persistence-hibernate` (JPA/Panache, PostgreSQL)
**Test implementation:** `casehub-persistence-memory` (in-memory, thread-safe)

Modules needing in-memory tests add `casehub-persistence-memory` as a test dependency and activate the implementations via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` — no Docker required.

**`TenantAwareRepository` — RLS base class (persistence-hibernate only):** All JPA repositories in `casehub-persistence-hibernate` extend `TenantAwareRepository` (which extends `AbstractJpaRepository`). It provides two helpers that inject PostgreSQL session variables inside reactive transactions:
- `withTenantTransaction(work)` — sets `SET LOCAL "casehub.tenancy_id" = <currentPrincipal.tenancyId()>` before any SQL. Used by all tenant-scoped repos (EventLog, CaseInstance, CaseMetaModel, SubCaseGroup, PlanItem).
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS). Used by cross-tenant repos (`JpaReactiveCrossTenantEventLogRepository`, `JpaReactiveCrossTenantCaseInstanceRepository`) and recovery methods.

`SET LOCAL` resets automatically at transaction end — no cleanup needed. All reads AND writes go through `withTenantTransaction()` because `SET LOCAL` only applies inside an explicit PostgreSQL transaction (not in `withSession()` autocommit mode).

**`JpaReactiveCrossTenantEventLogRepository`** — separate class (not inside `JpaReactiveEventLogRepository`) implementing `ReactiveCrossTenantEventLogRepository`. `JpaReactiveEventLogRepository` implements `ReactiveEventLogRepository` only. Both extend `TenantAwareRepository`. Blocking counterparts `JpaCrossTenantEventLogRepository` and `JpaCrossTenantCaseInstanceRepository` await the reactive delegates.

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

**SPI contract tests:** Abstract contract tests live in `casehub-engine-common/src/test` (e.g. `PlanItemStoreContractTest`, `ReactivePlanItemStoreContractTest`). Modules that provide concrete implementations extend the abstract class. To access these test-only classes, add `casehub-engine-common` with `<type>test-jar</type>` and `<scope>test</scope>` — see `persistence-hibernate/pom.xml` and `persistence-memory/pom.xml` for the pattern.

**`JpaReactivePlanItemStore.updateStatus` flush requirement:** JPQL queries bypass the first-level cache. If `save()` and `updateStatus()` run in the same transaction, the entity from `save()` may not be in the database yet. `updateStatus()` calls `session.flush()` before issuing the JPQL UPDATE to ensure the entity is visible. Same pattern as the blocking `JpaPlanItemStore.updateStatus()`.

**casehub-ledger on test classpath:** If `casehub-ledger` is a transitive dependency (via `engine`), its JPA entities appear in `@QuarkusTest` contexts and require a datasource even in in-memory test suites. Fix: add `quarkus-jdbc-h2` + `casehub-ledger` as test dependencies, then in the module's test `application.properties`:
```properties
quarkus.datasource.db-kind=h2
quarkus.datasource.jdbc.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
quarkus.datasource.username=sa
quarkus.datasource.password=
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
quarkus.flyway.migrate-at-start=false
```
And add a `NoOpLedgerEntryRepository` (`@Alternative @Priority(1) @ApplicationScoped`) to the module's test sources — see `engine/src/test/java/io/casehub/engine/NoOpLedgerEntryRepository.java`. Applied to: `engine`, `casehub-blackboard`, `casehub-resilience`, `casehub-work-adapter`.

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

**`ReactiveCaseInstanceRepository` query methods:** `findByStatus(CaseStatus, tenancyId)`, `findAll(tenancyId)`, `findByNamespaceAndName(namespace, name, tenancyId)` — all return `Uni<List<CaseInstance>>`. Added as `default` methods (return `List.of()`) per SPI evolution protocol. Implemented in both `InMemoryReactiveCaseInstanceRepository` (stream filter) and `JpaReactiveCaseInstanceRepository` (JPQL with `join fetch` on `caseMetaModel`). Refs engine#523.

**`@CrossTenant` qualifier:** Cross-tenant SPIs (`ReactiveCrossTenantEventLogRepository`, `ReactiveCrossTenantCaseInstanceRepository`) are only injectable via `@CrossTenant`. `CrossTenantProducer` (in `runtime/internal/identity/`) produces both beans, guarded by `@EngineSystem SystemCurrentPrincipal`. Convention-based — CDI does not prevent unqualified injection; code review and the qualifier annotation are the enforcement mechanism. `SystemCurrentPrincipal` is `@ApplicationScoped @EngineSystem` (not `@DefaultBean`) — it does not conflict with `MockCurrentPrincipal`. All 6 engine injection sites (`PendingWorkRegistry`, `DefaultWorkerExecutionRecoveryService`, `QuartzWorkerExecutionJob`, `QuartzWorkerExecutionManager`, `MilestoneSLATimeoutJob`, `DeadLetterReplayService`) use `@CrossTenant`. See protocol PP-20260520-e6a5f0.

## CaseOutcomeObserver SPI

`CaseOutcomeObserver` — lifecycle hook called by the engine when a case reaches a terminal state (COMPLETED, FAULTED, CANCELLED). Implementations write CBR case entries to `CaseMemoryStore` or perform other outcome-based learning operations. Fired from `CaseStatusChangedHandler` on a worker thread (`blocking = true`). `@DefaultBean` no-op in `runtime/internal/worker/`. Consumer implementations are `@ApplicationScoped` and discovered automatically via CDI `Instance<CaseOutcomeObserver>`. Refs engine#477.

`ActionGatePolicy` — shared enum (`ALWAYS`, `THRESHOLD`, `CONDITIONAL`) in `api/spi/` for domain classifiers (AML, clinical, devtown, life) to reference instead of defining their own gate policy vocabulary. Refs engine#472.

## CaseContext Change Listeners

`CaseContext.onChange(key, listener)` / `onAnyChange(listener)` — per-key change listeners on the working layer. `ContextChangeEvent(key, oldValue, newValue)` with atomic old-value capture via `WritableLayerImpl.setPrev()`. Listeners fire after write lock release (no deadlock). Error isolation per listener. `engineSet()` and `applyDiff()` do NOT fire listeners. Default methods return `Subscription.NOOP`. Refs engine#619.

## CBR Retrieval Bridge

`CbrRetrievalService` (`runtime/internal/routing/`) — runtime bridge for Case-Based Reasoning retrieval. Evaluates `FeatureExtractor` (sealed: `JqFeatureExtractor` | `LambdaFeatureExtractor`) against the case context, builds a `CbrQuery`, calls `CbrCaseMemoryStore` (blocking), and maps results to engine-owned `RetrievedExperience` types. CBR failure never blocks case progression — the full chain is wrapped with `.onFailure().recoverWithItem(List.of())`. Injects `CbrCaseMemoryStore` (blocking) directly with `runSubscriptionOn(Infrastructure.getDefaultWorkerPool())` — bypasses `BlockingToReactiveCbrBridge` (`@DefaultBean`) which resolves its delegate at build time before `@Alternative` activation (engine#675, GE-20260706-abaddc). Refs engine#478.

`CbrConfig` on `CaseDefinition` — configures CBR retrieval per case type: `featureExtractor` (JQ string mode or lambda), `domain`, `caseType`, `topK`, `minSimilarity`, `weights`, `vectorWeight`, `timing`. YAML `cbr:` block in case definition schema. Domain falls back to `EpisodicMemoryConfig.domain()` when not explicitly set. `CbrRetrievalTiming` — `PER_EVALUATION` (default) or `CASE_LIFETIME`. Case-lifetime caching stores retrieval results in `CbrRetrievalService` per `caseId`, evicted on terminal case status via `CbrCacheEvictionHandler`. Max 1000 entries. YAML: `cbr: { timing: case-lifetime }`. Refs engine#671.

`RetrievedExperience` and `ExperiencePlanStep` (`api/spi/routing/`) — engine-owned types representing past case outcomes and their plan traces. Populated by `CbrRetrievalService` and threaded through routing contexts (`AgentRoutingContext.experiences()`, `ImplementationRoutingContext.experiences()`, `ProvisionContext`). All three routing context types carry `tenancyId` and `experiences` fields.

**Test infrastructure:** `casehub-neocortex-memory-cbr-inmem` (test scope in `runtime/pom.xml`) provides `InMemoryCbrCaseMemoryStore`. Requires `quarkus.index-dependency.cbr-inmem` and inclusion in `quarkus.arc.selected-alternatives` (see `application-memory.properties`). `RecordingCbrAgentRoutingStrategy` (`@Alternative @Priority(100)`, id `"cbr-recording"`) captures `AgentRoutingContext` for test assertions.

## JQ Expression Evaluation Surface

All JQ expressions (binding filters, `when` conditions, goals, milestones, `inputSchema`, `outputSchema`) evaluate against the **working layer** (`context.layer(ContextLayer.WORKING).asJsonNode()`), NOT the full layer document (`context.asJsonNode()`). YAML definitions use unqualified field paths (`.transaction`, `.entityResolution`) — the layer structure is an engine implementation detail.

`CaseHubRuntime.signal()` returns `CompletionStage<Void>` (engine#493). The CompletionStage resolves when the signal has been applied, the event log written, and CONTEXT_CHANGED dispatched — it does NOT guarantee goal evaluation completion. The 5-arg overload carries `triggerChannelId` and `triggerCorrelationId` (both nullable) for Qhorus causal lineage. CDI lifecycle events in all handlers (`SignalReceivedEventHandler`, `CaseStartedEventHandler`, `WorkflowExecutionCompletedHandler`) use fire-and-forget `.invoke()` — never `.chain()` — to prevent slow observers from blocking case state progression. Refs engine#231, engine#493.

## Worker Provisioner SPIs

Eight interfaces in `api/src/main/java/io/casehub/api/spi/` (four blocking + four reactive mirrors):

- `WorkerProvisioner` / `ReactiveWorkerProvisioner` — provision and terminate workers
- `WorkerStatusListener` / `ReactiveWorkerStatusListener` — lifecycle callbacks (started, completed, stalled)
- `CaseChannelProvider` / `ReactiveCaseChannelProvider` — open/close/post to backend-agnostic channels. **`postToChannel` takes 6 parameters**: `(CaseChannel, String from, String content, MessageType, String correlationId, String deadline)`. The 3-arg overload is a `default` delegating with three `null`s. `correlationId` and `deadline` are first-class SPI params (engine#343) — consumers no longer parse them from `CommandContent` JSON.
- `WorkerContextProvider` / `ReactiveWorkerContextProvider` — build startup context from ledger lineage
- `WorkerExecutionManager` — `getActiveCaseIds(String workerId): List<UUID>` is a `default` method added in engine#56 that returns Quartz job case UUIDs currently scheduled for the given worker; used by `casehub-engine-actor-state` to populate the active-cases slice of the actor state view

**Default implementations** in `engine/src/main/java/io/casehub/engine/internal/worker/`:
- `NoOpWorkerProvisioner`, `NoOpWorkerStatusListener`, `NoOpCaseChannelProvider`, `EmptyWorkerContextProvider`
- Four `@DefaultBean` reactive mirrors: `NoOpReactiveWorkerProvisioner`, `NoOpReactiveCaseChannelProvider`, `NoOpReactiveWorkerStatusListener`, `EmptyReactiveWorkerContextProvider`
- `NoOpCapabilityHealth` — returns `Ready` for all probes; deployments without `casehub-eidos-api` get transparent no-op
- `NoOpVocabularyRegistry` — exact-match-only semantics; when `casehub-eidos-runtime` is on the classpath, `CdiVocabularyRegistry` displaces this automatically. Injected by `AgentCandidateFactory` for vocabulary-grounded subsumption matching. Refs engine#609.

Ten are `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) — they yield automatically to any consumer-provided implementation without requiring `selected-alternatives` configuration. See protocol `PP-20260514-engine-spi-noops-defaultbean`.

**Default routing strategies** in `runtime/src/main/java/io/casehub/engine/internal/routing/`:
- `FirstSupportedRoutingStrategy` — `@DefaultBean @ApplicationScoped`; iterates `@WorkerBackend` managers by `@Priority` (descending), returns first where both `supports()` and `canExecute()` return true (engine#461, engine#587)

**Composite execution manager** in `runtime/src/main/java/io/casehub/engine/internal/worker/`:
- `CompositeWorkerExecutionManager` — `@ApplicationScoped` (not `@DefaultBean`); routes `submit()` via `WorkerExecutionRoutingStrategy`, aggregates `getActiveWorkCount`/`getActiveCaseIds`/`canExecute` across `@WorkerBackend` backends (engine#461). When no backends are discovered, `submit()` throws `ProvisioningException`. `canExecute(WorkerFunction)` delegates to backends — returns `true` if any backend can execute the function type (engine#587).

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
| `WorkerContextProvider.buildContext` + `WorkerExecutionContext.set` | `QuartzWorkerExecutionJob` | Immediately before worker function — sets thread-local with channels |
| `ReactiveWorkerProvisioner.provision` (→ `ProvisionResult`) + `CaseLifecycleEvent("WorkerStarted")` | `CaseContextChangedEventHandler.tryProvision` | Successful external provisioning — fires `WorkerStarted` (commandType `ProvisionWorker`) after provisioner returns |
| `WorkerProvisioner.provision` | `CaseContextChangedEventHandler.tryProvision` | No pre-defined workers match capability |

`WorkerProvisioner.provision()` is called when a capability binding fires and no pre-defined workers match. `ProvisioningException` is caught and logged; the binding stays eligible for the next context-change tick. The no-op default returns empty capabilities, so it is never called unless a real provisioner is wired in.

**AgentDescriptor association (engine#543):** `AgentDescriptor` is stored on `CaseDefinition` (not Worker). `CaseDefinition.agentDescriptorFor(workerName)` returns `Optional<AgentDescriptor>`. `AgentCandidateFactory.buildCandidates()` takes `CaseDefinition` as a parameter and looks up descriptors via this method. Workers are pure foundation-tier records with no eidos dependency.

**`AgentCandidateFactory` subsumption matching (engine#609):** `AgentCandidateFactory` is `@ApplicationScoped` (not a static utility), injecting `VocabularyRegistry` for vocabulary-grounded capability matching. Two-tier matching: exact string match on `Worker.capabilityNames()` first (fast path), then `CapabilityResolver.resolve()` when the worker has an `AgentDescriptor` with grounded capabilities (subsumption fallback). Workers without descriptors get exact-only matching. `MatchDegree` is surfaced on `AgentCandidate` via the `matchDegree` field (nullable) — propagated from `CandidateMatchingStrategy.match()` which returns `MatchedWorker` (Worker + MatchDegree). `ExactMatchStrategy` returns `MatchDegree.Exact`; `SubsumptionMatchStrategy` propagates the degree from `CapabilityResolver`. Refs engine#638. Three auxiliary matching points (`SchedulerService`, `WorkflowExecutionCompletedHandler`, `PlanningStrategyLoopControl`) remain exact-only.

**`WorkerProvisioner.provision()` returns `ProvisionResult`** (blocking) / `Uni<ProvisionResult>` (reactive). `ProvisionResult(UUID causedByEntryId)` carries the ledger entry ID of the Qhorus COMMAND that triggered provisioning for causal audit linkage. Provisioner implementations that cannot resolve a causal entry return `ProvisionResult.empty()`. No-op defaults still throw `ProvisioningException` on `provision()`. `ProvisionResult` lives in `api/src/main/java/io/casehub/api/spi/ProvisionResult.java`. See protocol `PP-20260529-bcbbb5`. Claudony wiring tracked in claudony#140.

**`ProvisionContext` fields:** `caseId`, `tenancyId`, `taskType`, `workerContext` (nullable), `propagationContext`, `triggerChannelId` (nullable String), `triggerCorrelationId` (nullable String). `tenancyId` identifies the tenant owning the case — populated from `CaseInstance.tenancyId` at the construction site in `tryProvision()`. Provisioner implementations use this to resolve tenant-specific endpoints via `EndpointRegistry`. The trigger fields carry the Qhorus channel ID and correlation ID of the COMMAND that caused provisioning — allowing provisioner implementations to establish causal linkage in the ledger. Engine-internal call sites pass `null` for both until engine#231 threads Qhorus trigger context through the CaseFile-update API.

**`tryProvision()` capabilities gate removed (engine#531):** `CaseContextChangedEventHandler.tryProvision()` no longer gates on `getCapabilities().contains(capability)`. The capabilities set is still passed to `provision()` — the provisioner decides whether it can handle the request based on full context (capabilities + `ProvisionContext` with `tenancyId`).

`WorkerExecutionContext.current()` returns the active `WorkerContext` (including `channels`) inside a worker's function body. Cleared in a `finally` block after the function returns.

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
- `ActionRiskClassifier` — blocking; consumer implementations use this
- `ReactiveActionRiskClassifier` — primary (called by engine); `ChainedReactiveActionRiskClassifier` bridges blocking → reactive
- `RiskDecision` — sealed: `Autonomous` | `GateRequired(reason, reversible, candidateGroups, expiresIn, scope)`. `candidateGroups` is `CandidateSetStrategy` (not `List<String>`) — supports dynamic evaluation via `StaticSetStrategy.of(...)`, `ExpressionSetStrategy`, or named CDI strategies resolved via `StrategyResolver`. Refs engine#634.
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
`ChainedReactiveActionRiskClassifier` (`@ApplicationScoped`, NOT `@DefaultBean`) discovers all `@RiskClassifier` beans and applies "most restrictive wins" (fewest candidateGroups beats more; GateRequired beats Autonomous). Classifier failure → fail-safe `GateRequired`. Blocking classifiers offloaded to worker pool via `runSubscriptionOn(workerPool)`.

**Gate mechanism:** When `GateRequired` fires:
1. `WorkflowExecutionCompletedHandler` stores `PendingActionGate` in-memory on `CaseInstance` (**not persisted by JPA in v1 — restart loses the gate**; tracked as engine#433)
2. Publishes `ActionGateScheduleEvent` (carrying `resolvedCandidateGroups` — the evaluated `Set<String>` from `CandidateSetStrategy`) → `ActionGateWorkItemHandler` (work-adapter) creates a WorkItem
3. Human approves/rejects via work inbox
4. `ActionGateCompletionApplier` (work-adapter) publishes `ActionGateApprovedEvent` or `ActionGateRejectedEvent`
5. `ActionGateApprovedHandler` (runtime) re-fires `WorkflowExecutionCompleted` with `outcome=Success(null)` — normal completion path applies deferred output
6. `ActionGateRejectedHandler`/`ActionGateExpiredHandler` write context signals (`actionGateRejected`, `actionGateExpired`), fire `CONTEXT_CHANGED`, publish `ACTION_GATE_WORKER_FAULTED` for blackboard PlanItem fault

**Binding guard requirement:** Case definitions with consequential workers MUST include rejection handler bindings. The binding trigger condition must also exclude gate signal paths to prevent re-scheduling while a gate is pending:
```java
.on(new ContextChangeTrigger(".result == null and .actionGateRejected == null and .actionGateApproved == null"))
```

**Startup warning:** `ActionGateDeploymentHealthCheck` warns if `@RiskClassifier` classifiers are registered but `casehub-engine-work-adapter` is absent (gate WorkItem would never be created).

**New event bus addresses** (in `EventBusAddresses`): `ACTION_GATE_SCHEDULE`, `ACTION_GATE_APPROVED`, `ACTION_GATE_REJECTED`, `ACTION_GATE_EXPIRED`, `ACTION_GATE_CANCELLED`, `ACTION_GATE_WORKER_FAULTED` (distinct from `WORKER_RETRIES_EXHAUSTED` — gate faults must not fault the CaseInstance).

See design spec: `docs/specs/2026-06-05-action-risk-classifier-design.md`. Consumer exploration issues: life#20, devtown#56, aml#42, clinical#47, openclaw#6.

## ImplementationRoutingStrategy SPI

Selects which binding(s) handle a capability when multiple bindings target the same capability. Symmetric to `AgentRoutingStrategy` (which selects which worker instance handles a task). Package: `io.casehub.api.spi.routing`. Refs engine#476.

**Pipeline:** Binding eligibility → Stage gating → **ImplementationRouting** → PlanningStrategy → **AgentRouting** → Worker scheduling.

**Sealed result:** `ImplementationSelection` — `Selected(List<String> bindingNames)` | `RunAll()` | `RunNone()`. `Selected` enforces non-empty via constructor validation.

**Default:** `NoOpImplementationRoutingStrategy` (`@DefaultBean @ApplicationScoped` in `runtime/internal/routing/`) returns `RunAll`.

**Integration:** `PlanningStrategyLoopControl.applyImplementationRouting()` runs at step 3.5 — after `stageLifecycleEvaluator.evaluate()`, before `planningStrategy.select()`. Routing filters bindings before PlanItem creation (no create-then-cancel).

## Universal Routing Strategy Architecture

`NamedStrategy` marker interface (`io.casehub.platform.api.routing`) and `StrategyResolver` CDI bean provide a consistent named-strategy convention across the platform. All per-case-selectable routing strategies extend `NamedStrategy`, declare `id()`, and are resolved by `StrategyResolver`. Resolution: YAML-specified ID → `@DefaultBean` fallback.

**CandidateSetStrategy** (`api/spi/routing/`) — replaces sealed `ListEvaluator`. Returns `Uni<Set<String>>`. Two creation modes: value objects (`StaticSetStrategy`, `ExpressionSetStrategy`, `JqCandidateSetStrategy`) for YAML mapper/builder, and named CDI beans for `StrategyResolver` lookup. `HumanTaskTarget` stores `CandidateSetSpec` (sealed: `Inline(CandidateSetStrategy)` | `Named(strategyId, config)`).

**CandidateMatchingStrategy** (`api/spi/routing/`) — replaces hardcoded `AgentCandidateFactory` matching. Returns `Uni<List<MatchedWorker>>` (`MatchedWorker` record pairs `Worker` with `MatchDegree`; `MatchedWorker.exact()` factory for exact-match sites). Built-in: `ExactMatchStrategy` (id=`"exact"`), `SubsumptionMatchStrategy` (id=`"subsumption"`, `@DefaultBean`). `AgentCandidateFactory` delegates matching, retains health probing and candidate construction.

**CaseDefinition** gains `agentRouting`, `implementationRouting`, `candidateMatching` (all nullable String strategy IDs). Resolved at dispatch time via `StrategyResolver`.

`CaseDefinition` gains `types: Set<Path>` and `labels: Set<Path>` (both `io.casehub.platform.api.path.Path`, empty by default, unmodifiable via `Set.copyOf()`). `types` = behavioral contracts (implements semantics); `labels` = operational classification. Parsed from YAML via `Path.parse()` in `CaseDefinitionYamlMapper`. `CaseDefinitionRegistry` gains `findByType(Path)` and `findByLabel(Path)` default methods — ancestor matching via `Path.isAncestorOf()`. YAML schema `tags` (dead `type: object`) and `metadata` (dead) removed. Refs engine#652.

**EngineStrategyResolver** (`runtime/internal/routing/`) — `@Alternative @Priority(1)` resolver using per-domain `Instance<>` injection (Quarkus ARC workaround — `Instance<NamedStrategy>` does not discover sub-interface beans). Detects `@DefaultBean` strategies via `InjectableBean.isDefaultBean()` — YAML-specified IDs take precedence over defaults. Adding new strategy SPI types requires updating this resolver's constructor. Refs engine#634, engine#641, GE-20260704-d6aacc.

## Repeatable Stage

Stage gains `repeatable` (final boolean, builder-only) and `instanceIndex` (AtomicInteger, 0-based). When a repeatable stage autocompletes, `StageAutocompleteEvaluator` calls `resetForRepetition()` — CAS COMPLETED→PENDING, clears `containedPlanItemIds`/`requiredItemIds`/`containedMilestoneIds`, increments `instanceIndex`. Binding names persist across resets (design-time declarations).

**Event records:** `StageCompletedEvent(caseId, stage, instanceIndex)` and `StageActivatedEvent(caseId, stage, instanceIndex)` carry an explicit `instanceIndex` snapshot — use the field, not `stage.getInstanceIndex()` which may have advanced.

**V1 constraint:** Repeatable stages must not contain nested stages or milestones. Runtime enforcement in `StageAutocompleteEvaluator` — logs warning and skips reset.

**Fan-out race:** `WORKER_EXECUTION_FINISHED` fan-out means auto-registration and `resetForRepetition()` may interleave. Self-healing — at worst one cycle skipped. Refs engine#482.

**Stage-PlanItem auto-registration:** `PlanningStrategyLoopControl` auto-registers newly created PlanItems with their owning stage's `containedPlanItemIds` and `requiredItemIds` via `registerWithOwningStages()`. This makes stage autocomplete work in production (previously test-only). Refs engine#497.

**Outcomes cleanup:** `StageResetOutcomesCleaner` (blackboard) consumes `STAGE_ACTIVATED` and clears `_outcomes` entries for the stage's `getContainedBindingNames()` when `instanceIndex > 0`. Without this, excluded agents from iteration N carry over to iteration N+1. Refs engine#517.

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

## casehub-blackboard Module

Optional CMMN/Blackboard orchestration layer. Activated via CDI `@Alternative @Priority(10)` when on the classpath.

**Build and test:**
```bash
mvn install -DskipTests -q          # install deps to local repo first
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl casehub-blackboard
```

**Test conventions:**
- `@QuarkusTest` classes MUST be named `*Test.java` — never `*IT.java`
  (`*IT` is picked up by failsafe instead of surefire; produces `Tests run: 0` with no error)
- Uses `casehub-persistence-memory` as a test dependency for in-memory SPI implementations
- `src/test/resources/application.properties` sets `quarkus.http.test-port=0`, indexes the
  persistence-memory module via `quarkus.index-dependency`, and activates the in-memory
  alternatives via `quarkus.arc.selected-alternatives` (including `InMemorySubCaseGroupRepository`, `InMemoryReactiveSubCaseGroupRepository`)
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
- `PlanItemCompletionHandler` — marks PlanItems COMPLETED on `WORKER_EXECUTION_FINISHED` and `SUBCASE_EXECUTION_COMPLETED`; delegates stage autocomplete to `StageAutocompleteEvaluator`
- `WorkerRetryExhaustionHandler` — marks CapabilityTarget PlanItems FAULTED on `WORKER_RETRIES_EXHAUSTED` (both guard-blocked and Quartz-exhausted paths); delegates stage autocomplete to `StageAutocompleteEvaluator`. Refs engine#331.
- `StageAutocompleteEvaluator` — evaluates stage autocomplete after any PlanItem terminal transition; fires `STAGE_COMPLETED` when all required items are terminal (COMPLETED, REJECTED, FAULTED, or CANCELLED). See ADR-0002 for the semantic decision on FAULTED/REJECTED triggering autocomplete.
- `SubCaseExecutionHandler` — consumes `SUBCASE_SCHEDULE` events. Detects self-reference (parent definition == child SubCase identity) and enforces bounded recursion via `SubCase.maxRecursionDepth()` (int, default 0 = hard block). Depth is computed by walking the `parentCaseId` chain via `CaseInstanceCache`, counting ALL same-definition ancestors (total counting, not consecutive — prevents trampoline bypass via A→B→A chains). Short-circuits at `maxRecursionDepth`. If `depth >= maxRecursionDepth` → faults the PlanItem. The cache walk relies on `CaseInstanceCacheImpl` having no eviction (bare `ConcurrentHashMap`, no `remove()` method) — all ancestors in a recursive chain are WAITING and remain cached. **Single-node assumption:** `CaseInstanceCache` is per-JVM. Clustering would require a distributed cache or repository query. **Known limitation:** mutual recursion (A→B→A cycles) is unbounded — B spawning A bypasses the self-reference check. Refs engine#573.
- `SubCaseCompletionService` — handles grouped sub-case completion (M-of-N threshold). Fires `Event<SubCaseGroupLifecycleEvent>.fireAsync()` for every non-null `GroupStatus` transition (IN_PROGRESS, COMPLETED, REJECTED). Observers (monitoring, audit, Claudony dashboard) subscribe without coupling to the engine. Refs engine#249.

**PlanItem CAS transitions:** `PlanItem.tryMarkRunning()` is a CAS-based transition (PENDING→RUNNING, AVAILABLE→RUNNING) that returns `true` on success, `false` when already RUNNING or terminal. Used by handlers to avoid duplicate CONTEXT_CHANGED fan-out. Per-case serialization of CONTEXT_CHANGED is tracked in engine#646. Refs engine#636.

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

## casehub-work-adapter Module

Activated by adding `casehub-engine-work-adapter` to the consumer's classpath (transitively brings `casehub-engine-blackboard`). Required for any runtime that uses `humanTask` YAML bindings — without it, `HumanTaskScheduleEvent` is published but never handled and WorkItems are never created.

**Dependency:** Production depends on `casehub-work-api` (SPI only), not `casehub-work` runtime. All WorkItem operations go through `WorkItemCreator` (create, find) and `WorkItemLifecycle` (cancel, complete). The runtime (`casehub-work`) is present at test scope only. Refs engine#578.

**YAML DSL:** `humanTask` is a first-class binding target type in `CaseDefinition.yaml` (alongside `capability` and `subCase`). `CaseDefinitionYamlMapper` converts it to `HumanTaskTarget`. Inline mode requires `title`; template mode requires `templateRef`. Both modes support `outputMapping`, `inputMapping`, `candidateGroups`, `candidateUsers`, `expiresIn`, `scope` (hierarchical path for SLA preference resolution, e.g. `"casehubio/devtown/pr-review"`), `claimDeadlineHours` (integer — business hours to claim before escalation, wired to `WorkItemCreateRequest.claimDeadlineBusinessHours`), and `outcomes` (`Set<String>` of valid outcome names, e.g. `APPROVED`, `REJECTED` — propagated to `WorkItemCreateRequest.permittedOutcomes` in both modes; enforced at completion by casehub-work). Refs engine#325, engine#512.

Two-way bridge between casehub-work and CaseHub plan items:
- **Inbound** (`WorkItemLifecycleAdapter`) — observes `WorkItemEvent` (api interface) directly and translates terminal events to `PlanItem` transitions via `status.isTerminal()` guard (no explicit enumeration). Uses `WorkItemEvent` typed accessors (`callerRef()`, `workItemId()`, `candidateGroups()`, `resolution()`) — never `source()`. Evaluates `outputMapping` against the `WorkItemRef.resolution()` JSON, and fires `CONTEXT_CHANGED` for engine re-evaluation. Terminal status mapping: COMPLETED → `markCompleted()`; REJECTED → `markRejected()` (intentional human refusal — PlanItem must be DELEGATED); FAULTED → `markFaulted()` (system failure) + fires `PlanItemFaultedEvent`; EXPIRED → `markFaulted()` (deadline failure) + fires `PlanItemFaultedEvent`; OBSOLETE → `markObsolete()` (context changed, work irrelevant) + fires `PlanItemObsoleteEvent`; CANCELLED → `markCancelled()`. Also observes `WorkItemGroupLifecycleEvent` for M-of-N SpawnGroup outcomes (COMPLETED → `PlanItem.markCompleted()`; REJECTED → `PlanItem.markRejected()`). ESCALATED is terminal — all SLA breach policy branches exhausted. The adapter writes a `workItemEscalated` signal to the case context (`{workItemId, newGroups, bindingName}`) so case definitions can react via `contextChange(".workItemEscalated")` bindings. The PlanItem stays DELEGATED. SLA breach policies that re-route the WorkItem to new groups (the `EscalateTo` decision) do not set ESCALATED — the WorkItem stays PENDING, so the adapter's terminal filter skips it. Refs engine#338, engine#400, engine#539, engine#579.
- **Outbound** (`HumanTaskScheduleHandler`) — consumes `HUMAN_TASK_SCHEDULE` event bus messages, looks up the `PlanItem` by binding name, then:
  - **Inline mode** (`HumanTaskTarget.inline()`): creates a WorkItem via `WorkItemCreator.create(request)`, then `planItemStore.save(DELEGATED)`, then `item.markDelegated()`
  - **Template mode** (`HumanTaskTarget.template(ref)`): parses `ref` as UUID; invalid UUID → warn + leave PlanItem PENDING; builds `WorkItemCreateRequest` with `templateId` set and all overrides (`scope`, `candidateGroups`, `candidateUsers`, `payload`, `permittedOutcomes`) on the builder; calls `WorkItemCreator.create(request)` — the SPI adapter routes `templateId != null` to `WorkItemTemplateService.createFromTemplate()` internally, using request-wins merge semantics (template defaults fill in what the request doesn't specify). Template payload uses deep JSON merge with inputData (template keys are preserved alongside input keys). Exception from the SPI → warn + leave PlanItem PENDING. On success → `planItemStore.save(DELEGATED)`, `item.markDelegated()`

All three steps in each mode are inside `@Transactional` — if WorkItem creation fails the transaction rolls back and `markDelegated()` is never called (PlanItem stays PENDING). `JpaPlanItemStore` + `WorkAdapterPlanItemEntity` live in `work-adapter` (blocking JPA, shares casehub-work datasource). `InMemoryPlanItemStore` (in `casehub-engine-persistence-memory`) must be in `selected-alternatives` for work-adapter tests.

`@ConsumeEvent` handlers that call `@Transactional` services must use `blocking = true` — without it, the transaction silently does not commit on the Vert.x IO thread (the WorkItem is never created, no error is thrown).

See protocols `PP-20260517-cbf836` (PlanItem must not be marked RUNNING until all resolution steps succeed), `PP-20260517-0093f8` (inputMapping output must reach WorkItem payload in all handler modes), and `PP-20260518-78f8b7` (PlanItemStore.save() must be called from a blocking @Transactional context).

**Test setup** (when depending on `casehub-work` full module):
- Add `casehub-work-persistence-memory` test dep — provides `InMemoryWorkItemStore @Alternative @Priority(1)`
- Add `quarkus-jdbc-h2` test dep — casehub-work JPA entities require a datasource even in tests
- Add `quarkus.arc.exclude-types=io.casehub.work.runtime.repository.jpa.JpaWorkItemStore,io.casehub.work.runtime.repository.jpa.JpaWorkItemTemplateStore` to `application.properties` — `@Alternative @Priority(1)` from an external jar does NOT automatically override a non-alternative `@ApplicationScoped` bean in Quarkus ARC 3.x; excluding the JPA stores is required for in-memory stores to resolve correctly
- Use `quarkus.arc.selected-alternatives` to activate `casehub-persistence-memory` repos AND `io.casehub.work.memory.InMemoryWorkItemStore` AND `io.casehub.work.memory.InMemoryWorkItemTemplateStore` — omitting it causes boot failure: `Unsatisfied dependency for ReactiveSubCaseGroupRepository`. Template-mode tests that use `persistTemplate()` must call `templateStore.put()` (not Panache `persist()`) to write to the in-memory store that the handler reads from (engine#576)
- Add `@Alternative @Priority(1)` static inner class stub for `WorkloadProvider` — casehub-work ships `JpaWorkloadProvider` which would query the database for work counts; a zero-returning stub isolates tests from DB queries. (engine#337 removed `CasehubWorkloadProvider` — no CDI ambiguity exists, but the stub is still good test hygiene)
- Set `quarkus.quartz.store-type=ram` and `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`
- `QuarkusTestProfile.getEnabledAlternatives()` **replaces** (not appends to) `quarkus.arc.selected-alternatives` — any profile using this method must re-declare all globally required alternatives, including both blocking and reactive persistence-memory repos (reactive delegates inject the blocking canonical by concrete type) and `InMemoryWorkItemStore`

`callerRef` format: `case:{caseId}/pi:{planItemId}` — use `CallerRef.encode()` / `CallerRef.parse()`.

## casehub-engine-actor-state Module

Optional module providing a unified actor workload view (`GET /actors/{actorId}/state`). Aggregates active cases (via `WorkerExecutionManager.getActiveCaseIds`), open WorkItems (via `casehub-work-api`), and open Qhorus obligations (via `CommitmentStore.findOpenByObligor`) using the `ActorStateContributor` SPI from `casehub-platform-api`. Both blocking (`ActorStateAggregator`) and reactive (`ReactiveActorStateAggregator`) aggregation paths are provided with parity enforced by `ActorStateParityTest`. Activated by adding `casehub-engine-actor-state` to the consumer's classpath.

## casehub-engine-flow Module

Optional module enabling `Worker(Workflow)` to dispatch casehub workers from within Serverless Workflow steps and await their results. Activated by adding `casehub-engine-flow` to the consumer's classpath.

`FlowWorkerFunction` (record, implements `WorkerFunction`) lives here — the serverlessworkflow SDK never leaves this module. `FlowWorkerFunctionProvider` (`@ApplicationScoped`, implements `WorkerFunctionProvider`) handles YAML `do:` block construction — receives raw `JsonNode`, deserializes to `Workflow` via `WorkflowReader`. `FlowWorkerFunctionHandler` (`@ApplicationScoped`, implements `WorkerFunctionHandler`) executes workflows using `WorkflowApplication` singleton and `FlowExecutionRegistry`, running on `@VirtualThreads ExecutorService`. `CasehubCallableTaskBuilder implements CallableTaskBuilder<CallFunction>` (registered via Java SPI) handles `call: casehub:dispatch` YAML steps. Note: `CallFunction` and `FunctionArguments` are in `io.serverlessworkflow.api.types` — not the `.func` experimental subpackage.

## Worker Execution Architecture

`WorkerExecutor` (`common/internal/executor/`) abstracts how to run a worker function — independent of any scheduler. `DefaultWorkerExecutor` (`runtime/internal/executor/`) is a composite over `WorkerFunctionHandler` instances — it iterates `Instance<WorkerFunctionHandler>`, finds the first handler that `supports()` the function, delegates execution, and applies output schema evaluation as `.map()` post-processing. `SyncAgentWorkerFunctionHandler` (`runtime`) handles `Sync` and `AgentWorkerFunction` on `@VirtualThreads ExecutorService` with timeout enforcement. `FlowWorkerFunctionHandler` (`flow`) handles `FlowWorkerFunction` — see casehub-engine-flow Module. `WorkerFunctionHandler` (`common/internal/executor/`) is the engine-internal SPI; `outputSchema` is deliberately absent from the handler interface (cross-cutting concern owned by the composite executor). `WorkerFunctionProvider` and `WorkerFunctionProviderRegistry` (`api/spi/`) delegate YAML worker function construction to modules — the flow module registers `FlowWorkerFunctionProvider` for `do:` blocks; Agent and Sync construction stays inline in `CaseDefinitionYamlMapper`. Worker/Capability/WorkerFunction/WorkerResult/WorkerOutcome are from `io.casehub.worker.api` (foundation tier); `WorkerFunction` is a marker interface with no `execute()` method. `Worker` carries `Set<String> capabilityNames` (not `Capability` instances) — workers declare support by name; the engine resolves authoritative `Capability` instances from `CaseDefinition.getCapabilities()` via the binding's `CapabilityTarget`. Refs engine#591. `WorkerFunction.None` (engine#586) models external workers with no in-process function — `WorkerFunction.NONE` is the singleton constant. `Worker.Builder.noFunction()` is the convenience method. `CaseDefinitionYamlMapper` uses `NONE` for workers without an agent block or flow provider. `WorkerExecutionManager.canExecute(WorkerFunction)` is a `default true` method (engine#587) — Quartz overrides with positive handler delegation (iterates `WorkerFunctionHandler` instances, returns `true` only when a handler supports the function). `WorkerRecoveryCoordinator` (`runtime/internal/engine/recovery/`) initiates recovery at `@Priority(22)` via `WorkerExecutionRecoveryService.recoverPendingScheduledWorkers()` with a configurable timeout (`casehub.engine.recovery.timeout`, default 60s). Tracks `RecoveryStatus` (`PENDING`/`COMPLETED`/`FAILED`). `WorkerRecoveryHealthCheck` (`@Readiness`) reports the status at `/q/health/ready`. `QuartzWorkerExecutionManager.onStart(@Priority(20))` retains only Quartz job listener registration. Refs engine#593. ExecutionPolicy/RetryPolicy/BackoffStrategy are from `io.casehub.platform.api.governance`.

`QuartzWorkerExecutionJob` is a thin fire-and-forget Quartz adapter: resolves context (EventLog, CaseInstance, Worker, Capability), delegates to `WorkerExecutor.execute()`, and subscribes with success/failure callbacks. Success publishes `WORKER_EXECUTION_FINISHED`; failure routes to `QuartzRetryService`.

`QuartzRetryService` (`scheduler-quartz`) owns failure handling: persists `WORKER_EXECUTION_FAILED` event log, resolves retry policy from the worker's `ExecutionPolicy`, counts prior failures, and uses `RetryPolicies.evaluate()` to decide retry vs exhaust. On retry, reschedules via `QuartzWorkerSchedulerService`; on exhaust, publishes `WORKER_RETRIES_EXHAUSTED`.

`RetryPolicies` (`common/internal/executor/`) is a pure static utility for backoff computation — no CDI, no dependencies. `RetryDecision` is a sealed type: `Retry(Duration delay)` or `Exhaust(String reason)`. Moved from `QuartzWorkerExecutionJobListener` so any scheduler adapter can reuse the same backoff logic.

`WorkerExecutionConfig` (`common/internal/executor/`) provides the default worker timeout (`casehub.engine.worker.default-timeout-ms`, default 60000ms). Per-worker overrides come from `ExecutionPolicy.timeoutMs()`.

**WorkerRuntime — Tier 1 orchestration surface (engine#490, #485):** `WorkerRuntime` (`api/engine/`) is a per-invocation handle letting workers call other functions and spawn sub-cases. Six methods: `caseId()`, `execute(WorkerFunction, Map)`, `execute(String workerName, Map)`, `spawnCase(String, Map)`, `awaitCase(UUID, Duration)`, `spawnAndAwaitCase(String, Map, Duration)`. Available via `WorkerExecutionContext.currentRuntime()` — set by `SyncAgentWorkerFunctionHandler` alongside `WorkerContext`. `WorkerRuntimeFactory` (`runtime/internal/executor/`, `@ApplicationScoped`) creates per-invocation `DefaultWorkerRuntime` instances. `execute()` never throws — runtime exceptions wrapped in `WorkerResult.failed()`. Supports `Sync` and `AgentWorkerFunction` only; `FlowWorkerFunction` returns failed (belongs at Tier 3). Stack semantics: saves/restores parent `WorkerExecutionContext` for nested orchestration. `spawnCase()`/`awaitCase()` are TODO stubs — full implementation deferred. `WorkerFunctions` (`api/model/`) provides `sequence(WorkerFunction...)` combinator and `merge(Map, Map)` utility.

**CaseCompletionTracker** (`runtime/internal/engine/`, `@ApplicationScoped`) — tracks in-flight cases and throws `CaseTerminatedException` (runtime exception in `common/internal/exception/`) when a worker attempts to signal a case that has already terminated. Used by `DefaultCaseHubRuntime.signal()` to prevent workers from mutating completed/faulted/cancelled cases. Refs engine#629.

**signalAndAwait — bulk signal + settlement (engine#490, #483):** `CaseHubRuntime` gains three default methods: `signal(UUID, Map<String,Object>)` (bulk atomic context update, single CONTEXT_CHANGED), `signalAndAwait(UUID, Map, Duration)` → `CompletionStage<CaseContext>` (resolves when all triggered workers complete), `signalAndAwaitSync()` (blocking variant). `BulkSignalReceivedEvent` (`common/internal/event/`) carries the bulk payload + optional `signalId`. `SignalSettlementTracker` (`runtime/internal/engine/`, `@ApplicationScoped`) tracks per-signal expected/completed counts with `synchronized(state)` blocks on `SettlementState` instances. `signalId` (nullable UUID) threads through: `CaseContextChangedEvent` → `WorkerScheduleEvent` → EventLog metadata → `QuartzWorkerExecutionJob` → `WorkflowExecutionCompleted` → `WorkflowExecutionCompletedHandler.recordCompletion()`. On failure: `QuartzWorkerExecutionJob` → `WorkerRetryContext` → `QuartzRetryService` → `WorkerRetriesExhaustedEvent` → `WorkerRetriesExhaustedEventHandler.recordCompletion()`. Guard quarantine path also threads signalId. Settlement resolves when `expectedCount == completedCount AND fullyDispatched`. Only CapabilityTarget bindings count. With `SequentialPlanningStrategy`, settlement resolves after the first step only.

`ExecutionOrigin` (`api/model/event/`) — enum tagging EventLog entries with the origination path: `BINDING_DISPATCH`, `SIGNAL`, `SCHEDULE_TRIGGER`, `SUBCASE_COMPLETION`, `RECOVERY`. Set by each handler that creates EventLog entries. Available on `PlanExecutionContext.origin()` (nullable). Refs engine#618.

`RetryState` (`api/model/`) — record tracking every retry attempt: `attemptCount`, `List<RetryAttempt>` (timestamp, errorMessage, duration, succeeded), `firstAttemptTime`, `lastAttemptTime`. Available on `PlanExecutionContext.retryState()` (nullable — present only on retries) and `DeadLetterEntry.retryState()`. Populated by `QuartzRetryService` from `WORKER_EXECUTION_FAILED` EventLog entries. Refs engine#617.

## Hybrid Orchestration — Four-Tier Model (engine#490)

The engine supports orchestration at four tiers: **Tier 1 (Execution)** — `WorkerRuntime` for in-worker function composition, no durability; **Tier 2 (Simple plan)** — `SequentialPlanningStrategy` selects one binding at a time with natural durability from PlanItem state; **Tier 3 (Complex plan, future)** — `WorkflowPlanningStrategy` backed by Serverless Workflow for durable branching/compensation; **Tier 4 (Multi-case, future)** — blocks patterns for cross-case coordination. Tiers 2-3 share the `PlanningStrategy.select()` seam. `PlanningStrategyLoopControl` injects `Instance<PlanningStrategy>` and resolves by ID from `CaseDefinition.getPlanningStrategy()` (nullable String, defaults to `"default"`). `SequentialPlanningStrategy` (`blackboard/control/`, id=`"sequential"`) returns the first PENDING binding; halts on non-COMPLETED terminal states (FAULTED, REJECTED, OBSOLETE, CANCELLED). `CaseDefinitionYamlMapper` maps `planningStrategy:` and `sequence:` YAML keys — sequence uses two-pass resolution (build all workers, then resolve step references via `WorkerFunctions.sequence()`).

## Worker Outcome Handling

Workers declare semantic outcomes via `WorkerResult`: `Success` (default), `Declined(reason)`, `Failed(reason)`, `Expired(reason)`. The engine handles non-success outcomes via `OutcomePolicy` on the `Binding`:

- `REROUTE` (default): writes failure state to `_outcomes.<bindingName>` in the working layer, marks PlanItem FAULTED, publishes CONTEXT_CHANGED. The binding re-fires with excluded agents filtered from candidates.
- `FAULT`: publishes `CASE_STATUS_CHANGED(FAULTED)` (case-level fault) + `WORKER_OUTCOME_RESOLVED(FAULT)` (PlanItem fault + stage autocomplete).

`Expired` outcomes originate from two sources: engine-internal worker timeout (`SyncAgentWorkerFunctionHandler` converts `TimeoutException` to `WorkerResult.expired()` — the SPI boundary never leaks exceptions) and Qhorus commitment expiration (future, qhorus#281). Both route through `OutcomePolicy.onExpired` using the same `handleSemanticFailure` path as `Declined` and `Failed`.

**Qhorus commitment bridge (engine#515):** `QhorusMessageSignalBridge` translates Qhorus DECLINE/FAILURE speech acts to `WorkerOutcome.Declined`/`WorkerOutcome.Failed` and publishes `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED`. The bridge resolves the original worker and binding from the EventLog via `correlationId` (= eventLogId from the original COMMAND). DONE/RESPONSE messages continue through the existing `channelMessage` signal path. Non-engine messages (non-numeric correlationId or EventLog not found) fall through to the signal path. `QhorusMessageSignalBridge` also routes `MessageType.STATUS` messages via `runtime.signal(caseId, "statusReport", payload)`. STATUS is informational (not commitment-resolving) — no correlationId lookup. Payload: `{from, content, timestamp}`. Milestone/sentry conditions evaluate `.statusReport.content` in JQ. Refs engine#661.

**`ConflictResolver`** (`api/model/`) — static utility for all conflict resolution strategies. Strategies: `LAST_WRITER_WINS` (default), `FIRST_WRITER_WINS`, `FAIL`, `DEEP_MERGE`. `DEEP_MERGE` recursively merges maps, preserving existing keys (`attempts`, `history`, `excludedAgents`) that incoming output does not overwrite. Used by both `WorkflowExecutionCompletedHandler` (worker output) and `PlanItemCompletionApplier` (humanTask output). `PlanItemCompletionApplier` looks up the binding's `conflictResolverStrategy` via `CaseDefinitionRegistry` instead of using bulk `setAll()`. Refs engine#508.

**`Binding.inputSchemaOverride`** — JQ expression overriding the capability's `inputSchema` for this specific binding. Threaded through `WorkerScheduleEvent.effectiveInputSchema()` and `tryProvision()`. Use for failure cascade scope reduction — same capability, narrower input. Refs engine#509.

**`Binding.producedKeys`** — `Set<String>` of context keys this binding is expected to produce. Populated from YAML `producedKeys:` array. Runtime audit: `WorkflowExecutionCompletedHandler` extracts actual produced keys from context diff into EventLog metadata alongside `contextChanges`. Refs engine#616.

**`Binding.contextWrite`** — `Map<String, Object>` applied to the case context before dispatch. Applied in `CaseContextChangedEventHandler.publishByTarget()` before the target-type switch. Prevents infinite condition re-evaluation loops in failure cascade bindings. Refs engine#511.

Failure state schema at `_outcomes.<bindingName>`: `{status, attempts, history[], excludedAgents[]}`. Status values: `DECLINED`, `FAILED`, `EXPIRED`, `REROUTES_EXHAUSTED`, `COMPLETED`. Keyed by **binding name** (not capability name) — two bindings targeting the same capability maintain independent failure state. On successful completion after a reroute, `WorkflowExecutionCompletedHandler.recordSuccessOutcome()` updates status to `COMPLETED` and appends a history entry for the successful agent.

`WorkerOutcomeResolvedHandler` (blackboard, `blocking=true`) consumes `WORKER_OUTCOME_RESOLVED` and owns PlanItem lifecycle for non-success outcomes. `PlanItemCompletionHandler` gates on `WorkerOutcome.Success` and returns early for DECLINED/FAILED/EXPIRED — eliminates the fan-out race.

Agent exclusion: `CaseContextChangedEventHandler.publishWorkerSchedule()` filters excluded agents from `_outcomes.<bindingName>.excludedAgents` before calling the routing strategy. All strategies benefit automatically. When all candidates are excluded, `handleAllCandidatesExhausted()` writes `REROUTES_EXHAUSTED` to `_outcomes` and publishes `WORKER_OUTCOME_RESOLVED(EXHAUSTED)` — the blackboard faults the PlanItem and triggers stage autocomplete.

Failure goals: `GoalReachedEventHandler` produces `CaseStatus.FAULTED` with goal metadata (`satisfiedGoalName`, `satisfiedGoalKind`). Success goals produce `CaseStatus.COMPLETED`. `CaseStatusChanged` carries the goal metadata; `CaseOutcomeEvent.metadata()` propagates it to outcome observers.

Binding name threading: `WorkerScheduleEvent`, `WorkerScheduleEventHandler` (EventLog metadata), `QuartzWorkerExecutionJob`, `WorkflowExecutionCompleted`, `PlanItemCompletionHandler` all carry `bindingName` for precise PlanItem lookup. `findBindingByName()` replaces `findMatchingCapabilityBinding()` for direct binding resolution.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
