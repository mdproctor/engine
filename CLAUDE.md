# CLAUDE.md

**Name:** casehub-engine

## Project Type

**Type:** java

**DSL parity:** YAML and Java are peer representations — see [DSL Style Guide](https://raw.githubusercontent.com/casehubio/parent/main/docs/DSL-STYLE-GUIDE.md) §YAML/Java Parity Principle

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
| blog       | project     | lands in `docs/blog/` — promoted at work end |
| plans      | workspace   | stay in workspace permanently |
| design journal | workspace | JOURNAL.md (epic artifact) stays in workspace permanently |
| DESIGN.md  | project     | canonical design doc — `docs/DESIGN.md` in the project repo |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

## Documentation Index

Architecture, module structure, SPI details, and implementation conventions are documented in the references below — not inline here. Read on demand for the task at hand.

| Topic | Document | Section |
|-------|----------|---------|
| Architecture & design decisions | `wksp/ARC42STORIES.md` | §4 Solution Strategy, §10 Decisions |
| Module structure & framework split | `docs/guides/contributor-guide.md` | §Module Structure |
| SPI landscape & placement rules | `docs/guides/contributor-guide.md` | §SPI Architecture |
| Routing pipeline | `docs/guides/contributor-guide.md` | §Routing Architecture |
| Worker execution lifecycle | `docs/guides/contributor-guide.md` | §Worker Execution Lifecycle |
| Planning, compounds, adaptation | `docs/guides/contributor-guide.md` | §Planning Module Architecture |
| CDI & dependency injection | `docs/guides/contributor-guide.md` | §CDI Conventions |
| Test patterns & gotchas | `docs/guides/contributor-guide.md` | §Test Conventions |
| Persistence & tenancy | `docs/guides/contributor-guide.md` | §Tenancy Enforcement |
| Cross-repo dependencies | `docs/guides/contributor-guide.md` | §Dependencies |
| App-level usage, APIs, quick start | `docs/guides/consumer-guide.md` | — |
| DSL conventions | `docs/guides/yaml-dsl-conventions.md` | — |
| Platform boundaries & ownership | remote: [PLATFORM.md](https://raw.githubusercontent.com/casehubio/parent/main/docs/PLATFORM.md) | — |
| Platform discovery index | remote: [INDEX.md](https://raw.githubusercontent.com/casehubio/parent/main/docs/INDEX.md) | — |
| Protocol index | remote: [FOUNDATION-INDEX.md](https://raw.githubusercontent.com/casehubio/garden/main/docs/protocols/casehub/FOUNDATION-INDEX.md) | — |

Convention: `proj/` in workspace reaches the project repo; `wksp/` in the project repo reaches the workspace.

Update the relevant guide in the same session when implementation changes modules, SPIs, or public APIs. Do not defer — drift compounds.

---

## Build & Test

```bash
mvn install -DskipTests -q                                    # install all modules
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test               # full test suite
TESTCONTAINERS_RYUK_DISABLED=true mvn clean test -pl planning  # single module
```

Quarkus version: `3.32.2` (`version.quarkus.platform` in root `pom.xml`). All ecosystem projects must match — bump together.

Cross-project versions in root `pom.xml`: `version.io.casehub` (`0.2-SNAPSHOT`), `version.io.casehub.ledger` (`0.2-SNAPSHOT`).

GitHub Packages: `<repositories>` with `id=github` at `https://maven.pkg.github.com/casehubio/*`. CI uses `server-id: github` + `GITHUB_TOKEN`.

**Publishing:** `maven.deploy.skip=false` is the default — the root parent POM IS published. Modules that should not be published override in their own `<properties>`.

---

## Generated YAML Records

YAML deserialization records (`io.casehub.api.model.converter.yaml`) are generated at build time by `CasehubRecordCodegen` (codegen module). The generator reads `schema/src/main/resources/schema/CaseDefinition.yaml` + `schema/src/main/resources/schema/yaml-record-mappings.yaml` and emits Java records to `api/target/generated-sources/yaml-records/`.

To add a new YAML record field: add the property to `CaseDefinition.yaml` (if schema-sourced) and/or add the field to `yaml-record-mappings.yaml` (type overrides, aliases, extra fields). Records regenerate on `mvn compile`.

Hand-written exceptions: `YamlAgentDescriptor` (inner records pattern), `JsonNodeForEachAdapter` (utility).

## No Migration Tooling

This project has no installed instances to migrate. Do not add:

- Flyway or Liquibase dependencies
- SQL migration files in the generic `db/migration/` path. Exception: `casehub-engine-ledger` ships V2000/V2001 at `db/engine-ledger/migration/` (scoped per PP-20260525-607b33)
- `quarkus.flyway.*` or `quarkus.liquibase.*` properties
- JDBC-only dependencies (`quarkus-jdbc-postgresql`, `quarkus-agroal`) unless required for a non-migration reason

Schema is managed by Hibernate directly:
```properties
quarkus.hibernate-orm.schema-management.strategy=drop-and-create
```

---

## Critical Gotchas

These cause silent failures — read before making changes in the relevant area.

- **Inject repos by SPI interface, not concrete class.** `@Inject CaseInstanceRepository`, never `InMemoryCaseInstanceRepository`. Concrete-class injection creates two separate stores → silent tenant mismatches.
- **Test classes must be `*Test.java`, never `*IT.java`.** Failsafe picks up `*IT` instead of surefire → `Tests run: 0` with no error.
- **`@ObservesAsync` is unreliable in `@QuarkusTest`.** Observer methods are silently never invoked. Inject the listener bean and call the observer method directly.
- **JQ evaluates the working layer only.** All JQ expressions evaluate against `context.layer(ContextLayer.WORKING).asJsonNode()`, NOT the full layer document.
- **casehub-ledger on test classpath causes silent timeouts.** If transitive, add `quarkus-jdbc-h2`, configure H2 datasource, add `NoOpLedgerEntryRepository` (`@Alternative @Priority(1)`), and exclude `CaseLedgerEventCapture` + `WorkerDecisionEventCapture` from CDI.
- **`casehub-engine-common-core` index-dependency.** Any test needing `JQEvaluator` must add `quarkus.index-dependency` for this module — it's a library JAR, not auto-discovered.
- **Workers return `WorkerResult`, not `Map`.** `WorkerScope` is a parameter, not a ThreadLocal — cast to `WorkerRuntime` for engine-specific methods.

See `docs/guides/contributor-guide.md` §CDI Conventions and §Test Conventions for the full list.

---

## IntelliJ MCP Tools

Two IntelliJ MCP servers are available (`mcp__intellij__*` and `mcp__intellij-index__*`).
Before using Bash tools, check whether the operation can be performed via IntelliJ — it is
often more correct, faster, and less error-prone (symbol lookup, rename refactoring, diagnostics,
file search).

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose.
