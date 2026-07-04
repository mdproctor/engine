# Universal Routing Strategy Audit — engine#634

**Date:** 2026-07-02
**Scope:** Every routing decision point across the casehub platform (engine, work, qhorus, eidos, connectors)
**Purpose:** Phase 1 of engine#634 — systematic audit before design

---

## 1. casehub-engine

### 1.1 AgentRoutingStrategy — "Which worker instance handles a task?"

**Interface:** `io.casehub.api.spi.routing.AgentRoutingStrategy`
**Module:** `casehub-engine-api`

```java
String id();
Uni<AgentAssignment> select(AgentRoutingContext context, List<AgentCandidate> candidates);
```

**Result type:** `AgentAssignment` — sealed interface:
- `Assigned(workerId)` — worker selected
- `Unresolvable()` — no candidate can handle it
- `EscalateToOversight(capabilityName, reason)` — needs human oversight

**Selection mechanism:** CDI `@Alternative @Priority(N)` — highest priority wins. `@DefaultBean` yields to any `@ApplicationScoped` or `@Alternative` implementation.

**Implementations:**

| Class | Priority | Module | Description |
|-------|----------|--------|-------------|
| `LeastLoadedAgentStrategy` | `@DefaultBean` (lowest) | runtime | Selects candidate with fewest running Quartz jobs |
| `TrustWeightedAgentStrategy` | `@Alternative @Priority(1)` | casehub-engine-ledger | Four-phase trust maturity model with blended trust + workload scoring |
| `SemanticAgentRoutingStrategy` | `@Alternative @Priority(2)` | casehub-engine-ai | Embedding-based semantic re-ranking. Score = semantic(0.4) + trust(0.36) + workload(0.24) |
| `DispositionAwareRoutingStrategy` | `@Alternative @Priority(2)` | quarkmind | Game-context-dependent disposition multiplier (0.8–1.2) over trust classification |

**Call sites:**
1. `CaseContextChangedEventHandler.publishWorkerSchedule()` — primary dispatch path. Builds candidates via `AgentCandidateFactory`, filters excluded agents from `_outcomes.<bindingName>.excludedAgents`, then calls `agentRoutingStrategy.select()`. Handles all three outcomes: Assigned → schedule, Unresolvable → tryProvision(), EscalateToOversight → handleEscalation().
2. `DefaultWorkOrchestrator.doSubmit()` — Tier 1 orchestration path. Same pattern: build candidates, route, then publish `WorkerScheduleEvent`.

**Context record:** `AgentRoutingContext(UUID caseId, String capabilityName, Map<String,Object> caseContext)`

**Pluggable:** Yes — consumer provides `@ApplicationScoped @Alternative @Priority(N)` where N > existing implementations.

---

### 1.2 ImplementationRoutingStrategy — "Which binding(s) handle a capability?"

**Interface:** `io.casehub.api.spi.routing.ImplementationRoutingStrategy`
**Module:** `casehub-engine-api`

```java
String id();
Uni<ImplementationSelection> select(ImplementationRoutingContext context, List<ImplementationCandidate> candidates);
```

**Result type:** `ImplementationSelection` — sealed interface:
- `Selected(List<String> bindingNames)` — specific bindings chosen (non-empty enforced)
- `RunAll()` — run all eligible bindings
- `RunNone()` — skip all

**Implementations:**

| Class | Priority | Module | Description |
|-------|----------|--------|-------------|
| `NoOpImplementationRoutingStrategy` | `@DefaultBean` | runtime | Always returns `RunAll()` |
| `TrustWeightedImplementationRoutingStrategy` | `@Alternative @Priority(1)` | casehub-engine-ledger | Adapts `ImplementationCandidate` to `AgentCandidate`, runs through `TrustCandidateClassifier`. All-bootstrap with equal scores → `RunAll()`. Qualified → selects single winner. Has `fallbackBinding` support from `TrustRoutingPolicy`. |

**Call site:** `PlanningStrategyLoopControl.applyImplementationRouting()` — groups gated-eligible bindings by capability name. Single-binding groups pass through. Multi-binding groups consult the strategy. Runs at step 3.5 (after stage lifecycle evaluation, before `PlanningStrategy.select()`).

**Selection:** Single CDI bean, `@DefaultBean` displacement.

**Pluggable:** Yes — consumer provides `@ApplicationScoped @Alternative @Priority(N)`.

---

### 1.3 WorkerExecutionRoutingStrategy — "Which backend executes a function?"

**Interface:** `io.casehub.engine.common.spi.scheduler.WorkerExecutionRoutingStrategy`
**Module:** `casehub-engine-common`

```java
Optional<WorkerExecutionManager> select(List<WorkerExecutionManager> candidates, Worker worker, Capability capability, String tenancyId);
```

**Implementations:**

| Class | Priority | Module | Description |
|-------|----------|--------|-------------|
| `FirstSupportedRoutingStrategy` | `@DefaultBean` | runtime | Iterates `@WorkerBackend` managers by `@Priority` (descending), returns first where both `supports(capabilityName, tenancyId)` and `canExecute(workerFunction)` return true |

**Call site:** `CompositeWorkerExecutionManager.submit()` — collects all `@WorkerBackend` `WorkerExecutionManager` beans sorted by `@Priority` (descending), delegates to `routingStrategy.select()`.

**Pluggable:** Yes — consumer provides `@ApplicationScoped @Alternative @Priority(N)`.

---

### 1.4 PlanningStrategy — "Which eligible bindings fire and in what order?"

**Interface:** `io.casehub.blackboard.control.PlanningStrategy`
**Module:** `casehub-blackboard`

```java
String getId();
String getName();
Uni<List<Binding>> select(CasePlanModel plan, PlanExecutionContext context, List<Binding> eligible);
```

**Implementations:**

| Class | ID | Description |
|-------|----|-------------|
| `DefaultPlanningStrategy` | `"default"` | Returns all eligible bindings unchanged (choreography — parallel execution possible) |
| `SequentialPlanningStrategy` | `"sequential"` | Returns the first PENDING binding; halts on any non-COMPLETED terminal state |

**Selection mechanism:** Named `id()` lookup. `PlanningStrategyLoopControl` injects `Instance<PlanningStrategy>` and resolves by ID from `CaseDefinition.getPlanningStrategy()` (nullable String, defaults to `"default"`).

**Call site:** `PlanningStrategyLoopControl.evaluate()` — the full pipeline is: binding eligibility → stage gating → ImplementationRouting → **PlanningStrategy** → AgentRouting → Worker scheduling.

**Pluggable:** Yes — consumer adds `@ApplicationScoped` implementation with a unique `getId()` and references it from `CaseDefinition.planningStrategy` in YAML.

---

### 1.5 ListEvaluator — "How are candidateGroups/candidateUsers resolved?"

**Type:** `io.casehub.api.model.evaluator.ListEvaluator`
**Module:** `casehub-engine-api`

Sealed interface with two variants:
- `StaticList(Set<String> values)` — literal pre-defined set, no runtime evaluation
- `JQList(String expression)` — JQ expression evaluated against case context at runtime

**Method:** `List<String> evaluate(JsonNode context, JQEvaluator jqEvaluator)`

**Storage:** `HumanTaskTarget` holds two `ListEvaluator` fields: `candidateGroups` and `candidateUsers`.

**Evaluation chain (YAML → actual groups):**
1. **YAML parsing:** `CaseDefinitionYamlMapper` parses `candidateGroups`/`candidateUsers`. Static strings → `StaticList`. JQ expressions (starting with `.`) → `JQList`.
2. **Builder API:** `HumanTaskTarget.Builder.candidateGroups(Set<String>)` wraps in `StaticList`; `.candidateGroupsExpression(String)` wraps in `JQList`.
3. **Runtime resolution:** `ListExpressionResolver.resolve(CaseInstance, ListEvaluator, fieldName)`:
   - `null` spec → returns null (no restriction)
   - `StaticList` → returns `values()` directly
   - `JQList` → evaluates JQ expression against `caseInstance.getCaseContext().panel(ContextPanel.WORKING).asJsonNode()`. Must produce a JSON array of strings. Empty/non-array/non-string → `RESOLUTION_FAILED` sentinel.
4. **Call site:** `CaseContextChangedEventHandler.publishHumanTaskSchedule()` calls `listExpressionResolver.resolve()` for both fields, then passes resolved sets into `HumanTaskScheduleEvent`.

**Not pluggable.** Sealed type — consumers cannot add new evaluation strategies. Only two variants (static list or JQ expression). A harness author cannot plug in a Drools-based candidateGroups resolver or share escalation logic across human-task and worker routing.

---

### 1.6 AgentCandidateFactory — "How is the candidate pool constructed?"

**Class:** `io.casehub.engine.internal.routing.AgentCandidateFactory`
**Module:** `runtime` — `@ApplicationScoped`, injects `VocabularyRegistry`

```java
List<AgentCandidate> buildCandidates(CaseInstance, CaseDefinition, List<Worker>, Capability, WorkerExecutionManager, CapabilityHealth)
```

**Two-tier matching flow:**
1. **Exact match (fast path):** `worker.capabilityNames().contains(capabilityName)` — if true, worker is a match.
2. **Subsumption fallback:** If no exact match AND worker has an `AgentDescriptor` with grounded capabilities, calls `CapabilityResolver.resolve(descriptor.capabilities(), capabilityName, vocabularyRegistry)`. Uses `VocabularyRegistry.match()` for OWLS-MX style subsumption (Exact, Plugin, Specialization, None). Best-depth-first resolution.

**After matching:** Each matched worker is health-probed via `CapabilityHealth.probe()`. `Unavailable` workers are excluded. An `AgentCandidate` record is constructed with `workerId`, `capabilities`, `runningJobs` (from `executionManager.getActiveWorkCount()`), `health`, and `agentDescriptor`.

**VocabularyRegistry implementations:**
- `NoOpVocabularyRegistry` (`@DefaultBean` in runtime) — exact-match-only semantics
- `CdiVocabularyRegistry` (`@ApplicationScoped` in casehub-eidos-runtime) — full vocabulary grounding with hierarchy, subsumption, and cross-vocabulary equivalence. Displaces `NoOpVocabularyRegistry` when on classpath.

**Not pluggable** as an SPI — concrete `@ApplicationScoped` class. Extension points are `CapabilityHealth` and `VocabularyRegistry`.

---

### 1.7 CapabilityHealth — "Is an agent healthy enough to route to?"

**Interface:** `io.casehub.eidos.api.CapabilityHealth`
**Module:** `casehub-eidos-api`

```java
CapabilityStatus probe(AgentDescriptor descriptor, String capabilityTag, ProbeContext context);
```

**Result type:** `CapabilityStatus` — sealed interface with 6 outcomes:
- `Ready` — fully operational
- `Degraded(reason, detail)` — operational but impaired
- `Unavailable(reason)` — cannot handle requests
- `EpistemicallyWeak(domain, confidence)` — low epistemic confidence in domain
- `Excluded(domain, source, declineCount)` — learned exclusion from decline history
- `BehavioralViolation(violations)` — compliance violations detected

**Reactive mirror:** `ReactiveCapabilityHealth` with `Uni<CapabilityStatus> probe(...)`.

**Implementations:**

| Class | Priority | Module | Description |
|-------|----------|--------|-------------|
| `NoOpCapabilityHealth` | `@DefaultBean` | runtime | Returns `Ready` for all probes |
| `DefaultCapabilityHealth` | `@DefaultBean` | casehub-eidos-runtime | Full 6-step evaluation pipeline (see §3.5 below) |

**How health feeds into routing:**
- `AgentCandidateFactory.buildCandidates()` calls `capabilityHealth.probe()` for each worker with an `AgentDescriptor`
- `Unavailable` → worker **excluded** from candidate list entirely (with warning log)
- `EpistemicallyWeak` → mapped to `AgentHealth.EPISTEMICALLY_WEAK` — included but strategies may demote
- `Degraded` → mapped to `AgentHealth.DEGRADED` — included but strategies may demote
- `Ready`, `Excluded`, `BehavioralViolation` → mapped to `AgentHealth.READY`
- Workers without an `AgentDescriptor` skip the probe entirely and get `AgentHealth.READY`

**Pluggable:** Yes via CDI. `DefaultCapabilityHealth` is `@DefaultBean` in eidos-runtime, which displaces `NoOpCapabilityHealth` (also `@DefaultBean`) in runtime.

---

### 1.8 ActionRiskClassifier — "Should a worker action be gated for human approval?"

**Interface:** `io.casehub.api.spi.ActionRiskClassifier`
**Module:** `casehub-engine-api`

```java
RiskDecision classify(PlannedAction action, ClassificationContext context);
```

**Reactive mirror:** `ReactiveActionRiskClassifier`

**Result type:** `RiskDecision` — sealed:
- `Autonomous` — proceed without human review
- `GateRequired(reason, reversible, candidateGroups, expiresIn, scope)` — human approval required

**Note:** `GateRequired.candidateGroups` is a routing decision — determines WHO reviews the consequential action. Currently uses `List<String>` directly (not `ListEvaluator`).

**CDI Qualifier:** `@RiskClassifier`

**Composition:** `ChainedReactiveActionRiskClassifier` discovers all `@RiskClassifier` beans — **most restrictive wins** (fewest candidateGroups beats more; GateRequired beats Autonomous). Classifier failure → fail-safe `GateRequired`.

**Implementations (6 consumer classifiers + 1 chain):**

| Class | Module |
|-------|--------|
| `ChainedReactiveActionRiskClassifier` | api (chain orchestrator) |
| `DevtownActionRiskClassifier` | casehub-devtown |
| `AmlActionRiskClassifier` | casehub-aml |
| `ClinicalActionRiskClassifier` | casehub-clinical |
| `LifeActionRiskClassifier` | casehub-life |
| `SocActionRiskClassifier` | casehub-soc |
| `IoTActionRiskClassifier` | casehub-iot |

**Call site:** `WorkflowExecutionCompletedHandler` calls `ReactiveActionRiskClassifier.classify()` when a worker returns a `PlannedAction`. `GateRequired` → creates a WorkItem via `ActionGateWorkItemHandler`.

**Pluggable:** Yes — consumer provides `@RiskClassifier @ApplicationScoped implements ActionRiskClassifier`. Multiple classifiers compose automatically.

---

### 1.9 TrustRoutingPolicyProvider — "What trust thresholds apply per capability?"

**Interface:** `io.casehub.api.spi.routing.TrustRoutingPolicyProvider`
**Module:** `casehub-engine-api`

```java
TrustRoutingPolicy forCapability(String capabilityName);
```

**`TrustRoutingPolicy` record:** `threshold`, `blendFactor`, `borderlineMargin`, `minimumObservations`, `qualityFloors`, `bootstrapEscalationRequired`, `fallbackBinding`.

**Selection mechanism:** CDI `@Alternative @Priority(N)`.

**Implementations (7):**

| Class | Module |
|-------|--------|
| `DefaultTrustRoutingPolicyProvider` | casehub-engine-ledger |
| `DevtownTrustRoutingPolicyProvider` | casehub-devtown |
| `AmlTrustRoutingPolicyProvider` | casehub-aml |
| `ClinicalTrustRoutingPolicyProvider` | casehub-clinical |
| `LifeTrustRoutingPolicyProvider` | casehub-life |
| `QuarkMindTrustRoutingPolicyProvider` | quarkmind |
| `DeploymentTrustRoutingPolicyProvider` | casehub-ops |

**Pluggable:** Yes — consumer provides `@Alternative @Priority(N)`.

---

### 1.10 TrustCandidateClassifier — Shared classification utility

**Class:** `io.casehub.ledger.routing.TrustCandidateClassifier`
**Module:** `casehub-engine-ledger` — `@ApplicationScoped`

Not itself a routing strategy, but the **shared classification engine** used by:
- `TrustWeightedAgentStrategy`
- `SemanticAgentRoutingStrategy`
- `DispositionAwareRoutingStrategy` (quarkmind)
- `TrustWeightedImplementationRoutingStrategy`

**Phase classification:** BOOTSTRAP, QUALIFIED, BORDERLINE, EXCLUDED_PHASE2B, EXCLUDED_PHASE3.

**Decision method:** `decide()` returns:
- `Assigned` — highest score > 0.0
- `EscalateToOversight` — all zero AND any borderline
- `Unresolvable` — all zero, no borderline

---

### 1.11 WorkerFunctionHandler — "Which handler executes a function type?"

**Interface:** `io.casehub.engine.common.internal.executor.WorkerFunctionHandler`
**Module:** `casehub-engine-common`

```java
boolean supports(WorkerFunction function);
Uni<WorkerResult> execute(...);
```

**Selection mechanism:** `DefaultWorkerExecutor` iterates all `Instance<WorkerFunctionHandler>` beans, finds the first where `supports()` returns true. Complementary (not competing) — all are `@ApplicationScoped`.

**Implementations:**

| Class | Module | Handles |
|-------|--------|---------|
| `SyncAgentWorkerFunctionHandler` | runtime | `Sync` and `AgentWorkerFunction` |
| `FlowWorkerFunctionHandler` | casehub-engine-flow | `FlowWorkerFunction` |

**Not a competing strategy** — handlers are complementary. Each handles distinct function types. `WorkerFunction.NONE` (external workers) is handled by no handler matching → the composite executor skips execution.

---

### 1.12 OutcomePolicy — "How are non-success worker outcomes routed?"

**Type:** `io.casehub.api.model.OutcomePolicy`
**Module:** `casehub-engine-api` — record, per-binding configuration

```java
OutcomePolicy(OutcomeAction onDecline, OutcomeAction onFailure, OutcomeAction onExpired, int maxRerouteAttempts)
```

**`OutcomeAction` enum:** `REROUTE`, `FAULT`

**Not an SPI** — per-binding data configuration. On a DECLINED/FAILED/EXPIRED outcome, the engine either:
- `REROUTE` — writes failure state to `_outcomes.<bindingName>` and re-fires `CONTEXT_CHANGED` for agent exclusion re-routing
- `FAULT` — publishes `CASE_STATUS_CHANGED(FAULTED)` + `WORKER_OUTCOME_RESOLVED(FAULT)`

**Agent exclusion flow:** When `REROUTE` fires, `WorkflowExecutionCompletedHandler` writes `excludedAgents[]` to `_outcomes`. On next context change, `CaseContextChangedEventHandler.publishWorkerSchedule()` filters excluded agents from the candidate list. When all excluded → `handleAllCandidatesExhausted()` writes `REROUTES_EXHAUSTED`.

---

### 1.13 MeshParticipationStrategy — "What is a worker's mesh participation level?"

**Interface:** `io.casehub.api.model.mesh.MeshParticipationStrategy`
**Module:** `casehub-engine-api`

```java
MeshParticipation determineParticipation(Worker worker, CaseContext context);
```

**`MeshParticipation` enum:** `ACTIVE`, `REACTIVE`, `SILENT`

**Default:** Always `ACTIVE`.

**Pluggable:** Yes — part of agent mesh configuration.

---

### 1.14 ConflictResolver — "How are concurrent context writes merged?"

**Type:** `io.casehub.api.model.ConflictResolver`
**Module:** `casehub-engine-api` — static utility

**Strategies:** `LAST_WRITER_WINS` (default), `FIRST_WRITER_WINS`, `FAIL`, `DEEP_MERGE`

Per-binding configuration via `Binding.conflictResolverStrategy`. Used by both `WorkflowExecutionCompletedHandler` (worker output) and `PlanItemCompletionApplier` (humanTask output).

---

### 1.15 ContextDiffStrategy — "How are context changes diff'd?"

**Interface:** `io.casehub.api.spi.ContextDiffStrategy`
**Module:** `casehub-engine-api`

**Selection mechanism:** Config-based — `casehub.engine.diff-strategy` property (`none` | `top-level` | `json-patch`, default `none`). A `@Produces @DefaultBean` producer instantiates the chosen POJO. Consumer `@ApplicationScoped` impl wins automatically via CDI.

**Implementations:**

| Class | Config value |
|-------|-------------|
| `NoOpContextDiffStrategy` | `none` |
| `TopLevelContextDiffStrategy` | `top-level` |
| `JsonPatchContextDiffStrategy` | `json-patch` |

---

### 1.16 Engine Routing Pipeline — Complete Ordering

The engine has a layered routing pipeline for capability dispatch:

```
 1. Binding eligibility (condition/trigger evaluation)
 2. Stage gating (StageLifecycleEvaluator)
 3. CapabilityHealth probing (health gate — Unavailable excluded)
 3.5. ImplementationRoutingStrategy (which binding(s)?)
 4. PlanningStrategy (which bindings fire, in what order?)
 5. AgentCandidateFactory (which workers match? — exact + subsumption)
 6. Agent exclusion filtering (_outcomes.excludedAgents)
 7. AgentRoutingStrategy (which worker gets the task?)
    └── falls to tryProvision() if Unresolvable
    └── fires escalation event if EscalateToOversight
 8. WorkerExecutionRoutingStrategy (which backend runs it?)
 9. WorkerFunctionHandler (which handler executes?)
10. ActionRiskClassifier (gate before applying output?)
11. OutcomePolicy (reroute or fault on non-success?)
12. ConflictResolver (merge strategy for context writes)
```

For humanTask bindings, the path diverges at step 4:
```
 4. ListExpressionResolver resolves candidateGroups/candidateUsers
 5. HumanTaskScheduleEvent published with resolved groups
 6. HumanTaskScheduleHandler creates WorkItem
```

---

## 2. casehub-work

### 2.1 WorkerSelectionStrategy — "Which worker is auto-assigned a task?"

**Interface:** `io.casehub.work.api.spi.WorkerSelectionStrategy`
**Module:** `casehub-work-api`

```java
AssignmentDecision select(SelectionContext context, List<WorkerCandidate> candidates);
default Set<AssignmentTrigger> triggers();  // returns all triggers by default
```

**`AssignmentDecision` record:** `assigneeId`, `candidateGroups`, `candidateUsers`. Null fields mean "no change". Factory methods: `noChange()`, `assignTo(id)`, `narrowCandidates(groups, users)`.

**Strategy selection (two-tier):**
1. CDI `@Alternative` — any non-built-in `WorkerSelectionStrategy` alternative bean wins
2. Config fallback — `casehub.work.routing.strategy` property: `"least-loaded"` (default), `"claim-first"`, `"round-robin"`

**Built-in implementations:**

| Class | CDI | Description |
|-------|-----|-------------|
| `LeastLoadedStrategy` | `@ApplicationScoped` | Pre-assigns to candidate with fewest active items. Default. |
| `ClaimFirstStrategy` | `@Alternative @Priority(0)` | Returns `noChange()` — pool stays open for manual claim. |
| `RoundRobinStrategy` | `@ApplicationScoped` | Sequential rotation via `RoutingCursorStore`. Cluster-safe with OCC. |
| `SemanticWorkerSelectionStrategy` | `@Alternative @Priority(1)` | AI-based scoring via `SkillProfileProvider` + `SkillMatcher`. Falls back to `LeastLoadedStrategy` on failure or below-threshold. Auto-activates when `quarkus-work-ai` is on classpath. |

**Orchestrator:** `WorkItemAssignmentService` (`runtime/service/`)
- `assign(WorkItem, AssignmentTrigger)` — resolves active strategy, builds candidates, delegates to `WorkBroker.apply()`, applies decision to WorkItem
- `activeStrategy()` — CDI alternative lookup first, then config switch
- `resolveCandidates(WorkItem)` — parses `candidateUsers` into `WorkerCandidate` list, resolves `candidateGroups` via `WorkerRegistry`, populates `activeWorkItemCount` via `WorkloadProvider`, filters excluded users via `ExclusionPolicy`

**Call sites for `assign()`:**
- `WorkItemService.create()` — trigger `CREATED`
- `WorkItemService.delegate()` — trigger `DELEGATED`
- `WorkItemService.declineDelegation()` — trigger `DELEGATION_DECLINED`
- `WorkItemService.release()` — trigger `RELEASED`
- `WorkItemService.escalate()` — trigger `SLA_ESCALATED`
- `ExpiryLifecycleService.executeEscalateTo()` — trigger `SLA_ESCALATED`

**Pluggable:** Yes — consumer adds `@ApplicationScoped` implementation with a unique strategy name and sets it in config, or uses `@Alternative` to override entirely.

---

### 2.2 SlaBreachPolicy — "What happens when an SLA is breached?"

**Interface:** `io.casehub.work.api.spi.SlaBreachPolicy`
**Module:** `casehub-work-api`

```java
BreachDecision onBreach(SlaBreachContext context);
```

**`SlaBreachContext` record:** `BreachType`, `BreachedTask`, `Path scope`, `Preferences`
**`BreachType`:** `CLAIM_EXPIRED` or `COMPLETION_EXPIRED`

**`BreachDecision`** — sealed interface with 5 variants:
- `Fail(String reason)` — terminates WorkItem with EXPIRED status
- `EscalateTo(Set<String> groups, Duration deadline)` — **re-routes to new candidate groups**, resets deadline
- `Extend(Duration by)` — pushes active deadline forward without status change
- `Exhausted(String reason)` — transitions to terminal ESCALATED status
- `Chained(primary, fallback)` — tries primary, falls back on execution failure

**Note:** `EscalateTo` IS a routing decision — it changes the candidateGroups on the WorkItem and feeds back into the `WorkerSelectionStrategy` pipeline via `assignmentService.assign(item, AssignmentTrigger.SLA_ESCALATED)`.

**Default:** `NoOpSlaBreachPolicy` (`@DefaultBean @ApplicationScoped`) — returns `Fail("no-sla-breach-policy-configured")`.

**Call sites:**
- `ExpiryLifecycleService.checkExpired()` — batch scan for completion breaches
- `ExpiryLifecycleService.checkClaimDeadlines()` — batch scan for claim breaches
- `ExpiryLifecycleService.expireItem(UUID)` — single-item Quartz timer for completion breach
- `ExpiryLifecycleService.processClaimDeadline(UUID)` — single-item Quartz timer for claim breach

**Pluggable:** Yes — consumer provides `@ApplicationScoped` implementation that displaces `@DefaultBean`.

---

### 2.3 ClaimSlaPolicy — "How is the pool deadline computed?"

**Interface:** `io.casehub.work.api.spi.ClaimSlaPolicy`
**Module:** `casehub-work-api`

```java
Instant computePoolDeadline(ClaimSlaContext context);
```

**Four built-in implementations** (all in `io.casehub.work.core.policy`):

| Class | Description |
|-------|-------------|
| `ContinuationPolicy` | Remaining pool time carries forward (default) |
| `FreshClockPolicy` | Full pool SLA resets on every return to pool |
| `SingleBudgetPolicy` | Hard deadline from submission, never moves |
| `PhaseClockPolicy` | Each claimant gets full time; hard total cap above |

**Pluggable:** Yes — `@Alternative @Priority(1)` replaces the config-selected built-in.

---

### 2.4 WorkBroker — Capability Filtering + Trigger Gating

**Class:** `io.casehub.work.core.strategy.WorkBroker`
**Module:** `casehub-work-core` — `@ApplicationScoped`, not replaceable

```java
AssignmentDecision apply(SelectionContext, AssignmentTrigger, List<WorkerCandidate>, WorkerSelectionStrategy)
```

Two routing decisions:
1. **Trigger gating:** checks `strategy.triggers().contains(trigger)`. If the trigger is not in the strategy's set, returns `noChange()`.
2. **Capability filtering:** filters candidates to those possessing ALL `requiredCapabilities`. Exact case-sensitive matching.

**Not pluggable** — concrete class, but the strategies it dispatches to are pluggable.

---

### 2.5 WorkloadProvider — "How many active items does a worker have?"

**Interface:** `io.casehub.work.api.spi.WorkloadProvider`
**Module:** `casehub-work-api` — `@FunctionalInterface`

```java
int getActiveWorkCount(String workerId);
```

**Implementation:** `JpaWorkloadProvider` (`@ApplicationScoped`) — counts ASSIGNED, IN_PROGRESS, and SUSPENDED WorkItems per worker via JPA query.

**Usage:** Called by `WorkItemAssignmentService.resolveCandidates()` to populate `WorkerCandidate.activeWorkItemCount()` before candidates are passed to the `WorkerSelectionStrategy`. This is the data source for `LeastLoadedStrategy`'s min-comparator.

**Pluggable:** Yes — `@Alternative @Priority(1)` to provide custom workload source.

---

### 2.6 WorkerRegistry — "Who belongs to a candidate group?"

**Interface:** `io.casehub.work.api.spi.WorkerRegistry`
**Module:** `casehub-work-api`

```java
List<WorkerCandidate> resolveGroup(String groupName);
```

**Default:** `NoOpWorkerRegistry` (`@DefaultBean @ApplicationScoped`) — returns empty list. Groups remain claim-first until a real implementation connects LDAP/Keycloak/etc.

**Usage:** Called by `WorkItemAssignmentService.resolveCandidates()` to expand `candidateGroups` into individual `WorkerCandidate` instances. Each group name is passed to `resolveGroup()`, results are deduplicated, and workload counts are populated.

**Pluggable:** Yes — consumer provides implementation that connects to directory service.

---

### 2.7 ExclusionPolicy — "Is this worker excluded from this task?"

**Interface:** `io.casehub.work.api.spi.ExclusionPolicy`
**Module:** `casehub-work-api`

```java
PolicyDecision check(String userId, String excludedUsers);
```

**Default:** `CommaSeparatedExclusionPolicy` (`@DefaultBean @ApplicationScoped`) — checks if userId appears in comma-separated excludedUsers string.

**Two enforcement points:**
1. **Pre-assignment filtering:** `WorkItemAssignmentService.resolveCandidates()` calls `exclusionPolicy.check()` and removes denied candidates before passing to strategy.
2. **Claim guard:** `WorkItemService.claim()` calls `exclusionPolicy.check(claimantId, item.excludedUsers)`. If denied, records via `BlockedAttemptAuditService` and throws `IllegalStateException`.

**Pluggable:** Yes — consumer provides `@Alternative @Priority(1)` for custom exclusion logic (e.g., `ExpiringExclusionPolicy` for time-window exclusions).

---

### 2.8 InstanceAssignmentStrategy — "How are multi-instance work items assigned?"

**Interface:** `io.casehub.work.api.spi.InstanceAssignmentStrategy`
**Module:** `casehub-work-api`

```java
void assign(List<Object> instances, MultiInstanceContext context);
```

**Four implementations** (all `@Named`, `@ApplicationScoped` in `runtime/multiinstance/`):

| Class | CDI Name | Description |
|-------|----------|-------------|
| `PoolAssignmentStrategy` | `"pool"` | Copies parent's candidateGroups/Users to all children. Default. |
| `ExplicitListAssignmentStrategy` | `"explicit"` | 1:1 mapping from `explicitAssignees` list to instances. |
| `RoundRobinAssignmentStrategy` | `"roundRobin"` | Distributes via the active `WorkerSelectionStrategy`, excluding already-assigned workers per iteration. |
| `CompositeInstanceAssignmentStrategy` | `"composite"` | Chains multiple strategies in order. |

**Selection:** `@Named` CDI qualifier. The multi-instance spawn service resolves the strategy by name from `MultiInstanceConfig`.

**Pluggable:** Yes — consumer adds `@Named @ApplicationScoped` implementation.

---

### 2.9 Semantic Routing Pipeline (AI Module)

Three SPIs compose the semantic routing pipeline in casehub-work:

**a) SkillProfileProvider** — `io.casehub.work.api.spi.SkillProfileProvider`

```java
SkillProfile getProfile(String workerId, Set<String> capabilities);
```

**Implementations:**
- `CapabilitiesSkillProfileProvider` — builds narrative from capability IDs
- `WorkerProfileSkillProfileProvider` — uses stored worker profile descriptions
- `ResolutionHistorySkillProfileProvider` — builds narrative from past resolution history
- `CompositeSkillProfileProvider` — merges multiple providers' profiles

**b) SkillMatcher** — `io.casehub.work.api.spi.SkillMatcher`

```java
double score(SkillProfile workerProfile, SelectionContext context);
```

**Implementations:**
- `EmbeddingSkillMatcher` — cosine similarity via langchain4j `EmbeddingModel`
- `KeywordSkillMatcher` — keyword overlap (example only)

**c) SemanticWorkerSelectionStrategy** — `@Alternative @Priority(1)` — auto-wins over config-selected strategies when `quarkus-work-ai` is on classpath. Uses `SkillProfileProvider` + `SkillMatcher` to score candidates. Below-threshold or failure falls back to `LeastLoadedStrategy`.

---

### 2.10 CapabilityRegistry — "Is this capability tag valid?"

**Interface:** `io.casehub.work.api.spi.CapabilityRegistry`
**Module:** `casehub-work-api`

```java
Set<Capability> capabilities();
default boolean isKnown(Capability tag);
```

Validation mode configured via `casehub.work.capability-validation` (STRICT/WARN/PERMISSIVE).

**Not a routing decision per se**, but gates which capability tags are valid, which indirectly affects capability-based routing in `WorkBroker.filterByCapabilities()`.

**Pluggable:** Yes — `@Alternative @Priority(1)` replaces the permissive default.

---

### 2.11 RoutingCursorStore — "Where is the round-robin cursor?"

**Interface:** `io.casehub.work.core.strategy.RoutingCursorStore`
**Module:** `casehub-work-core`

```java
int acquireNext(String poolHash, int poolSize);  // atomic cursor advance with modulo wrap
```

**Tiered implementations:**

| Class | CDI | Module |
|-------|-----|--------|
| `NoOpRoutingCursorStore` | `@DefaultBean` | core (Tier 0, always returns 0) |
| `JpaRoutingCursorStore` | `@ApplicationScoped` | runtime (Tier 1, JPA with `@Version`-based OCC) |
| `MongoRoutingCursorStore` | `@Alternative @Priority(1)` | persistence-mongodb (Tier 2) |
| `InMemoryRoutingCursorStore` | `@Alternative @Priority(100)` | persistence-memory (Tier 3, tests) |

Used exclusively by `RoundRobinStrategy` to maintain per-pool cursor position. Pool identity is a SHA-256 hash of sorted candidate IDs.

---

### 2.12 NotificationChannel — "How are lifecycle events delivered?"

**Interface:** `io.casehub.work.api.spi.NotificationChannel`
**Module:** `casehub-work-api`

```java
String channelType();
void send(NotificationPayload payload);
```

**Selection:** String-keyed by `channelType()`, matched against DB-stored `WorkItemNotificationRule.channelType`. Routes lifecycle events to the correct delivery mechanism.

**Implementations:** `SlackNotificationChannel` ("slack"), `TeamsNotificationChannel` ("teams").

**Pluggable:** Yes — any `@ApplicationScoped` bean with unique `channelType()` string is auto-discovered.

---

### 2.13 Template Routing — Request-Wins Merge

Templates do NOT introduce a separate routing strategy. `WorkItemTemplateService.createFromTemplate()` uses **request-wins merge semantics**: for every routing-relevant field (`candidateGroups`, `candidateUsers`, `requiredCapabilities`, `scope`, `excludedUsers`), the request value wins if non-null; otherwise the template default is used. The merged request then flows through the standard `WorkItemService.create()` → `WorkItemAssignmentService.assign()` pipeline.

**Template-specific routing concern:** `TemplateExpander` resolves `excludedGroups` to actor IDs via `GroupMembershipProvider.membersOf()` at creation time. The expanded IDs are merged into `excludedUsers`.

**GroupMembershipProvider** (`io.casehub.platform.api.identity.GroupMembershipProvider`, external SPI):
- Default: `NoOpGroupMembershipProvider` (`@DefaultBean @ApplicationScoped`) — returns empty set
- Pluggable: Yes — `@Alternative @Priority(1)` to connect a real directory

---

### 2.14 Queue Membership — Observational Label-Based Routing

The `queues` module provides observational queue routing based on WorkItem labels. Not assignment routing — determines which queues (views) a WorkItem belongs to.

**Mechanism:** `QueueMembershipContext.resolve()` diffs the WorkItem's current labels against `QueueView.labelPattern` definitions. A WorkItem is a member of a queue if any of its label paths match the queue's pattern.

**Not pluggable** at the strategy level — queue membership is deterministic based on labels and patterns. The patterns are configured in `QueueView` entities (persisted, managed via API).

---

### 2.15 casehub-work Routing Pipeline — Complete Ordering

```
 1. WorkItem created with candidateGroups, candidateUsers, requiredCapabilities
    (from engine HumanTaskScheduleHandler or direct API call)
 2. Template merge (if template-based) — request-wins semantics
 3. ExclusionPolicy filtering — removes denied candidates
 4. WorkerRegistry.resolveGroup() — expands groups to individual candidates
 5. WorkloadProvider — populates activeWorkItemCount per candidate
 6. WorkBroker — capability filtering + trigger gating
 7. WorkerSelectionStrategy.select() — auto-assignment decision
    (LeastLoaded | ClaimFirst | RoundRobin | Semantic)
 8. On SLA breach → SlaBreachPolicy.onBreach()
    └── EscalateTo → new candidateGroups, re-enter at step 3
    └── Extend → push deadline
    └── Fail → EXPIRED terminal
    └── Exhausted → ESCALATED terminal
 9. ClaimSlaPolicy — pool deadline computation on release/delegate
10. InstanceAssignmentStrategy — multi-instance distribution (if applicable)
```

---

## 3. casehub-qhorus

### 3.1 ChannelGateway Fan-Out — Message Delivery to Backends

**Class:** `io.casehub.qhorus.runtime.gateway.ChannelGateway`
**Module:** `casehub-qhorus-runtime`

```java
fanOut(UUID channelId, String channelName, OutboundMessage message)
```

Iterates ALL registered `BackendEntry` records for the channel. When the delivery pump is enabled (`casehub.qhorus.delivery.enabled=true`), backends declaring `DeliveryGuarantee.AT_LEAST_ONCE` are skipped by fan-out (handled by the delivery pump instead). All other backends get fire-and-forget delivery on virtual threads.

**Call site:** `MessageService.dispatch()` after message persistence and observer notification.

**This is fan-out delivery, not selection.** All matching backends get the message. `DeliveryGuarantee` bifurcates immediate vs. tracked delivery.

---

### 3.2 ChannelBackend SPI — Backend Type Hierarchy

**Interface:** `io.casehub.qhorus.api.gateway.ChannelBackend`
**Module:** `casehub-qhorus-api`

```java
String backendId();
ActorType actorType();
void open();
void post(OutboundMessage message);
void close();
DeliveryGuarantee deliveryGuarantee();
```

**Sub-interfaces:**
- `AgentChannelBackend` — max 1 per channel, fatal errors
- `HumanParticipatingChannelBackend` — max 1, non-fatal
- `HumanObserverChannelBackend` — unlimited, non-fatal, capped to EVENT

**Implementations:** `QhorusChannelBackend` (internal), `ConnectorChannelBackend` (AT_LEAST_ONCE), `SlackChannelBackend`, `ClaudonyChannelBackend`, `OpenClawChannelBackend`, `AdvisoryChannelBackend`, `DebateChannelBackend`, `ReviewerChannelBackend`, `RecordingChannelBackend`.

**Pluggable:** Yes — any `@ApplicationScoped` CDI bean implementing a sub-interface.

---

### 3.3 DeliveryService — Tracked Delivery Pump

**Class:** `io.casehub.qhorus.runtime.gateway.DeliveryService`
**Module:** `casehub-qhorus-runtime`

Processes channels from `DeliverySignalQueue`. For AT_LEAST_ONCE backends, uses cursor-based batch delivery via `DeliveryBatchExecutor`. Circuit breaker skips unhealthy backends; scheduled reconciler retries every 30s.

**Not pluggable** — concrete class, config-driven via `DeliveryConfig`.

---

### 3.4 ObligorTrustPolicy — Trust-Gated Commitment Routing

**Interface:** `io.casehub.qhorus.api.spi.ObligorTrustPolicy`
**Module:** `casehub-qhorus-api`

```java
boolean permits(ObligorTrustContext ctx);
```

**Call site:** `MessageService.dispatch()` for COMMAND messages with a named target. Rejects if the obligor does not meet the trust threshold.

**Default:** `DefaultObligorTrustPolicy` (`@DefaultBean`) — reads `casehub.qhorus.commitment.min-obligor-trust` config, delegates to `TrustGateService.meetsThreshold()`.

**Pluggable:** Yes — `@DefaultBean` displacement.

---

### 3.5 MessageTypePolicy — Channel Type Enforcement

**Interface:** `io.casehub.qhorus.runtime.message.MessageTypePolicy`
**Module:** `casehub-qhorus-runtime`

```java
validate(Channel, MessageType);   // hard-block gate
advisory(Channel, MessageType);   // warning
```

**Implementation:** `StoredMessageTypePolicy` — hard-enforces only COMMAND and QUERY violations (these create commitments); other types get advisory-only warnings.

**Call site:** `MessageService.dispatch()`

**This is access control, not routing** — determines what CAN flow, not where things GO.

---

### 3.6 AllowedWritersPolicy — Sender ACL

**Class:** `io.casehub.qhorus.runtime.channel.AllowedWritersPolicy`
**Module:** `casehub-qhorus-runtime`

```java
isAllowedWriter(String sender, List<String> allowedWriters, Supplier<List<String>> senderTagsSupplier)
```

Three ACL entry types: exact sender match, `capability:tag` match, `role:agent`/`role:human` match.

**Not pluggable** — concrete `@ApplicationScoped` class. Access control, not routing.

---

### 3.7 ChannelSemantic — Channel Behavior Policies

**Enum:** `io.casehub.qhorus.api.channel.ChannelSemantic`
**Module:** `casehub-qhorus-api`

Values: `APPEND` (default ordered), `COLLECT` (fan-in, atomic delivery), `BARRIER` (join gate), `EPHEMERAL` (transient), `LAST_WRITE` (overwrite-or-reject).

Per-channel configuration. Not pluggable.

---

### 3.8 WatchdogAlertRouter — "Where do alerts get delivered?"

**Interface:** `io.casehub.qhorus.api.watchdog.WatchdogAlertRouter`
**Module:** `casehub-qhorus-api`

```java
List<AlertDeliveryTarget> route(WatchdogAlertEvent event);
```

**Default:** `ConfiguredWatchdogAlertRouter` (`@DefaultBean`) — V1 fan-out to all configured endpoints, no per-condition routing.

**Call site:** `ConnectorAlertBridge.onAlert(@ObservesAsync WatchdogAlertEvent)` — calls `router.route(event)`, sends via `ConnectorService.send()` per target.

**Pluggable:** Yes — `@DefaultBean` displacement.

---

### 3.9 MessageObserver — Post-Dispatch Observer Routing

**Interface:** `io.casehub.qhorus.api.gateway.MessageObserver`
**Module:** `casehub-qhorus-api`

```java
void onMessage(MessageReceivedEvent event);
Set<UUID> channels();   // filter set
ObserverScope scope();  // LOCAL or CLUSTER
```

**Dispatcher:** `MessageObserverDispatcher` — iterates all `@Any Instance<MessageObserver>` beans, applies `channels()` filter, defers to JTA `afterCompletion` when transactional.

**Pluggable:** Yes — any `@ApplicationScoped` CDI bean.

---

### 3.10 InboundNormaliser — "What message type is this inbound message?"

**Interface:** `io.casehub.qhorus.api.gateway.InboundNormaliser`
**Module:** `casehub-qhorus-api`

```java
NormalisedMessage normalise(ChannelRef channel, InboundHumanMessage raw);
```

**Selection:** Per-channel override via `HumanParticipatingChannelBackend.normaliserFor(channelId)`, fallback to `DefaultInboundNormaliser`.

**Implementations:**
- `DefaultInboundNormaliser` (system fallback)
- `SlackInboundNormaliser`
- `ClinicalInboundNormaliser`
- `ConnectorNormaliser` sub-interface (keyed by `connectorId()`)

**Pluggable:** Yes.

---

### 3.11 CommitmentAttestationPolicy — "What trust attestation for this commitment?"

**Interface:** `io.casehub.qhorus.api.spi.CommitmentAttestationPolicy`
**Module:** `casehub-qhorus-api`

```java
Optional<AttestationOutcome> attestationFor(MessageType terminalType, String resolvedActorId, CommitmentContext context);
```

When a commitment is discharged, determines what attestation (SOUND/FLAGGED + confidence) to write to the ledger for Bayesian trust scoring.

**Pluggable:** Yes — `@Alternative @Priority`.

---

### 3.12 InstanceActorIdProvider — "What is this instance's persona-level actor ID?"

**Interface:** `io.casehub.qhorus.api.spi.InstanceActorIdProvider`
**Module:** `casehub-qhorus-api`

```java
String resolve(String instanceId);
```

Maps session-scoped instance IDs to persona-scoped actor IDs, affecting where trust attestations land.

**Default:** `DefaultInstanceActorIdProvider` (identity function).

**Pluggable:** Yes — `@Alternative @Priority`.

---

### 3.13 ConnectorChannelBackend — Inbound Message Channel Routing

**Class:** `io.casehub.qhorus.connector.backend.ConnectorChannelBackend`
**Module:** `casehub-qhorus-connector`

```java
onInboundMessage(@ObservesAsync InboundMessage msg)
```

**Routing flow:**
1. `ConnectorKeyStrategy.deriveKey()` — sender-keyed for SMS/WhatsApp/Email (`externalSenderId`), channel-keyed for Slack/Teams/IRC/Discord (`externalChannelRef`). **Not pluggable** (hardcoded set).
2. `channelService.findByConnectorKey()` — lookup existing channel
3. Fallback to `AutoChannelPolicy.onFirstContact()` — auto-create channel
4. `route(channel, msg)` via `gateway.receiveHumanMessage()`

---

### 3.14 AutoChannelPolicy — "Should a channel be auto-created on first contact?"

**Interface:** `io.casehub.qhorus.connector.backend.AutoChannelPolicy`
**Module:** `casehub-qhorus-connector`

```java
Optional<AutoChannelSpec> onFirstContact(InboundMessage msg, String lookupKey);
```

**Default:** `ConfiguredAutoChannelPolicy` (`@DefaultBean`) — config-driven per-connector (`casehub.qhorus.connector.auto-channel.entries.{connectorId}`).

**Pluggable:** Yes — `@DefaultBean` displacement.

---

### 3.15 Commitment Obligor Assignment

**Class:** `io.casehub.qhorus.runtime.message.CommitmentService`
**Module:** `casehub-qhorus-runtime`

Obligor is determined by the sender at dispatch time (the `target` field from `MessageDispatch`). `delegate(correlationId, delegatedTo)` creates a child commitment with a new obligor.

**Not pluggable** — caller-supplied routing.

---

### 3.16 RateLimiter — Per-Channel/Per-Instance Throttling

**Class:** `io.casehub.qhorus.runtime.channel.RateLimiter`
**Module:** `casehub-qhorus-runtime`

Checked in `MessageService.dispatch()` against `ch.rateLimitPerChannel()` and `ch.rateLimitPerInstance()`.

**Not pluggable** — concrete class. Throttling, not routing.

---

## 4. casehub-eidos

### 4.1 CapabilityResolver — Subsumption-Based Capability Matching

**Class:** `io.casehub.eidos.api.CapabilityResolver` (static utility)
**Module:** `casehub-eidos-api`

```java
static MatchDegree match(AgentCapability capability, String capabilityTag, VocabularyRegistry registry);
static AgentCapability resolve(List<AgentCapability> capabilities, String capabilityTag, VocabularyRegistry registry);
```

**Matching priority:** Exact > Plugin (smallest depth) > Specialization (smallest depth) > None. First-in-list wins at equal depth.

**Call sites:** `DefaultCapabilityHealth.probe()` (step 2), `BehavioralSignalStore` callers, `AgentCandidateFactory` (engine).

**Not pluggable** — static utility, fixed logic.

---

### 4.2 MatchDegree — Semantic Match Classification

**Sealed interface:** `io.casehub.eidos.api.MatchDegree`
**Module:** `casehub-eidos-api`

Variants:
- `Exact` — declared capability == requested capability
- `Plugin(int depth)` — declared subsumes requested (broader than needed)
- `Specialization(int depth)` — requested subsumes declared (narrower than needed)
- `None` — no match

Result type from `CapabilityResolver.match()` and `VocabularyRegistry.match()`. Drives all subsumption-based routing.

---

### 4.3 VocabularyRegistry — Vocabulary-Grounded Subsumption Engine

**Interface:** `io.casehub.eidos.api.VocabularyRegistry`
**Module:** `casehub-eidos-api`

```java
MatchDegree match(String vocabUri, String declaredValue, String requestedValue);
boolean subsumes(String vocabUri, String generalValue, String specificValue);
Map<String, Set<String>> expandForMatchingByVocabulary(String value);
List<String> ancestors(String vocabUri, String value);
List<String> descendants(String vocabUri, String value);
```

**Implementation:** `CdiVocabularyRegistry` (`@ApplicationScoped`, runtime) — BFS DAG traversal at `@PostConstruct` for O(n) lookup. Cross-vocabulary hierarchies supported.

**Populated via:** `VocabularyRegistrar` SPI (7 registered vocabularies: CasehubCapability, CasehubSlot, Conscientiousness, DISC, Belbin, SVO, ThomasKilmann).

**Effectively not pluggable** — `@ApplicationScoped` without `@DefaultBean`; replacement would cause CDI ambiguity.

---

### 4.4 AgentRegistry — Agent Discovery with Vocabulary Expansion

**Interface:** `io.casehub.eidos.api.AgentRegistry`
**Module:** `casehub-eidos-api`

```java
List<AgentDescriptor> find(AgentQuery query);
```

**`AgentQuery` filters:** `slot`, `capabilityName`, `tenancyId` (required), `taskDomain`

**JPA implementation:** `JpaAgentRegistry` — calls `vocabularyRegistry.expandForMatchingByVocabulary()`, builds dynamic JPQL with per-vocabulary OR clauses + domain exclusion filter. Activated via `@IfBuildProperty("casehub.eidos.reactive.enabled"="false", enableIfMissing=true)`.

**In-memory implementation:** `InMemoryAgentRegistry` (`@Alternative @Priority(1)`, test only).

**Pluggable:** Yes — via build property toggle for reactive vs. blocking.

---

### 4.5 DefaultCapabilityHealth Probe Pipeline — 6-Step Evaluation

**Class:** `io.casehub.eidos.runtime.health.DefaultCapabilityHealth`
**Module:** `casehub-eidos-runtime`

Full 6-step evaluation pipeline:
1. **Operational degradation** — `AgentStateStore.query()` for rate-limited/overloaded → `Degraded`
2. **Capability resolution** — `CapabilityResolver.resolve()` with `VocabularyRegistry` → `Unavailable` if no match
3. **Declared exclusion** — `AgentCapability.excludedDomains.contains(taskDomain)` → `Excluded`
4. **Learned exclusion** — `BehavioralSignalStore.count()` DECLINE signals vs. configurable threshold → `Excluded`
5. **Epistemic weakness** — `AgentCapability.epistemicDomains` confidence vs. weak threshold → `EpistemicallyWeak`
6. **Behavioral compliance** — `BehavioralSignalStore.learned()` VIOLATED signals vs. threshold → `BehavioralViolation`
7. All pass → `Ready`

Thresholds are per-tenancy configurable via `PreferenceProvider` at `EidosPreferenceKeys`.

---

### 4.6 AgentStateStore — Operational Degradation State

**Interface:** `io.casehub.eidos.api.AgentStateStore`
**Module:** `casehub-eidos-api`

```java
void record(String agentId, String tenancyId, DegradationReason reason, Instant expiresAt);
Optional<DegradationRecord> query(String agentId, String tenancyId);
void clear(String agentId, String tenancyId);
```

**`DegradationReason`:** `RATE_LIMITED`, `CONTEXT_EXHAUSTED`, `OVERLOADED`, `DOMAIN_MISMATCH`

**Implementations:** `NoOpAgentStateStore` (`@DefaultBean`), `JpaAgentStateStore` (production), `InMemoryAgentStateStore` (test).

**Data provider for routing** — feeds into `DefaultCapabilityHealth` step 1.

---

### 4.7 BehavioralSignalStore — Learned Exclusion and Compliance Tracking

**Interface:** `io.casehub.eidos.api.BehavioralSignalStore`
**Module:** `casehub-eidos-api`

```java
void record(BehavioralSignal signal);
int count(String agentId, String capabilityTag, String taskDomain, SignalType type);
List<BehavioralSignal> learned(String agentId, String capabilityTag, String taskDomain);
```

**`SignalType`:** `DECLINE`, `SUCCESS`, `COMPLIANT`, `VIOLATED`

**Implementations:** `NoOpBehavioralSignalStore` (`@DefaultBean`), `JpaBehavioralSignalStore` (production), `InMemoryBehavioralSignalStore` (test).

**Data provider for routing** — feeds into `DefaultCapabilityHealth` steps 4 and 6.

---

### 4.8 AgentGraphQuery — Evidence-Based Agent Ranking

**Interface:** `io.casehub.eidos.api.AgentGraphQuery`
**Module:** `casehub-eidos-api`

```java
List<String> topAgentsByOutcome(String capabilityTag, String taskDomain, String tenancyId, int limit);
```

**Ranking algorithm (JpaAgentGraphQuery):** Quality = confidence × multiplier (SUCCEEDED=1.0, PARTIALLY=0.5, FAILED=0.0). Wilson lower bound with z=1.645. Uses `TaskSemanticEnricher.semanticallyEquivalent()` to expand domains for ranking.

**Implementations:** `NoOpAgentGraphQuery` (`@DefaultBean`), `JpaAgentGraphQuery` (graph module, production).

**Data provider for routing** — can feed into routing strategies that want evidence-based candidate ranking.

---

### 4.9 TaskSemanticEnricher — Domain Semantic Equivalence

**Interface:** `io.casehub.eidos.api.TaskSemanticEnricher`
**Module:** `casehub-eidos-api`

```java
boolean semanticallyEquivalent(String domainA, String domainB);
List<DispositionAxis> dispositionAxes(String capabilityTag, String taskDomain);
double significance(String capabilityTag, String taskDomain);
```

**Implementation:** `NoOpTaskSemanticEnricher` (`@DefaultBean`) only — no production impl yet.

**Data provider** — feeds into `AgentGraphQuery` for domain expansion.

---

### 4.10 CapabilityVocabularyValidator — Registration-Time Gate

**Class:** `io.casehub.eidos.api.CapabilityVocabularyValidator` (static utility)
**Module:** `casehub-eidos-api`

```java
static void validate(AgentDescriptor descriptor, VocabularyRegistry registry);
```

Throws if vocabulary URI unregistered or capability name not a valid term.

**Call sites:** `JpaAgentRegistry.register()`, `InMemoryAgentRegistry.register()`

**Registration-time gate** — agents with invalid vocabulary references cannot be discovered by routing queries.

---

## 5. casehub-connectors

### 5.1 ConnectorService — Outbound Connector Routing

**Class:** `io.casehub.connectors.ConnectorService`
**Module:** `casehub-connectors-core` — `@ApplicationScoped`

```java
void send(String connectorId, ConnectorMessage message);
```

Routes by exact string match on `Connector.id()`.

**Implementations:** `SlackConnector` ("slack"), `TeamsConnector` ("teams"), `TwilioSmsConnector` ("twilio-sms"), `WhatsAppConnector` ("whatsapp"), `EmailConnector` ("email").

**Call sites:** All MCP tools, `ConnectorChannelBackend.post()`, `ConnectorAlertBridge.onAlert()`, `SlackNotificationChannel.send()`, `TeamsNotificationChannel.send()`.

**Pluggable:** Yes — any `@ApplicationScoped Connector` CDI bean is auto-discovered. Direct lookup by ID, not strategy-based selection.

---

### 5.2 WebhookRouter — Inbound Webhook Routing

**Class:** `io.casehub.connectors.webhook.WebhookRouter`
**Module:** `casehub-connectors-core` — `@ApplicationScoped`

```java
WebhookResult dispatch(String id, WebhookRequest request);
```

JAX-RS path: `POST|GET /connectors/{id}/webhook`. URL path `{id}` exact match. Returns 404 if no match.

**Result handling:** Pattern-matches sealed `WebhookResult`: `Delivered` fires CDI events, `Challenged` returns challenge body, `Ignored` returns 200, `Unauthorized` suppresses retries.

**Implementations:** `SlackInboundConnector` ("slack-inbound"), `TeamsInboundConnector` ("teams-inbound"), `TwilioSmsInboundConnector` ("twilio-sms-inbound"), `WhatsAppInboundConnector` ("whatsapp-inbound").

**Pluggable:** Yes — any CDI bean extending `WebhookInboundConnector`.

---

### 5.3 InboundConnectorService — Pull-Based Inbound Fan-Out

**Class:** `io.casehub.connectors.InboundConnectorService`
**Module:** `casehub-connectors-core` — `@ApplicationScoped`

```java
void receive(InboundMessage message);
```

Fires `Event<InboundMessage>.fireAsync()`. Single CDI event bus for all inbound messages.

**Implementations:** `EmailInboundConnector` ("email-inbound"), `IrcInboundConnector` ("irc-inbound"), `DiscordInboundConnector` ("discord-inbound").

**Pluggable:** Yes — any `@ApplicationScoped InboundConnector`.

---

### 5.4 AutoChannelPolicy — Auto-Channel Creation on First Contact

See §3.14 above — lives in the qhorus connector bridge module.

---

### 5.5 ChatPlatformService — Chat Platform Dispatch

**Class:** `io.casehub.connectors.chat.ChatPlatformService`
**Module:** `casehub-connectors-core` — `@ApplicationScoped`

```java
ChatPlatform platform(String id);
```

Exact string match on `ChatPlatform.id()`.

**Implementations:** `RefChatPlatform` ("ref"), `DiscordChatPlatform`, `SlackChatPlatform`, `IrcChatPlatform`.

Capability sub-interfaces: `Messaging`, `Threading`, `Discovery`, `Reactions`, `Presence`, etc.

**Pluggable:** Yes — any `@ApplicationScoped ChatPlatform`.

---

### 5.6 InboundWorkItemPolicy — "Should this inbound message create a WorkItem?"

**Interface:** `io.casehub.engine.inbound.InboundWorkItemPolicy`
**Module:** `casehub-engine-inbound`

```java
Optional<WorkItemCreateRequest> decide(MessageReceivedEvent event);
```

**Bridge:** `InboundWorkItemBridge` (`@ApplicationScoped`, implements `MessageObserver`) — receives ALL Qhorus messages (empty `channels()` filter), delegates to policy. `Optional.empty()` → message ignored; present → creates WorkItem with the request's candidateGroups/candidateUsers.

**No default bean** — completely inert without a consumer-provided policy. This IS a routing decision — the policy decides whether a message becomes a work item and who it routes to.

**Pluggable:** Yes — consumer must provide `@ApplicationScoped` bean.

---

### 5.7 ConnectorMeshBridge — MCP Delivery Notification

**Interface:** `io.casehub.connectors.ConnectorMeshBridge`
**Module:** `casehub-connectors-core`

```java
void notifyDelivered(String connectorId, String destination, String content);
```

**Default:** `NoOpConnectorMeshBridge` (`@DefaultBean`).
**Qhorus impl:** `ConnectorQhorusMeshBridge` — posts STATUS message to configured delivery channel.

**Pluggable:** Yes — `@DefaultBean` displacement.

---

### 5.8 ConnectorsCloudEventAdapter — CloudEvent Type Routing

**Class:** `io.casehub.connectors.ConnectorsCloudEventAdapter`
**Module:** `casehub-connectors-core` — `@ApplicationScoped`

Observes `@ObservesAsync InboundMessage`, converts to CloudEvent with `type = "io.casehub.connectors.inbound." + connectorType()`. Downstream consumers route by CloudEvent type.

**Not pluggable** — hardcoded conversion.

---

## 6. Cross-Cutting Analysis

### 6.1 Selection Mechanism Inventory

Seven distinct selection models coexist across the platform:

| # | Model | Used by | Repos |
|---|-------|---------|-------|
| 1 | CDI `@Alternative @Priority(N)` — highest wins | AgentRoutingStrategy, ImplementationRoutingStrategy, TrustRoutingPolicyProvider | engine |
| 2 | CDI `@Alternative` > config string fallback | WorkerSelectionStrategy | work |
| 3 | CDI `@Named` qualifier | InstanceAssignmentStrategy | work |
| 4 | Named `id()` lookup from YAML/config | PlanningStrategy | engine |
| 5 | Single `@DefaultBean` displacement | Most SPIs across all repos | all |
| 6 | CDI `@RiskClassifier` qualifier + chain | ActionRiskClassifier | engine |
| 7 | Config property switch | ContextDiffStrategy, ClaimSlaPolicy | engine, work |

### 6.2 Routing Decision Categories

The ~64 mechanisms across the platform fall into fundamentally different categories:

**Selection — pick one from many:**
- AgentRoutingStrategy (engine) — which worker instance
- WorkerSelectionStrategy (work) — which worker for auto-assignment
- ImplementationRoutingStrategy (engine) — which binding(s)
- WorkerExecutionRoutingStrategy (engine) — which backend
- InstanceAssignmentStrategy (work) — multi-instance distribution

**Filtering — narrow the candidate pool:**
- CapabilityHealth (eidos → engine) — health-based demotion/removal
- AgentCandidateFactory (engine) — capability matching (exact + subsumption)
- ExclusionPolicy (work) — claim/assignment exclusion
- WorkBroker (work) — capability filtering + trigger gating
- ListEvaluator (engine) — candidateGroups/Users resolution

**Path selection — which implementation path:**
- PlanningStrategy (engine) — binding execution order
- OutcomePolicy (engine) — failure routing (reroute vs fault)
- SlaBreachPolicy (work) — escalation path on breach
- ClaimSlaPolicy (work) — pool deadline computation

**Access control — what CAN flow:**
- MessageTypePolicy (qhorus) — channel type enforcement
- AllowedWritersPolicy (qhorus) — sender ACL
- ObligorTrustPolicy (qhorus) — trust-gated delivery
- CapabilityRegistry (work) — capability vocabulary validation

**Data providers — feed INTO routing decisions:**
- WorkloadProvider (work) — active work counts
- WorkerRegistry (work) — group-to-member resolution
- VocabularyRegistry (eidos) — semantic matching substrate
- AgentStateStore (eidos) — operational degradation state
- BehavioralSignalStore (eidos) — learned exclusion data
- AgentGraphQuery (eidos) — evidence-based ranking data
- TrustCandidateClassifier (engine-ledger) — shared classification engine
- TrustRoutingPolicyProvider (engine) — per-capability trust thresholds
- SkillProfileProvider + SkillMatcher (work) — semantic skill scoring

**Delivery — fan-out, not selection:**
- ChannelGateway (qhorus) — message fan-out to backends
- DeliveryService (qhorus) — tracked delivery pump
- NotificationChannel (work) — lifecycle event delivery
- ConnectorService (connectors) — direct lookup by ID
- MessageObserver (qhorus) — post-dispatch observer notification

### 6.3 Parallel Architectures in Engine vs Work

Engine and work have evolved parallel routing architectures with different conventions:

| Concern | Engine | Work |
|---------|--------|------|
| "Which worker handles this?" | `AgentRoutingStrategy` (CDI @Priority) | `WorkerSelectionStrategy` (CDI @Alternative > config) |
| Semantic/AI routing | `SemanticAgentRoutingStrategy` | `SemanticWorkerSelectionStrategy` |
| Least-loaded fallback | `LeastLoadedAgentStrategy` | `LeastLoadedStrategy` |
| Trust-weighted selection | `TrustWeightedAgentStrategy` | — (not present) |
| Candidate filtering | `AgentCandidateFactory` + `CapabilityHealth` | `WorkBroker` + `ExclusionPolicy` |
| Workload data | `WorkerExecutionManager.getActiveWorkCount()` | `WorkloadProvider` |
| Group resolution | — (engine passes groups to work) | `WorkerRegistry.resolveGroup()` |
| Escalation on failure | `OutcomePolicy` (REROUTE/FAULT) | `SlaBreachPolicy` (EscalateTo/Fail/Extend/Exhausted) |
| Round-robin state | — (not present) | `RoutingCursorStore` |

### 6.4 The candidateGroups Evaluation Chain

The evaluation of "who should handle this human task" currently spans three repos with no shared abstraction:

```
Engine (ListEvaluator)
  → StaticList or JQList evaluated against case context
  → Resolved groups passed in HumanTaskScheduleEvent
  
Work-adapter (HumanTaskScheduleHandler)
  → Passes resolved groups to WorkItemCreateRequest
  
Work (WorkItemAssignmentService)
  → WorkerRegistry.resolveGroup() expands groups to candidates
  → ExclusionPolicy filters excluded candidates  
  → WorkloadProvider populates work counts
  → WorkBroker capability-filters and trigger-gates
  → WorkerSelectionStrategy.select() makes final assignment
```

A harness author who wants Drools-based candidateGroups resolution cannot plug in at step 1 (ListEvaluator is sealed) and has no visibility into step 5 (WorkerSelectionStrategy is in a different repo with a different SPI).

### 6.5 Non-Pluggable Routing Points

Several routing decisions are hardcoded and cannot be extended:

| Mechanism | Repo | Why it matters |
|-----------|------|----------------|
| `ListEvaluator` (sealed) | engine | Cannot add Drools/custom candidateGroups evaluation |
| `AgentCandidateFactory` (concrete class) | engine | Cannot change the 2-tier matching algorithm |
| `WorkBroker` (concrete class) | work | Cannot change capability filtering or trigger gating logic |
| `ConnectorKeyStrategy` (hardcoded set) | qhorus-connector | Cannot add new keying strategies for inbound messages |
| `QueueMembershipContext` (concrete class) | work | Cannot change queue membership logic |
| `ActionRiskClassifier.GateRequired.candidateGroups` uses `List<String>` | engine | Cannot use dynamic evaluation for risk gate routing |
