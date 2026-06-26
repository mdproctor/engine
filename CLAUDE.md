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

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

The protocol asks: Does this already exist elsewhere? Is this the right repo for it? Does this create a consolidation opportunity? Is this consistent with how the platform handles the same concern in other repos?

**Platform architecture (fetch before any implementation decision):**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md
```

**This repo's deep-dive:**
```
https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-engine.md
```

**Other repo deep-dives** (fetch the relevant ones when your implementation touches their domain):
- casehub-ledger: `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-ledger.md`
- casehub-work: `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-work.md`
- casehub-qhorus: `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-qhorus.md`
- claudony: `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/claudony.md`
- casehub-connectors: `https://raw.githubusercontent.com/casehubio/parent/main/docs/repos/casehub-connectors.md`

---

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
- `casehub-engine-common/src/main/java/io/casehub/engine/spi/` — `CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`, `SubCaseGroupRepository`, `PlanItemStore` (blocking), `ReactivePlanItemStore` (Uni<>)
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/jq/` — `JQEvaluator` (@ApplicationScoped), `ValidationResult` — canonical jq evaluation; lives here so `scheduler-quartz` can inject it without circular dependency. See protocol `PP-20260522-jq-evaluation-canonical`. Follow-on platform extraction tracked in engine#317.
- `casehub-engine-common/src/main/java/io/casehub/engine/common/internal/executor/` — `WorkerExecutor` (SPI), `WorkerExecutionConfig` (@ApplicationScoped, default timeout), `RetryPolicies` (static utility, backoff computation), `RetryDecision` (sealed: Retry | Exhaust), `ExecutionMetadata` (lineage record for flow path)

Both `engine` and both persistence modules depend on `casehub-engine-common`. Neither persistence module depends on `engine`. `scheduler-quartz` also depends on `casehub-engine-common` directly.

**Test classpath note:** `casehub-engine-common` must be added to `quarkus.index-dependency` in any test `application.properties` that needs `JQEvaluator` discovered as a CDI bean — it is a library JAR, not a Quarkus application module.

**Production implementation:** `casehub-persistence-hibernate` (JPA/Panache, PostgreSQL)
**Test implementation:** `casehub-persistence-memory` (in-memory, thread-safe)

Modules needing in-memory tests add `casehub-persistence-memory` as a test dependency and activate the implementations via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` — no Docker required.

**`TenantAwareRepository` — RLS base class (persistence-hibernate only):** All JPA repositories in `casehub-persistence-hibernate` extend `TenantAwareRepository` (which extends `AbstractJpaRepository`). It provides two helpers that inject PostgreSQL session variables inside reactive transactions:
- `withTenantTransaction(work)` — sets `SET LOCAL "casehub.tenancy_id" = <currentPrincipal.tenancyId()>` before any SQL. Used by all tenant-scoped repos (EventLog, CaseInstance, CaseMetaModel, SubCaseGroup, PlanItem).
- `withCrossTenantTransaction(work)` — sets `SET LOCAL ROLE casehub_crosstenancy` (BYPASSRLS). Used by cross-tenant repos (`JpaCrosstenantEventLogRepository`, `JpaCrosstenantCaseInstanceRepository`) and recovery methods.

`SET LOCAL` resets automatically at transaction end — no cleanup needed. All reads AND writes go through `withTenantTransaction()` because `SET LOCAL` only applies inside an explicit PostgreSQL transaction (not in `withSession()` autocommit mode).

**`JpaCrosstenantEventLogRepository`** — separate class (not inside `JpaEventLogRepository`) implementing `CrossTenantEventLogRepository`. `JpaEventLogRepository` implements `EventLogRepository` only. Both extend `TenantAwareRepository`.

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

**`CaseDefinitionRegistry` uses `CaseKey` record:** `DefaultCaseDefinitionRegistry` stores definitions in `Map<CaseKey, RegistryEntry>`. `CaseKey` is an immutable record `(namespace, name, version)` — eliminates the mutable-hashCode map key bug (engine#410). `RegistryEntry` is an inner record `(CaseDefinition, CaseMetaModel)` — a single atomic map put covers both, with no consistency window between two separate maps. `findByIdentity(namespace, name, version)` returns `Optional<CaseMetaModel>` — clean existence query without the throw-on-not-found of `getCaseMetaModel()`. Added as a `default` method per SPI evolution protocol. Refs engine#525.

**`CaseInstanceRepository` query methods:** `findByStatus(CaseStatus, tenancyId)`, `findAll(tenancyId)`, `findByNamespaceAndName(namespace, name, tenancyId)` — all return `Uni<List<CaseInstance>>`. Added as `default` methods (return `List.of()`) per SPI evolution protocol. Implemented in both `InMemoryCaseInstanceRepository` (stream filter) and `JpaCaseInstanceRepository` (JPQL with `join fetch` on `caseMetaModel`). Refs engine#523.

**`@CrossTenant` qualifier:** Cross-tenant SPIs (`CrossTenantEventLogRepository`, `CrossTenantCaseInstanceRepository`) are only injectable via `@CrossTenant`. `CrossTenantProducer` (in `runtime/internal/identity/`) produces both beans, guarded by `@EngineSystem SystemCurrentPrincipal`. Convention-based — CDI does not prevent unqualified injection; code review and the qualifier annotation are the enforcement mechanism. `SystemCurrentPrincipal` is `@ApplicationScoped @EngineSystem` (not `@DefaultBean`) — it does not conflict with `MockCurrentPrincipal`. All 6 engine injection sites (`PendingWorkRegistry`, `DefaultWorkerExecutionRecoveryService`, `QuartzWorkerExecutionJob`, `QuartzWorkerExecutionManager`, `MilestoneSLATimeoutJob`, `DeadLetterReplayService`) use `@CrossTenant`. See protocol PP-20260520-e6a5f0.

## CaseOutcomeObserver SPI

`CaseOutcomeObserver` — lifecycle hook called by the engine when a case reaches a terminal state (COMPLETED, FAULTED, CANCELLED). Implementations write CBR case entries to `CaseMemoryStore` or perform other outcome-based learning operations. Fired from `CaseStatusChangedHandler` on a worker thread (`blocking = true`). `@DefaultBean` no-op in `runtime/internal/worker/`. Consumer implementations are `@ApplicationScoped` and discovered automatically via CDI `Instance<CaseOutcomeObserver>`. Refs engine#477.

`ActionGatePolicy` — shared enum (`ALWAYS`, `THRESHOLD`, `CONDITIONAL`) in `api/spi/` for domain classifiers (AML, clinical, devtown, life) to reference instead of defining their own gate policy vocabulary. Refs engine#472.

## JQ Expression Evaluation Surface

All JQ expressions (binding filters, `when` conditions, goals, milestones, `inputSchema`, `outputSchema`) evaluate against the **working panel** (`context.panel(ContextPanel.WORKING).asJsonNode()`), NOT the full panel document (`context.asJsonNode()`). YAML definitions use unqualified field paths (`.transaction`, `.entityResolution`) — the panel structure is an engine implementation detail.

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
- `NoOpWorkerExecutionManager` — `submit()` fails with `ProvisioningException`; other methods no-op. Required for deployments without `scheduler-quartz` or `workers-camel` on the classpath (engine#447)

All ten are `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) — they yield automatically to any consumer-provided implementation without requiring `selected-alternatives` configuration. See protocol `PP-20260514-engine-spi-noops-defaultbean`.

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
- `RiskDecision` — sealed: `Autonomous` | `GateRequired(reason, reversible, candidateGroups, expiresIn, scope)`
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
2. Publishes `ActionGateScheduleEvent` → `ActionGateWorkItemHandler` (work-adapter) creates a WorkItem
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

**Test pattern:** Mock `ChatModel` via package-private `AgentBuilder.model(ChatModel)` for unit tests. For `@QuarkusTest` integration tests, define inner `CaseHub` subclasses with mock `ChatModelProvider` returning canned JSON. No Mockito needed — use anonymous `ChatModel` implementations.

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
  alternatives via `quarkus.arc.selected-alternatives` (including `MemorySubCaseGroupRepository`)
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

**YAML DSL:** `humanTask` is a first-class binding target type in `CaseDefinition.yaml` (alongside `capability` and `subCase`). `CaseDefinitionYamlMapper` converts it to `HumanTaskTarget`. Inline mode requires `title`; template mode requires `templateRef`. Both modes support `outputMapping`, `inputMapping`, `candidateGroups`, `candidateUsers`, `expiresIn`, `scope` (hierarchical path for SLA preference resolution, e.g. `"casehubio/devtown/pr-review"`), `claimDeadlineHours` (integer — business hours to claim before escalation, wired to `WorkItemCreateRequest.claimDeadlineBusinessHours`), and `outcomes` (`Set<String>` of valid outcome names, e.g. `APPROVED`, `REJECTED` — propagated to `WorkItemCreateRequest.permittedOutcomes` in inline mode and `WorkItem.permittedOutcomes` via `OutcomeCodecs.encodeOutcomes()` in template mode; enforced at completion by casehub-work). Refs engine#325, engine#512.

Two-way bridge between casehub-work and CaseHub plan items:
- **Inbound** (`WorkItemLifecycleAdapter`) — translates terminal `WorkItemLifecycleEvent` CDI events to `PlanItem` transitions via `status.isTerminal()` guard (no explicit enumeration), evaluates `outputMapping` against the WorkItem resolution JSON, and fires `CONTEXT_CHANGED` for engine re-evaluation. Terminal status mapping: COMPLETED → `markCompleted()`; REJECTED → `markRejected()` (intentional human refusal — PlanItem must be DELEGATED); FAULTED → `markFaulted()` (system failure) + fires `PlanItemFaultedEvent`; EXPIRED → `markFaulted()` (deadline failure) + fires `PlanItemFaultedEvent`; OBSOLETE → `markObsolete()` (context changed, work irrelevant) + fires `PlanItemObsoleteEvent`; CANCELLED → `markCancelled()`. Also observes `WorkItemGroupLifecycleEvent` for M-of-N SpawnGroup outcomes (COMPLETED → `PlanItem.markCompleted()`; REJECTED → `PlanItem.markRejected()`). ESCALATED is terminal — all SLA breach policy branches exhausted. The adapter writes a `workItemEscalated` signal to the case context (`{workItemId, newGroups, bindingName}`) so case definitions can react via `contextChange(".workItemEscalated")` bindings. The PlanItem stays DELEGATED. SLA breach policies that re-route the WorkItem to new groups (the `EscalateTo` decision) do not set ESCALATED — the WorkItem stays PENDING, so the adapter's terminal filter skips it. Refs engine#338, engine#400, engine#539.
- **Outbound** (`HumanTaskScheduleHandler`) — consumes `HUMAN_TASK_SCHEDULE` event bus messages, looks up the `PlanItem` by binding name, then:
  - **Inline mode** (`HumanTaskTarget.inline()`): creates a `WorkItem` via `WorkItemService`, then `planItemStore.save(DELEGATED)`, then `item.markDelegated()`
  - **Template mode** (`HumanTaskTarget.template(ref)`): parses `ref` as UUID and resolves via `WorkItemTemplateService.findById`; invalid UUID or not-found → warn + leave PlanItem PENDING; on success calls `WorkItemTemplateService.instantiate(template, titleOverride, null, "casehub-engine", callerRef)` (5-arg — callerRef is 5th param, assigneeId is null to route via candidateGroups), then manually sets `workItem.scope` and `workItem.payload` (serialized `inputData`, honours `inputMapping` contract), persists with `workItem.persist()`, then `planItemStore.save(DELEGATED)`, then `item.markDelegated()`

All three steps in each mode are inside `@Transactional` — if WorkItem creation fails the transaction rolls back and `markDelegated()` is never called (PlanItem stays PENDING). `JpaPlanItemStore` + `WorkAdapterPlanItemEntity` live in `work-adapter` (blocking JPA, shares casehub-work datasource). `MemoryPlanItemStore` (in `casehub-engine-persistence-memory`) must be in `selected-alternatives` for work-adapter tests.

`@ConsumeEvent` handlers that call `@Transactional` services must use `blocking = true` — without it, the transaction silently does not commit on the Vert.x IO thread (the WorkItem is never created, no error is thrown).

See protocols `PP-20260517-cbf836` (PlanItem must not be marked RUNNING until all resolution steps succeed), `PP-20260517-0093f8` (inputMapping output must reach WorkItem payload in all handler modes), and `PP-20260518-78f8b7` (PlanItemStore.save() must be called from a blocking @Transactional context).

**Test setup** (when depending on `casehub-work` full module):
- Add `casehub-work-persistence-memory` test dep — provides `InMemoryWorkItemStore @Alternative @Priority(1)`
- Add `quarkus-jdbc-h2` test dep — casehub-work JPA entities require a datasource even in tests
- Add `quarkus.arc.exclude-types=io.casehub.work.runtime.repository.jpa.JpaWorkItemStore` to `application.properties` — `@Alternative @Priority(1)` from an external jar does NOT automatically override a non-alternative `@ApplicationScoped` bean in Quarkus ARC 3.x; excluding the JPA store is required for `InMemoryWorkItemStore` to resolve correctly
- Use `quarkus.arc.selected-alternatives` to activate `casehub-persistence-memory` repos AND `io.casehub.work.memory.InMemoryWorkItemStore` — omitting it causes boot failure: `Unsatisfied dependency for SubCaseGroupRepository`
- Add `@Alternative @Priority(1)` static inner class stub for `WorkloadProvider` — casehub-work ships `JpaWorkloadProvider` which would query the database for work counts; a zero-returning stub isolates tests from DB queries. (engine#337 removed `CasehubWorkloadProvider` — no CDI ambiguity exists, but the stub is still good test hygiene)
- Set `quarkus.quartz.store-type=ram` and `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`
- `QuarkusTestProfile.getEnabledAlternatives()` **replaces** (not appends to) `quarkus.arc.selected-alternatives` — any profile using this method must re-declare all globally required alternatives, including persistence-memory repos and `InMemoryWorkItemStore`

`callerRef` format: `case:{caseId}/pi:{planItemId}` — use `CallerRef.encode()` / `CallerRef.parse()`.

## casehub-engine-actor-state Module

Optional module providing a unified actor workload view (`GET /actors/{actorId}/state`). Aggregates active cases (via `WorkerExecutionManager.getActiveCaseIds`), open WorkItems (via `casehub-work-api`), and open Qhorus obligations (via `CommitmentStore.findOpenByObligor`) using the `ActorStateContributor` SPI from `casehub-platform-api`. Both blocking (`ActorStateAggregator`) and reactive (`ReactiveActorStateAggregator`) aggregation paths are provided with parity enforced by `ActorStateParityTest`. Activated by adding `casehub-engine-actor-state` to the consumer's classpath.

## casehub-engine-flow Module

Optional module enabling `Worker(Workflow)` to dispatch casehub workers from within Serverless Workflow steps and await their results. Activated by adding `casehub-engine-flow` to the consumer's classpath.

`FlowWorkerFunction` (record, implements `WorkerFunction`) lives here — the serverlessworkflow SDK never leaves this module. `FlowWorkerFunctionProvider` (`@ApplicationScoped`, implements `WorkerFunctionProvider`) handles YAML `do:` block construction — receives raw `JsonNode`, deserializes to `Workflow` via `WorkflowReader`. `FlowWorkerFunctionHandler` (`@ApplicationScoped`, implements `WorkerFunctionHandler`) executes workflows using `WorkflowApplication` singleton and `FlowExecutionRegistry`, running on `@VirtualThreads ExecutorService`. `CasehubCallableTaskBuilder implements CallableTaskBuilder<CallFunction>` (registered via Java SPI) handles `call: casehub:dispatch` YAML steps. Note: `CallFunction` and `FunctionArguments` are in `io.serverlessworkflow.api.types` — not the `.func` experimental subpackage.

## Worker Execution Architecture

`WorkerExecutor` (`common/internal/executor/`) abstracts how to run a worker function — independent of any scheduler. `DefaultWorkerExecutor` (`runtime/internal/executor/`) is a composite over `WorkerFunctionHandler` instances — it iterates `Instance<WorkerFunctionHandler>`, finds the first handler that `supports()` the function, delegates execution, and applies output schema evaluation as `.map()` post-processing. `SyncAgentWorkerFunctionHandler` (`runtime`) handles `Sync` and `AgentWorkerFunction` on `@VirtualThreads ExecutorService` with timeout enforcement. `FlowWorkerFunctionHandler` (`flow`) handles `FlowWorkerFunction` — see casehub-engine-flow Module. `WorkerFunctionHandler` (`common/internal/executor/`) is the engine-internal SPI; `outputSchema` is deliberately absent from the handler interface (cross-cutting concern owned by the composite executor). `WorkerFunctionProvider` and `WorkerFunctionProviderRegistry` (`api/spi/`) delegate YAML worker function construction to modules — the flow module registers `FlowWorkerFunctionProvider` for `do:` blocks; Agent and Sync construction stays inline in `CaseDefinitionYamlMapper`. Worker/Capability/WorkerFunction/WorkerResult/WorkerOutcome are from `io.casehub.worker.api` (foundation tier); `WorkerFunction` is a marker interface with no `execute()` method. ExecutionPolicy/RetryPolicy/BackoffStrategy are from `io.casehub.platform.api.governance`.

`QuartzWorkerExecutionJob` is a thin fire-and-forget Quartz adapter: resolves context (EventLog, CaseInstance, Worker, Capability), delegates to `WorkerExecutor.execute()`, and subscribes with success/failure callbacks. Success publishes `WORKER_EXECUTION_FINISHED`; failure routes to `QuartzRetryService`.

`QuartzRetryService` (`scheduler-quartz`) owns failure handling: persists `WORKER_EXECUTION_FAILED` event log, resolves retry policy from the worker's `ExecutionPolicy`, counts prior failures, and uses `RetryPolicies.evaluate()` to decide retry vs exhaust. On retry, reschedules via `QuartzWorkerSchedulerService`; on exhaust, publishes `WORKER_RETRIES_EXHAUSTED`.

`RetryPolicies` (`common/internal/executor/`) is a pure static utility for backoff computation — no CDI, no dependencies. `RetryDecision` is a sealed type: `Retry(Duration delay)` or `Exhaust(String reason)`. Moved from `QuartzWorkerExecutionJobListener` so any scheduler adapter can reuse the same backoff logic.

`WorkerExecutionConfig` (`common/internal/executor/`) provides the default worker timeout (`casehub.engine.worker.default-timeout-ms`, default 60000ms). Per-worker overrides come from `ExecutionPolicy.timeoutMs()`.

## Worker Outcome Handling

Workers declare semantic outcomes via `WorkerResult`: `Success` (default), `Declined(reason)`, `Failed(reason)`, `Expired(reason)`. The engine handles non-success outcomes via `OutcomePolicy` on the `Binding`:

- `REROUTE` (default): writes failure state to `_outcomes.<bindingName>` in the working panel, marks PlanItem FAULTED, publishes CONTEXT_CHANGED. The binding re-fires with excluded agents filtered from candidates.
- `FAULT`: publishes `CASE_STATUS_CHANGED(FAULTED)` (case-level fault) + `WORKER_OUTCOME_RESOLVED(FAULT)` (PlanItem fault + stage autocomplete).

`Expired` outcomes originate from two sources: engine-internal worker timeout (`SyncAgentWorkerFunctionHandler` converts `TimeoutException` to `WorkerResult.expired()` — the SPI boundary never leaks exceptions) and Qhorus commitment expiration (future, qhorus#281). Both route through `OutcomePolicy.onExpired` using the same `handleSemanticFailure` path as `Declined` and `Failed`.

**Qhorus commitment bridge (engine#515):** `QhorusMessageSignalBridge` translates Qhorus DECLINE/FAILURE speech acts to `WorkerOutcome.Declined`/`WorkerOutcome.Failed` and publishes `WorkflowExecutionCompleted` on `WORKER_EXECUTION_FINISHED`. The bridge resolves the original worker and binding from the EventLog via `correlationId` (= eventLogId from the original COMMAND). DONE/RESPONSE messages continue through the existing `channelMessage` signal path. Non-engine messages (non-numeric correlationId or EventLog not found) fall through to the signal path.

**`ConflictResolver`** (`api/model/`) — static utility for all conflict resolution strategies. Strategies: `LAST_WRITER_WINS` (default), `FIRST_WRITER_WINS`, `FAIL`, `DEEP_MERGE`. `DEEP_MERGE` recursively merges maps, preserving existing keys (`attempts`, `history`, `excludedAgents`) that incoming output does not overwrite. Used by both `WorkflowExecutionCompletedHandler` (worker output) and `PlanItemCompletionApplier` (humanTask output). `PlanItemCompletionApplier` looks up the binding's `conflictResolverStrategy` via `CaseDefinitionRegistry` instead of using bulk `setAll()`. Refs engine#508.

**`Binding.inputSchemaOverride`** — JQ expression overriding the capability's `inputSchema` for this specific binding. Threaded through `WorkerScheduleEvent.effectiveInputSchema()` and `tryProvision()`. Use for failure cascade scope reduction — same capability, narrower input. Refs engine#509.

**`Binding.contextWrite`** — `Map<String, Object>` applied to the case context before dispatch. Applied in `CaseContextChangedEventHandler.publishByTarget()` before the target-type switch. Prevents infinite condition re-evaluation loops in failure cascade bindings. Refs engine#511.

Failure state schema at `_outcomes.<bindingName>`: `{status, attempts, history[], excludedAgents[]}`. Status values: `DECLINED`, `FAILED`, `EXPIRED`, `REROUTES_EXHAUSTED`, `COMPLETED`. Keyed by **binding name** (not capability name) — two bindings targeting the same capability maintain independent failure state. On successful completion after a reroute, `WorkflowExecutionCompletedHandler.recordSuccessOutcome()` updates status to `COMPLETED` and appends a history entry for the successful agent.

`WorkerOutcomeResolvedHandler` (blackboard, `blocking=true`) consumes `WORKER_OUTCOME_RESOLVED` and owns PlanItem lifecycle for non-success outcomes. `PlanItemCompletionHandler` gates on `WorkerOutcome.Success` and returns early for DECLINED/FAILED/EXPIRED — eliminates the fan-out race.

Agent exclusion: `CaseContextChangedEventHandler.publishWorkerSchedule()` filters excluded agents from `_outcomes.<bindingName>.excludedAgents` before calling the routing strategy. All strategies benefit automatically. When all candidates are excluded, `handleAllCandidatesExhausted()` writes `REROUTES_EXHAUSTED` to `_outcomes` and publishes `WORKER_OUTCOME_RESOLVED(EXHAUSTED)` — the blackboard faults the PlanItem and triggers stage autocomplete.

Failure goals: `GoalReachedEventHandler` produces `CaseStatus.FAULTED` with goal metadata (`satisfiedGoalName`, `satisfiedGoalKind`). Success goals produce `CaseStatus.COMPLETED`. `CaseStatusChanged` carries the goal metadata; `CaseOutcomeEvent.metadata()` propagates it to outcome observers.

Binding name threading: `WorkerScheduleEvent`, `WorkerScheduleEventHandler` (EventLog metadata), `QuartzWorkerExecutionJob`, `WorkflowExecutionCompleted`, `PlanItemCompletionHandler` all carry `bindingName` for precise PlanItem lookup. `findBindingByName()` replaces `findMatchingCapabilityBinding()` for direct binding resolution.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
