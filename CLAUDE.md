# CLAUDE.md

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

**Repository:** treblereel/casehub-engine

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
- SQL migration files (`V*.sql`, `db/migration/` directories)
- `quarkus.flyway.*` or `quarkus.liquibase.*` properties
- JDBC-only dependencies (`quarkus-jdbc-postgresql`, `quarkus-agroal`) unless required for a non-migration reason

Schema is managed by Hibernate directly:
```properties
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
```

If a schema change is needed, update the `@Entity` class. Hibernate recreates the schema on next startup.

## Persistence Architecture

Domain objects and SPI interfaces live in `casehub-engine-common` (no Quarkus, no JPA):

- `casehub-engine-common/src/main/java/io/casehub/engine/internal/model/` — `CaseMetaModel`, `CaseInstance`, `SubCaseGroup`
- `casehub-engine-common/src/main/java/io/casehub/engine/internal/history/` — `EventLog`, `CaseHubEventType`, `EventStreamType`
- `casehub-engine-common/src/main/java/io/casehub/engine/spi/` — `CaseMetaModelRepository`, `CaseInstanceRepository`, `EventLogRepository`, `SubCaseGroupRepository`

Both `engine` and both persistence modules depend on `casehub-engine-common`. Neither persistence module depends on `engine`.

**Production implementation:** `casehub-persistence-hibernate` (JPA/Panache, PostgreSQL)
**Test implementation:** `casehub-persistence-memory` (in-memory, thread-safe)

Modules needing in-memory tests add `casehub-persistence-memory` as a test dependency and activate the implementations via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` — no Docker required.

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

Domain objects (`CaseMetaModel`, `CaseInstance`, `EventLog`) are plain POJOs. The `id` field
is public (`public Long id`) and set by the repository after save.

## Worker Provisioner SPIs

Eight interfaces in `api/src/main/java/io/casehub/api/spi/` (four blocking + four reactive mirrors):

- `WorkerProvisioner` / `ReactiveWorkerProvisioner` — provision and terminate workers
- `WorkerStatusListener` / `ReactiveWorkerStatusListener` — lifecycle callbacks (started, completed, stalled)
- `CaseChannelProvider` / `ReactiveCaseChannelProvider` — open/close/post to backend-agnostic channels. **`postToChannel` takes a 4th `MessageType` parameter** (from `casehub-qhorus-api`, managed in root `pom.xml`); the 3-arg overload is a `default` delegating with `null`. Call sites that know the intent pass `MessageType.COMMAND` etc. explicitly — `WorkerScheduleEventHandler.dispatchCommand` does this.
- `WorkerContextProvider` / `ReactiveWorkerContextProvider` — build startup context from ledger lineage

**Default implementations** in `engine/src/main/java/io/casehub/engine/internal/worker/` and `diff/`:
- `NoOpWorkerProvisioner`, `NoOpWorkerStatusListener`, `NoOpCaseChannelProvider`, `EmptyWorkerContextProvider`, `NoOpContextDiffStrategy`
- Five `@DefaultBean` reactive mirrors: `NoOpReactiveWorkerProvisioner`, `NoOpReactiveCaseChannelProvider`, `NoOpReactiveWorkerStatusListener`, `EmptyReactiveWorkerContextProvider`

All nine are `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) — they yield automatically to any consumer-provided implementation without requiring `selected-alternatives` configuration. See protocol `PP-20260514-engine-spi-noops-defaultbean`.

**SPI placement rule:** Operational SPIs (worker provisioning, lifecycle, channels) go in `api/spi/`; persistence SPIs (`CaseMetaModelRepository`, etc.) go in `casehub-engine-common/spi/`. This clarifies intent: operational SPIs are about external system integration; persistence SPIs are about data durability.

To add a new operational SPI: define the interface in `api/spi/`, add a `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) no-op default in `engine/internal/worker/`, add it to the beans table in protocol `PP-20260514-engine-spi-noops-defaultbean`, add contract tests in `api/src/test/java/io/casehub/api/spi/`, and add engine unit tests in `engine/src/test/java/io/casehub/engine/internal/worker/DefaultWorkerSpiImplementationsTest.java`.

**Engine wiring — which SPIs are called and where (Refs #191):**

| SPI | Called in | When |
|-----|-----------|------|
| `WorkerStatusListener.onWorkerStarted` | `WorkerExecutionJobListener` | Quartz job begins |
| `WorkerStatusListener.onWorkerCompleted` | `WorkflowExecutionCompletedHandler` | Worker function returns |
| `WorkerStatusListener.onWorkerStalled` | `WorkerRetriesExhaustedEventHandler` | All retries exhausted |
| `CaseChannelProvider.openChannel` | `CaseStartedEventHandler` | Case starts |
| `CaseChannelProvider.openChannel` + `postToChannel(..., MessageType.COMMAND)` | `WorkerScheduleEventHandler.dispatchCommand` | Worker scheduled — opens channel, posts COMMAND |
| `CaseChannelProvider.closeChannel` | `CaseStatusChangedHandler` | Case reaches terminal state |
| `WorkerContextProvider.buildContext` | `WorkerScheduleEventHandler` | Before Quartz job is submitted (timing contract) |
| `WorkerContextProvider.buildContext` + `WorkerExecutionContext.set` | `QuartzWorkerExecutionJob` | Immediately before worker function — sets thread-local with channels |
| `WorkerProvisioner.provision` | `CaseContextChangedEventHandler.tryProvision` | No pre-defined workers match capability |

`WorkerProvisioner.provision()` is called only when `workerProvisioner.getCapabilities()` contains the required capability. `ProvisioningException` is caught and logged; the binding stays eligible for the next context-change tick. The no-op default returns empty capabilities, so it is never called unless a real provisioner is wired in.

**`ProvisionContext` fields:** `caseId`, `taskType`, `workerContext` (nullable), `propagationContext`, `triggerChannelId` (nullable String), `triggerCorrelationId` (nullable String). The trigger fields carry the Qhorus channel ID and correlation ID of the COMMAND that caused provisioning — allowing provisioner implementations to establish causal linkage in the ledger. Engine-internal call sites pass `null` for both until engine#231 threads Qhorus trigger context through the CaseFile-update API.

`WorkerExecutionContext.current()` returns the active `WorkerContext` (including `channels`) inside a worker's function body. Cleared in a `finally` block after the function returns.

**To test SPI wiring:** use `@Alternative @Priority(1) @ApplicationScoped` static inner classes in `@QuarkusTest` with `static` recording fields reset in `@BeforeEach`. This activates the recording bean globally across the test suite without Mockito. See `SpiWiringIntegrationTest` for the pattern. To test provisioner wiring, define a `CaseHub` subclass with a capability binding and no workers — the engine will fall through to `tryProvision()`.

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

Two-way bridge between casehub-work and CaseHub plan items:
- **Inbound** (`WorkItemLifecycleAdapter`) — translates terminal `WorkItemLifecycleEvent` CDI events to `PlanItem` transitions, evaluates `outputMapping` against the WorkItem resolution JSON, and fires `CONTEXT_CHANGED` for engine re-evaluation
- **Outbound** (`HumanTaskScheduleHandler`) — consumes `HUMAN_TASK_SCHEDULE` event bus messages, looks up the `PlanItem` by binding name, marks it RUNNING, and creates a `WorkItem` via `WorkItemService` with `callerRef = case:{caseId}/pi:{planItemId}`

`@ConsumeEvent` handlers that call `@Transactional` services must use `blocking = true` — without it, the transaction silently does not commit on the Vert.x IO thread (the WorkItem is never created, no error is thrown).

**Test setup** (when depending on `casehub-work` full module):
- Add `casehub-work-testing` test dep — provides `@Alternative @Priority` in-memory WorkItem stores
- Add `quarkus-jdbc-h2` test dep — casehub-work JPA entities require a datasource even in tests
- Use `quarkus.arc.selected-alternatives` to activate `casehub-persistence-memory` repos, including `MemorySubCaseGroupRepository` — omitting it causes boot failure: `Unsatisfied dependency for SubCaseGroupRepository`
- Add `@Alternative @Priority(1)` static inner class stub for `WorkloadProvider` — casehub-work ships `JpaWorkloadProvider` which clashes with `CasehubWorkloadProvider` from the engine
- Set `quarkus.quartz.store-type=ram` and `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`

`callerRef` format: `case:{caseId}/pi:{planItemId}` — use `CallerRef.encode()` / `CallerRef.parse()`.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
