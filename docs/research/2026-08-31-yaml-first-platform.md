# YAML-First Platform — Research Document

**Date:** 2026-08-31
**Status:** Research / Vision
**Covers:** engine#1015, #987, #978, #1016, #1017, #1018, #1019, ops#83, pages#399, pages#400

---

## 1. The Thesis

CaseHub should be deployable with zero hand-written Java. Authors write YAML case definitions — the platform generates data model classes, validates definitions, packages them with the engine runtime, and deploys as containers. Java remains the canonical execution substrate, but the authoring surface is entirely YAML.

This is not a simplification — it is a tier separation. The platform team writes Java (engine, SPIs, worker functions). Application authors write YAML (case definitions, topology, configuration). The generated Java bridges the gap.

---

## 2. Current State

### 2.1 What Exists Today

The engine's YAML pipeline has matured through two recent issues:

**#1015 — yaml-core record adoption.** Replaced 3,200 lines of hand-coded Jackson deserializers with 32+ plain Java records that mirror the YAML shape. Jackson auto-deserializes to records. A single converter class (`YamlCaseDefinitionConverter`) handles domain transforms. yaml-core's `ForEachExpander` and `VariableResolver` are wired in — YAML definitions support `${env.X}` variable resolution and `forEach` template expansion.

**#987 — YAML HTN decomposition tree.** Added `spec.decomposition:` block for explicit HTN trees — compound tasks with guard-gated methods decomposing into leaf tasks. This was the last structural gap in the YAML DSL. Every execution model can now be expressed in YAML:

| Model | YAML surface | How it works |
|-------|-------------|-------------|
| Choreography | `bindings:` with trigger conditions | Reactive — bindings fire on context change |
| Sequential | `planningStrategy: sequential` | One binding at a time |
| GOAP | `decompositionStrategy: goap` + `actions:` | A* search over preconditions/effects |
| LLM decomposition | `decompositionStrategy: llm` + goals | LLM produces task tree |
| Portfolio | `decompositionStrategy: portfolio` + `portfolioConfig:` | Cascading strategies |
| Explicit HTN | `spec.decomposition:` with methods/guards | Author-declared tree (#987) |
| Adaptive | `adaptation: adaptive` | Replan after each step |
| Progress-gated | `adaptation: progress` + `monitoring:` | Replan on divergence |

Additionally:
- **casehub-desiredstate** has its own YAML surface (`yaml/runtime/`), TypeScript DSL (`ts-dsl/`), and annotations model (`annotations/`). It provides reconciliation loop, fault policy, and topology composition.
- **casehub-ops/deployment** provides infrastructure topology node types — load balancer, HA multi-AZ, service mesh, multi-region, DNS failover.
- **scaffold** exists as a deployable microservice template for the engine.

### 2.2 What's Missing

Three gaps separate the current state from YAML-first deployment:

1. **Generated data model** — YAML records are hand-written, not generated from the JSON Schema. Drift between schema and records is manual to catch.
2. **Project structure** — YAML-first applications still require a Maven project. No lightweight descriptor, no CLI, no GitHub template.
3. **Deployment as desired state** — no `casehub-application` NodeSpec that provisions engine apps via the desiredstate reconciliation loop.

---

## 3. Industry Landscape

### 3.1 Schema-First Generation

The dominant pattern for multi-language model parity is schema-first: define once, generate everything else.

**JSON Schema** is the de facto standard for YAML/JSON validation. Tools like [quicktype](https://github.com/quicktype/quicktype) generate Java, TypeScript, Go, Rust, and others from a single schema. The [json-schema-to-typescript](https://www.npmjs.com/package/json-schema-to-typescript) package handles the TS path. For Java, [jsonschema2pojo](https://github.com/joelittlejohn/jsonschema2pojo) generates POJOs from JSON Schema — though [Java records support is still an open feature request](https://github.com/joelittlejohn/jsonschema2pojo/issues/1405) with no implementation.

The [OpenCCF project](https://github.com/openccf/openccf-data-model) demonstrates the pattern cleanly: "the schema is the single source of truth; everything else is generated from it — JSON Schema, SHACL, OWL, Python, and an Excel view are outputs of the model rather than part of it."

**CaseHub already follows this pattern** with its `schema/` module — `CaseDefinition.yaml` (JSON Schema) generates Java schema model classes via jsonschema2pojo. The gap is that the YAML records (the deserialization types) and the TypeScript types are not generated from the same schema.

### 3.2 Typed Configuration Languages

A new generation of configuration languages provides type safety at the definition level:

**[Pkl](https://pkl-lang.org)** (Apple) — JVM-native, compiles to JSON/YAML/property files, class-based syntax, Spring Boot integration, strong IntelliJ support. [GOV.UK chose Pkl over CUE and KCL](https://docs.publishing.service.gov.uk/repos/govuk-infrastructure/architecture/decisions/0022-use-pkl-for-configuration.html) for infrastructure configuration. Generates Java classes natively.

**[KCL](https://www.kcl-lang.io)** (CNCF) — Rust-based, Python-like syntax, constraint-based with `schema` keyword, multi-language SDKs (Rust, Go, Python, Java, Node.js), OCI registry support. Strong cloud-native integration.

**[CUE](https://cuelang.org)** — Types-as-values, commutative and aspect-oriented. Powerful constraint checking but steep learning curve and poor IDE support.

All three solve the same problem: YAML is structurally untyped, and these languages add type safety before YAML is generated. The trade-off is adding a language to the stack. For CaseHub, the conservative path (JSON Schema + custom generators) avoids this cost. Pkl is worth evaluating as a future option given its JVM affinity.

### 3.3 Academic Work

[CloudEval-YAML](https://arxiv.org/abs/2401.06786) (2024) benchmarks LLM-generated YAML for cloud configuration — 1,011 hand-written problems taking 1,200+ human hours. Validates that YAML authoring is a real, measurable skill gap.

[AI-assisted JSON Schema Creation and Mapping](https://arxiv.org/html/2508.05192v1) (2025) demonstrates LLM-driven schema generation and cross-format mapping via the MetaConfigurator tool — visual model editing, validation, code generation, and form generation from JSON/YAML/XML/CSV.

[Type-Constrained Code Generation](https://arxiv.org/abs/2504.09246) (PLDI 2025) shows that type constraints during code generation reduce compilation errors by more than half. Relevant to the record generation pipeline — generated code with correct types by construction.

### 3.4 Model Visualization

No standard exists for visualizing execution model definitions as diagrams. The closest analogues are:
- BPMN viewers (Camunda, Flowable) — process-centric, not case-centric
- Kubernetes topology visualizers — infrastructure graphs, not application behavior
- State machine editors (XState) — state-centric, not HTN/GOAP-aware

CaseHub's multi-model visualization (#1016) is novel territory — one viewer that renders choreography as reactive flow, GOAP as state machine, HTN as guarded tree, and DAG as execution pipeline.

---

## 4. Architecture

### 4.1 Three-Layer Model

```
┌──────────────────────────────────────────────────────────────────┐
│  Layer 1: Schema Pipeline (build time)                           │
│  JSON Schema → Java records + TypeScript types + validation      │
├──────────────────────────────────────────────────────────────────┤
│  Layer 2: Application Model (author time)                        │
│  casehub.yaml descriptor + cases/ + workers/ + shared/           │
├──────────────────────────────────────────────────────────────────┤
│  Layer 3: Deployment Topology (deploy time)                      │
│  desiredstate graph + environment overlays + reconciliation       │
└──────────────────────────────────────────────────────────────────┘
```

Each layer is independently useful:
- Layer 1 alone improves developer experience (no hand-written records)
- Layers 1+2 enable YAML-first authoring with `casehub run` for local dev
- All three enable full production deployment with topology, HA, and reconciliation

### 4.2 Schema Pipeline (Layer 1)

```
CaseDefinition.yaml (JSON Schema)
         │
         ├──→ YAML record generator ──→ YamlCaseDefinition.java, YamlCaseSpec.java, ...
         │    (custom Maven plugin)       (generated to target/generated-sources/)
         │
         ├──→ TypeScript generator  ──→ CaseDefinition.ts, Worker.ts, ...
         │    (#977)                     (generated interfaces + discriminated unions)
         │
         └──→ Validation rules     ──→ Runtime schema validation
              (JSON Schema validator)    (at definition load time)
```

**What gets generated:** Data shape types — records with components matching schema properties, null-safe compact constructors, `@JsonIgnoreProperties`, `@JsonDeserialize` annotations for polymorphic types.

**What stays hand-written:** The converter (`YamlCaseDefinitionConverter`) — domain logic that bridges YAML shape to Java domain semantics. The domain model (`CaseDefinition`, `Binding`, etc.) — has behavior, not just data. Polymorphic deserializers — complex parsing for Trigger, CaseCompletion, etc.

### 4.3 Application Model (Layer 2)

```
my-case-app/
├── casehub.yaml              ← application descriptor
├── cases/
│   ├── incident-response.yaml
│   └── loan-application.yaml
├── workers/
│   └── research-agent.yaml   ← agent/a2a/mcp configuration
└── README.md
```

The `casehub.yaml` descriptor:

```yaml
name: incident-response
version: 1.0.0
namespace: io.acme.incidents

runtime:
  engine: 0.2
  modules: [a2a, react]

config:
  llm:
    provider: ${var.llm_provider:-anthropic}
    model: ${var.llm_model:-claude-sonnet-4-20250514}
    apiKeyEnv: ANTHROPIC_API_KEY
  database:
    type: ${var.database_type:-h2}

dependencies: []    # optional — Maven coordinates for Java extensions
```

No `pom.xml`. No `src/main/java`. The CLI resolves engine version → base image, adds selected modules, copies YAML to classpath resources, builds an OCI container.

**Dependencies** are optional. The zero-Java path has no `dependencies:` block. Adding one enables hybrid mode — YAML-first with a Java extension for custom SPIs or typed bridges.

### 4.4 Deployment Topology (Layer 3)

Deployment is a desiredstate graph — the same reconciliation infrastructure that manages infrastructure manages CaseHub applications.

```
acme-casehub-platform/
├── deployment.yaml              ← desiredstate graph
├── environments/
│   ├── dev.yaml                 ← H2, no LB, local LLM
│   ├── staging.yaml             ← PostgreSQL, 1 replica
│   └── prod.yaml                ← HA multi-AZ, service mesh, 3 replicas
├── apps/
│   ├── incident-response/
│   │   ├── casehub.yaml
│   │   ├── cases/
│   │   │   ├── triage.yaml
│   │   │   ├── escalation.yaml
│   │   │   └── resolution.yaml
│   │   └── workers/
│   │       └── research-agent.yaml
│   ├── loan-processing/
│   │   ├── casehub.yaml
│   │   ├── cases/
│   │   │   ├── application-intake.yaml
│   │   │   ├── credit-assessment.yaml
│   │   │   └── approval-workflow.yaml
│   │   └── workers/
│   │       ├── credit-scorer.yaml
│   │       └── document-analyzer.yaml
│   └── compliance-monitor/
│       ├── casehub.yaml
│       └── cases/
│           ├── aml-screening.yaml
│           └── kyc-verification.yaml
├── shared/
│   ├── capabilities/
│   │   └── common-capabilities.yaml
│   └── lib/
│       └── custom-classifier-1.0.0.jar
└── .github/
    └── workflows/
        └── deploy.yml
```

The deployment graph:

```yaml
# deployment.yaml
nodes:
  - type: casehub-application
    id: incident-response
    spec:
      source: ./apps/incident-response
      runtime: { engine: 0.2, modules: [a2a, react] }
      config:
        llm: { provider: ${var.llm_provider} }
      replicas: ${var.replicas}

  - type: casehub-application
    id: loan-processing
    dependsOn: [shared-db]
    spec:
      source: ./apps/loan-processing
      runtime: { engine: 0.2, modules: [mcp] }

  - type: casehub-application
    id: compliance-monitor
    dependsOn: [shared-db]
    spec:
      source: ./apps/compliance-monitor
      runtime: { engine: 0.2 }

  - type: postgresql
    id: shared-db
    spec:
      version: "16"
      storage: ${var.db_storage}

  - type: load-balancer
    id: api-lb
    dependsOn: [incident-response, loan-processing, compliance-monitor]
    spec:
      algorithm: round-robin
```

Environment overlays:

```yaml
# environments/dev.yaml
variables:
  llm_provider: ollama
  llm_model: llama3
  database_type: h2
  replicas: 1
  db_storage: 1Gi

overrides:
  load-balancer: { enabled: false }
  shared-db: { enabled: false }      # apps use embedded H2
```

```yaml
# environments/prod.yaml
variables:
  llm_provider: anthropic
  llm_model: claude-sonnet-4-20250514
  database_type: postgresql
  replicas: 3
  db_storage: 100Gi

topology:
  - type: ha-multi-az
    id: ha
    spec:
      zones: [eu-west-1a, eu-west-1b, eu-west-1c]
      failover: automatic

  - type: service-mesh
    id: mesh
    dependsOn: [ha]
    spec:
      mtls: strict
      observability: full
```

**What desiredstate provides for free:**
- Reconciliation loop — ensures deployed state matches desired
- TransitionPlanner — dependency-ordered deployment (database before apps, apps before LB)
- Drift detection — if an app crashes, the reconciler re-provisions
- Fault policy — retry, escalate, CBR-learned recovery
- Human approval gates — `PendingApproval` on production changes
- Topology composition — case apps + load balancers + databases + service mesh in one graph

### 4.5 The casehub-application NodeProvisioner

A new NodeSpec + NodeProvisioner in `casehub-ops/deployment`:

1. **Read** `casehub.yaml` from the `source` directory
2. **Discover** case YAML files in `cases/`
3. **Validate** all YAML against the JSON Schema
4. **Resolve** dependencies (engine version → base image, modules → JARs, Maven coords → JARs)
5. **Package** engine runtime + generated classes + YAML + modules → OCI container image
6. **Deploy** the container to the target environment
7. **Health check** — verify engine started, cases registered, REST endpoints responding
8. **Reconcile** — on actual-state drift, re-provision

---

## 5. Visualization

Every execution model needs a visual representation — both at design time (what COULD happen) and at runtime (what DID happen). The graph viewer renders any model using model-specific stencils.

### 5.1 Design-Time Diagrams (from YAML)

| Model | Diagram type | Derived from |
|-------|-------------|-------------|
| Choreography | Reactive flow — bindings as nodes, data-flow edges | `bindings:` + `producedKeys:` → trigger conditions |
| Sequential | Pipeline — linear binding chain | Binding declaration order |
| HTN (explicit) | Tree — compound tasks, guarded methods, leaf tasks | `spec.decomposition:` |
| GOAP | State machine — world states as nodes, actions as edges | `actions:` preconditions/effects |
| DAG | Directed acyclic graph | `DagPlan` structure |

All derivable from the YAML at design time — no runtime data needed.

### 5.2 Runtime Overlays

At runtime, the same diagrams gain execution state:
- Which bindings fired, in what order, with what timing (choreography)
- Which method was selected, which tasks completed (HTN)
- Which actions executed, what state transitions occurred (GOAP)
- Per-node status, duration, failure reasons (DAG)

Source: `EventLog` entries + `ExecutionSnapshotStore` snapshots.

### 5.3 Drill-Down Navigation

Four levels of drill-down, each a different diagram type:

| Level | What you see | Click to drill into |
|-------|-------------|-------------------|
| 0 — Case overview | Capabilities, workers, bindings, execution model badge | Any node → Level 1 |
| 1 — Execution model | Model-specific diagram (flow/tree/state machine/pipeline) | Any task/binding → Level 2 |
| 2 — Worker detail | Worker type, execution policy, agent descriptor, data flow | Binding reference → Level 3 |
| 3 — Binding detail | Trigger, target, outcome policy, lifecycle scope, recovery | — |

YAML ↔ diagram bidirectional sync: click a diagram node → scroll to its YAML definition. Place cursor in YAML → highlight the corresponding diagram element.

### 5.4 Worker Chain Tracing

Workers connect through capabilities and data flow. The diagram traces these chains:
1. Worker A handles capability X, produces output keys `{result, score}`
2. Binding B triggers on `.result != null`, targets capability Y
3. Worker C handles capability Y, receives projected input

Derivable from YAML: `workers[].capabilities` → `bindings[].capability` + trigger condition key references + `producedKeys`.

---

## 6. What Authors Cannot Do Without Java

Some extension points are inherently Java:

| Extension | Why Java | YAML alternative |
|-----------|---------|-----------------|
| Custom `WorkerFunction` body | Arbitrary computation | Use built-in: agent, a2a, mcp, react, SWF |
| Custom routing strategy | SPI implementation | Use built-in: composable, cbr, constraint |
| Custom `ActionRiskClassifier` | Domain-specific risk logic | Use built-in chain with JQ expressions |
| Custom `ContextBridge` | Typed POJO context | Use Map<String, Object> with JQ projections |
| Custom `NodeProvisioner` | Infrastructure-specific deploy | Use built-in container provisioner |
| Custom `FaultPolicy` | Domain-specific recovery | Use built-in CBR + configuration |

The goal is not 100% parity. The goal is that the **common case** — case definition + built-in workers + standard routing + standard deployment — requires zero Java. The moment an author needs something custom, they drop to hybrid mode: same project structure, add a `dependencies:` block with a JAR.

---

## 7. Issue Map

### Foundation (landed)

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #1015 | engine | yaml-core record adoption — 32+ records, ForEach, VariableResolver | Landed |
| #987 | engine | YAML HTN decomposition tree | Landed |

### Schema Pipeline

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #1018 | engine | Schema-driven YAML record generation | Open |
| #977 | engine | TypeScriptWriter — TS interfaces from schema | Open |

### YAML Completeness

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #978 | engine | Epic: YAML DSL completeness | Open (mostly done) |
| #984 | engine | Standalone YAML examples for all models | Open |

### Application Model

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #1017 | engine | Epic: zero-authored-Java deployment | Open |
| #1019 | engine | casehub.yaml project descriptor | Open |

### Deployment

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #83 | ops | casehub-application NodeSpec + NodeProvisioner | Open |

### Visualization

| Issue | Repo | What | Status |
|-------|------|------|--------|
| #1016 | engine | Epic: execution model visualization | Open |
| #399 | pages | HTN diagram with drill-down | Open |
| #400 | pages | Unified case definition diagram | Open |

---

## 8. Execution Order

**Phase 1 — Schema pipeline + examples** (foundation, low risk)
1. #984 — YAML examples for all models (validates the YAML surface is complete)
2. #1018 — Schema-driven record generation (replaces hand-written records)
3. #977 — TypeScript generation (UI types from same schema)

**Phase 2 — Application model** (new surface, medium risk)
4. #1019 — casehub.yaml descriptor + CLI
5. GitHub template repo — fork → edit → deploy quickstart

**Phase 3 — Deployment topology** (infrastructure, high value)
6. ops#83 — casehub-application NodeSpec + Provisioner
7. Environment overlays + `casehub deploy`

**Phase 4 — Visualization** (can run in parallel with Phases 1-3)
8. #1016 / pages#399 / pages#400 — diagram components + drill-down

---

## 9. Open Questions

1. **Pkl vs JSON Schema** — Should we evaluate Pkl as the single source instead of JSON Schema? Pkl has JVM-native codegen, static types at the definition level, and can generate Java classes directly. Trade-off: adds a language, but potentially eliminates the custom record generator entirely.

2. **Dependency resolution** — How does a YAML-first project resolve Maven coordinates without Maven? Options: embedded resolver (Aether), pre-built dependency bundles, or a registry of approved extensions.

3. **Hot reload** — Should `casehub run` support YAML hot-reload in dev mode? Quarkus has live reload. The question is whether YAML case definition changes can be picked up without JVM restart (the CaseDefinitionRegistry supports dynamic registration).

4. **Multi-tenancy** — Can multiple YAML-first applications share a JVM in production? The current architecture assumes one container per app. Shared JVM would require classloader isolation — which we explicitly want to avoid.

5. **Testing** — What does YAML-first testing look like? Scenario-based test definitions in `tests/` that declare input context → expected goal satisfaction? Integration with the engine's existing test infrastructure?

6. **desiredstate overlay semantics** — How do environment overlays compose with desiredstate's existing graph merging? Need to reconcile yaml-core VariableResolver with desiredstate's own overlay mechanism.

---

## 10. References

### Industry

- [quicktype — multi-language code generation from JSON Schema](https://github.com/quicktype/quicktype)
- [jsonschema2pojo — Java records feature request #1405](https://github.com/joelittlejohn/jsonschema2pojo/issues/1405)
- [json-schema-to-typescript — TS type generation](https://www.npmjs.com/package/json-schema-to-typescript)
- [OpenCCF — schema as single source of truth](https://github.com/openccf/openccf-data-model)
- [Pkl vs YAML: typed configuration in 2026](https://radar.firstaimovers.com/pkl-vs-yaml-typed-configuration-enterprise-2026)
- [KCL language features and design](https://www.kcl-lang.io/docs/user_docs/getting-started/intro)
- [GOV.UK ADR: Pkl over CUE and KCL](https://docs.publishing.service.gov.uk/repos/govuk-infrastructure/architecture/decisions/0022-use-pkl-for-configuration.html)
- [YAML schema validation with JSON Schema](https://json-schema-everywhere.github.io/yaml)
- [LinkML — linked data modeling language](https://linkml.io/)

### Academic

- [CloudEval-YAML: practical benchmark for cloud config generation (2024)](https://arxiv.org/abs/2401.06786)
- [AI-assisted JSON Schema creation and mapping (2025)](https://arxiv.org/html/2508.05192v1)
- [Type-constrained code generation — PLDI 2025](https://arxiv.org/abs/2504.09246)
- [Type-guided program synthesis for type correctness (2025)](https://arxiv.org/html/2510.10216v1)

### Internal

- engine#1015 — yaml-core record adoption
- engine#987 — YAML HTN decomposition tree
- engine#978 — YAML DSL completeness epic
- engine#1016 — execution model visualization epic
- engine#1017 — zero-authored-Java deployment epic
- engine#1018 — schema-driven record generation
- engine#1019 — casehub.yaml project descriptor
- ops#83 — casehub-application NodeSpec
- pages#399 — HTN diagram with drill-down
- pages#400 — unified case definition diagram
- desiredstate YAML surface — `yaml/runtime/`, `yaml/deployment/`
- ops/deployment — topology node types
