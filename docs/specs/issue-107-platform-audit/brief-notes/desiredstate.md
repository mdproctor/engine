# Brief Notes — Desired-State Reconciliation Engine

> Source repo: desiredstate (37 leaf modules, 566 Java source files)
> Audit date: 2026-10-02

## Capability Areas (descriptive names)

### 1. Declarative Reconciliation Engine
Kubernetes controller pattern applied to arbitrary domains: declare desired state as a DAG, observe actual state, reconcile the gap continuously. Unlike Kubernetes, nodes can require human approval, transition plans can delegate to casehub-engine case orchestration, and fault policies can learn from outcomes via CBR.
- **Differentiation:** Domain-agnostic reconciliation with human-in-the-loop. No existing framework combines topological graph reconciliation, human gating (per-action: provision-only, deprovision-only, all), approval workflows, and case-based fault learning in a single engine. Kubernetes controllers are infrastructure-only and fully automated. Terraform is plan-apply with no continuous loop.
- **Extent:** 37 modules. `ReconciliationLoop` per-tenant with event-driven + periodic resync triggers, per-NodeType scheduling, debounced event processing. `TransitionPlanner` with Kahn's topological sort (prune-before-grow), orphan spec resolution from previous desired graph. Dual execution models: `SimpleTransitionExecutor` (sequential) and `ParallelTransitionExecutor` (layer-based virtual threads with per-NodeType semaphore rate limiting). Full OTel tracing on all phases.

### 2. Multi-Surface Graph Declarations
Three equivalent surfaces for declaring desired-state graphs — annotations, YAML, and TypeScript DSL — all producing the same `GoalCompiler` beans. Cross-surface rules allow Java `@GraphRule` annotations to apply to TypeScript-defined graphs.
- **Differentiation:** No comparable framework offers three declaration surfaces with cross-surface rule application. Most IaC tools are single-language (Terraform/HCL, Pulumi/TypeScript, Ansible/YAML). CaseHub's approach lets domain experts use YAML, developers use annotations, and frontend teams use TypeScript, with shared validation and graph rewriting.
- **Extent:** Annotations: `@DesiredState`, `@Node`, `@DeclareNode`, `@DependsOn`, `@GraphRule` (pattern matching with `@Match`, `@DirectDep`, `@Reaches`, `@NotExists`, cardinality constraints), `@GraphInvariant` (universal quantification). YAML: `META-INF/desiredstate/*.yaml` with `NodeSpecRegistry`, `VariableResolver`, reusable modules with parameters and cross-module references. TypeScript: `defineGraph()`/`defineLifecycle()`/`node()` helpers producing JSON envelopes. Build-time validation (unknown types, dangling dependencies, cycles) on all surfaces.

### 3. YAML Plugin System
Self-healing resource types declared entirely in YAML — no Java required. Each plugin file declares spec schema, actual-state detection, provisioning steps, fault policies, CBR features, and RAS situations. Built-in step primitives (`rest-call`, `json-extract`, `compare-state`, `assert`) plus custom compound primitives.
- **Differentiation:** Plugin authors need zero Java knowledge. Complete resource lifecycle (provision + actual-state + fault recovery + CBR learning + situation detection) in a single YAML file. Declarative test framework (`*.test.yaml`) with embedded WireMock and shell sandbox infrastructure. No comparable system exists — Kubernetes operators require Go/Java; Terraform providers require Go.
- **Extent:** `PluginModel`, `PluginParser`, `PluginValidator` (Levenshtein suggestions for typos), `YamlPluginProvisioner`, `YamlPluginActualStateAdapter`. Build-time validation cross-references `@NodeTypeId` registry. `PluginTestExtension` (JUnit5) discovers test YAML and exercises real provisioners. Two infrastructure types: `http-mock` (WireMock) and `shell-sandbox` (temp directory).

### 4. Fault Policy & CBR Learning Loop
Multi-tier fault escalation with case-based reasoning. Faults trigger policies that mutate the desired graph (add review nodes, escalate to humans). CBR pipeline retrieves similar past configurations, adapts them, applies the result, and feeds outcomes back via CloudEvents.
- **Differentiation:** Fault response that learns. `ThresholdFaultPolicy` with graph-presence guards for multi-tier escalation (retry → AI review → human review). CBR retrieve-adapt-apply-revise cycle with outcome tracking (SUCCEEDED, FAILED, SKIPPED, REJECTED, SUPERSEDED, ALREADY_PRESENT). No IaC tool has learning-based fault recovery.
- **Extent:** `FaultPolicyEngine` with multi-policy merge and conflict detection. `CbrFaultPolicy` for fault-triggered CBR. `CbrSituationRecompiler` for situation-triggered CBR. `CbrProposalTracker` matching affected nodes to outcomes. `ThresholdFaultPolicy` with builder API, namespace-scoped `FaultCountStore` (3-tier CDI priority: custom > JPA > in-memory), lazy eviction via `FaultCountEvictionListener`.

### 5. Multi-Phase Lifecycle Management
Multi-phase deployments without re-invoking `GoalCompiler`. `CompilationResult.Lifecycle` carries a sequence of phases, each with a graph and completion condition. `LifecycleManager` orchestrates phase transitions with dual CAS for concurrent safety.
- **Differentiation:** Declarative phased deployments with situation-driven replanning. `SituationRecompiler` can replace the entire lifecycle sequence mid-execution. Dual CAS on both the phase map and the reconciliation loop's desired graph prevents concurrent update corruption.
- **Extent:** `CompletionCondition` SPI with built-ins (`allPresent`, `never`). Per-domain lifecycle tracking in cross-domain composition. Node lifecycle state machines via `NodeLifecycleDefinition` + `StatefulNodeProvisioner` (OrcStateMachine enforcement, custom CloudEvents on state entry/exit).

### 6. Cross-Domain Composition
Push-model multi-domain graph composition. Each domain compiles its own goals, declares provides/requires for cross-domain edges, and registers with the engine. Three modes: flattened (single merged graph), hierarchical (meta-loop with one domain-level node per domain), passthrough (0-1 domains).
- **Differentiation:** Domain-isolated composition with shared reconciliation. Domains compile independently (preserving `GoalCompiler<G>` type safety), the engine merges with correct ordering. Domain-specific `SituationRecompiler`s receive only their domain graph — isolation preserved. No equivalent exists in IaC; Crossplane is the closest but lacks human gating and CBR.
- **Extent:** `CrossDomainCompositionEngine`, `DomainRegistration` (provides/requires/readinessCondition/situationRecompilers), validation (duplicate provides, unsatisfied requires, circular deps via Kahn's, node ID collisions). `TenantCompositionState` immutable record with `recomposeLock` serialization.

### 7. Dual-Framework Support (Quarkus + Spring Boot)
Core reconciliation engine extracted to framework-neutral runtime-core module. Quarkus and Spring Boot wiring modules bridge framework types to core constructors. Same behaviour on both frameworks — Quarkus at build time, Spring at startup.
- **Differentiation:** Platform-wide pattern (#139) applied to the most complex module. 24 core classes in runtime-core with zero CDI/Spring imports. Both frameworks auto-configure all declaration surfaces (annotations, YAML, TypeScript, plugins). Spring Boot Discovery classes provide shared Jandex scanning.
- **Extent:** runtime-core (24 classes), runtime (14 CDI bridges), runtime-spring (auto-config + 6 Discovery classes). persistence-jpa-common (shared entities), persistence-jpa (Quarkus), persistence-spring-jpa (Spring). annotations/core + spring, yaml/spring, ts-dsl/spring. spring-integration-test validates composition on Spring.

### 8. Eidos Organization Bridge
`OrgGoalCompiler` compiles organizational unit hierarchies and agent relationships from casehub-eidos into desired-state graphs with parent-child dependency edges. Enables reconciliation of organizational structure as a desired-state problem.
- **Differentiation:** Org model as infrastructure. Organizational changes (new teams, role reassignments, reporting line changes) are reconciled with the same fault tolerance, approval gates, and CBR learning as infrastructure provisioning. No comparable capability exists.
- **Extent:** 6 Java files. `OrgGoalCompiler`, `OrgUnitNodeSpec`, `OrgRelationshipNodeSpec`, `OrgNodeTypes`, `OrgBootstrap`. Depends on `casehub-eidos` org API.

## Cross-Repo Patterns

- **engine-adapter**: Transition plans become casehub-engine cases with prune/grow Worker(Workflow) phases. `desiredstate:dispatch` and `desiredstate:replan` registered via `CallableDispatchRegistry`. Human-gated nodes become `HumanTaskTarget` case bindings.
- **work-adapter**: `WorkItemPendingApprovalHandler` creates WorkItems for approval gates via `WorkItemCreator` SPI from casehub-work.
- **ras-adapter**: Bridges faults and drift to casehub-ras situation detection. `NodeFaultGanglion`, `PersistentDriftGanglion`. 3 situation definitions: repeated-failure (Streak 3), persistent-drift (Count 3), zone-degradation (Rate 60% of 10).
- **eidos-adapter**: Org model from casehub-eidos compiled into desired-state graphs.
- **platform**: Uses `PreferenceProvider` for runtime config, tenancy types, governance primitives.
- **ops**: Deployment desired-state domain uses desiredstate-api graph SPIs.

## Vision

- Graph persistence module (currently in-memory only)
- Proper case completion observation for CaseTransitionExecutor (currently optimistic V1)
- Multi-cluster orchestration (currently single-runtime only)
- Desired-state reconciliation for IoT devices (#1 — closed, demonstrated via iot repo integration)