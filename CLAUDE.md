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
- SQL migration files (`V*.sql`, `db/migration/` directories)
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

Both `engine` and both persistence modules depend on `casehub-engine-common`. Neither persistence module depends on `engine`. `scheduler-quartz` also depends on `casehub-engine-common` directly.

**Test classpath note:** `casehub-engine-common` must be added to `quarkus.index-dependency` in any test `application.properties` that needs `JQEvaluator` discovered as a CDI bean — it is a library JAR, not a Quarkus application module.

**Production implementation:** `casehub-persistence-hibernate` (JPA/Panache, PostgreSQL)
**Test implementation:** `casehub-persistence-memory` (in-memory, thread-safe)

Modules needing in-memory tests add `casehub-persistence-memory` as a test dependency and activate the implementations via `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` — no Docker required.

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

Domain objects (`CaseMetaModel`, `CaseInstance`, `EventLog`) are plain POJOs. The `id` field
is public (`public Long id`) and set by the repository after save.

## Worker Provisioner SPIs

Eight interfaces in `api/src/main/java/io/casehub/api/spi/` (four blocking + four reactive mirrors):

- `WorkerProvisioner` / `ReactiveWorkerProvisioner` — provision and terminate workers
- `WorkerStatusListener` / `ReactiveWorkerStatusListener` — lifecycle callbacks (started, completed, stalled)
- `CaseChannelProvider` / `ReactiveCaseChannelProvider` — open/close/post to backend-agnostic channels. **`postToChannel` takes 6 parameters**: `(CaseChannel, String from, String content, MessageType, String correlationId, String deadline)`. The 3-arg overload is a `default` delegating with three `null`s. `correlationId` and `deadline` are first-class SPI params (engine#343) — consumers no longer parse them from `CommandContent` JSON.
- `WorkerContextProvider` / `ReactiveWorkerContextProvider` — build startup context from ledger lineage

**Default implementations** in `engine/src/main/java/io/casehub/engine/internal/worker/`:
- `NoOpWorkerProvisioner`, `NoOpWorkerStatusListener`, `NoOpCaseChannelProvider`, `EmptyWorkerContextProvider`
- Four `@DefaultBean` reactive mirrors: `NoOpReactiveWorkerProvisioner`, `NoOpReactiveCaseChannelProvider`, `NoOpReactiveWorkerStatusListener`, `EmptyReactiveWorkerContextProvider`
- `NoOpCapabilityHealth` — returns `Ready` for all probes; deployments without `casehub-eidos-api` get transparent no-op

All nine are `@DefaultBean @ApplicationScoped` (`io.quarkus.arc.DefaultBean`) — they yield automatically to any consumer-provided implementation without requiring `selected-alternatives` configuration. See protocol `PP-20260514-engine-spi-noops-defaultbean`.

**`ContextDiffStrategy`** is engine-internal strategy selection, not a consumer-replaceable SPI. Selected via `casehub.engine.diff-strategy` config (`none` | `top-level` | `json-patch`, default `none`). A `@Produces @DefaultBean` producer in `engine/internal/diff/ContextDiffStrategyProducer` instantiates the chosen POJO — consumer `@ApplicationScoped` impl still wins automatically.

**SPI placement rule:** Operational SPIs (worker provisioning, lifecycle, channels) go in `api/spi/`; persistence SPIs (`CaseMetaModelRepository`, etc.) go in `casehub-engine-common/spi/`. This clarifies intent: operational SPIs are about external system integration; persistence SPIs are about data durability.

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
| `WorkerProvisioner.provision` | `CaseContextChangedEventHandler.tryProvision` | No pre-defined workers match capability |

`WorkerProvisioner.provision()` is called only when `workerProvisioner.getCapabilities()` contains the required capability. `ProvisioningException` is caught and logged; the binding stays eligible for the next context-change tick. The no-op default returns empty capabilities, so it is never called unless a real provisioner is wired in.

**`ProvisionContext` fields:** `caseId`, `taskType`, `workerContext` (nullable), `propagationContext`, `triggerChannelId` (nullable String), `triggerCorrelationId` (nullable String). The trigger fields carry the Qhorus channel ID and correlation ID of the COMMAND that caused provisioning — allowing provisioner implementations to establish causal linkage in the ledger. Engine-internal call sites pass `null` for both until engine#231 threads Qhorus trigger context through the CaseFile-update API.

`WorkerExecutionContext.current()` returns the active `WorkerContext` (including `channels`) inside a worker's function body. Cleared in a `finally` block after the function returns.

**To test SPI wiring:** use `@Alternative @Priority(1) @ApplicationScoped` static inner classes in `@QuarkusTest` with `static` recording fields reset in `@BeforeEach`. This activates the recording bean globally across the test suite without Mockito. See `SpiWiringIntegrationTest` for the pattern. To test provisioner wiring, define a `CaseHub` subclass with a capability binding and no workers — the engine will fall through to `tryProvision()`.

## Agent Worker AI Model

AI agent workers live in `api/src/main/java/io/casehub/api/model/ai/`:

- `Agent` — immutable execution unit; holds systemPrompt, transformers, ChatModel, optional responseSchema
- `AgentBuilder` — fluent builder; JQ string mode (`inputSchema(String)`) or lambda mode (`inputTransformer(UnaryOperator<JsonNode>)`) for transformers; mutually exclusive per direction
- `ChatModelProvider` — SPI interface; implementations use reflection (`Class.forName`) to avoid compile-time LLM SDK dependencies
- `ModelType` — enum: OPENAI, OLLAMA, ANTHROPIC, MISTRAL, GOOGLE_AI_GEMINI
- `JqTransformer` — standalone JQ evaluator (jackson-jq 1.6); thread-safe after construction
- `AgentException` — unchecked exception for agent failures (invalid JSON, JQ errors, template errors)

Provider implementations in sub-packages (`openai/`, `anthropic/`, `mistral/`, `gemini/`, `ollama/`) use `ServiceLoader` for discovery and reflection-based builder construction.

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

**YAML DSL:** `humanTask` is a first-class binding target type in `CaseDefinition.yaml` (alongside `capability` and `subCase`). `CaseDefinitionYamlMapper` converts it to `HumanTaskTarget`. Inline mode requires `title`; template mode requires `templateRef`. Both modes support `outputMapping`, `inputMapping`, `candidateGroups`, `candidateUsers`, `expiresIn`, and `scope` (hierarchical path for SLA preference resolution, e.g. `"casehubio/devtown/pr-review"`).

Two-way bridge between casehub-work and CaseHub plan items:
- **Inbound** (`WorkItemLifecycleAdapter`) — translates terminal `WorkItemLifecycleEvent` CDI events to `PlanItem` transitions, evaluates `outputMapping` against the WorkItem resolution JSON, and fires `CONTEXT_CHANGED` for engine re-evaluation
- **Outbound** (`HumanTaskScheduleHandler`) — consumes `HUMAN_TASK_SCHEDULE` event bus messages, looks up the `PlanItem` by binding name, then:
  - **Inline mode** (`HumanTaskTarget.inline()`): creates a `WorkItem` via `WorkItemService`, then `planItemStore.save(DELEGATED)`, then `item.markDelegated()`
  - **Template mode** (`HumanTaskTarget.template(ref)`): parses `ref` as UUID and resolves via `WorkItemTemplateService.findById`; invalid UUID or not-found → warn + leave PlanItem PENDING; on success calls `WorkItemTemplateService.instantiate(template, titleOverride, assigneeId, createdBy)` with `target.title()` as titleOverride, `null` as assigneeId, and `"casehub-engine"` as createdBy, then manually sets `workItem.callerRef`, `workItem.scope`, and `workItem.payload` (serialized `inputData`, honours `inputMapping` contract), persists with `workItem.persist()`, then `planItemStore.save(DELEGATED)`, then `item.markDelegated()`

All three steps in each mode are inside `@Transactional` — if WorkItem creation fails the transaction rolls back and `markDelegated()` is never called (PlanItem stays PENDING). `JpaPlanItemStore` + `WorkAdapterPlanItemEntity` live in `work-adapter` (blocking JPA, shares casehub-work datasource). `MemoryPlanItemStore` (in `casehub-engine-persistence-memory`) must be in `selected-alternatives` for work-adapter tests.

`@ConsumeEvent` handlers that call `@Transactional` services must use `blocking = true` — without it, the transaction silently does not commit on the Vert.x IO thread (the WorkItem is never created, no error is thrown).

See protocols `PP-20260517-cbf836` (PlanItem must not be marked RUNNING until all resolution steps succeed), `PP-20260517-0093f8` (inputMapping output must reach WorkItem payload in all handler modes), and `PP-20260518-78f8b7` (PlanItemStore.save() must be called from a blocking @Transactional context).

**Test setup** (when depending on `casehub-work` full module):
- Add `casehub-work-testing` test dep — provides `InMemoryWorkItemStore @Alternative @Priority(1)`
- Add `quarkus-jdbc-h2` test dep — casehub-work JPA entities require a datasource even in tests
- Add `quarkus.arc.exclude-types=io.casehub.work.runtime.repository.jpa.JpaWorkItemStore` to `application.properties` — `@Alternative @Priority(1)` from an external jar does NOT automatically override a non-alternative `@ApplicationScoped` bean in Quarkus ARC 3.x; excluding the JPA store is required for `InMemoryWorkItemStore` to resolve correctly
- Use `quarkus.arc.selected-alternatives` to activate `casehub-persistence-memory` repos AND `io.casehub.work.testing.InMemoryWorkItemStore` — omitting it causes boot failure: `Unsatisfied dependency for SubCaseGroupRepository`
- Add `@Alternative @Priority(1)` static inner class stub for `WorkloadProvider` — casehub-work ships `JpaWorkloadProvider` which clashes with `CasehubWorkloadProvider` from the engine
- Set `quarkus.quartz.store-type=ram` and `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`
- `QuarkusTestProfile.getEnabledAlternatives()` **replaces** (not appends to) `quarkus.arc.selected-alternatives` — any profile using this method must re-declare all globally required alternatives, including persistence-memory repos and `InMemoryWorkItemStore`

`callerRef` format: `case:{caseId}/pi:{planItemId}` — use `CallerRef.encode()` / `CallerRef.parse()`.

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.
