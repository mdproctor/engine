# Agent Learning & Memory — Architecture Design

**Issue:** casehubio/engine#800
**Scope:** Cross-cutting architecture for agent-level memory, reflection, and goal lifecycle
**Repos:** engine (primary), neocortex, eidos, blocks
**Date:** 2026-08-04

## Overview

Agents in the CaseHub platform execute tasks within cases but accumulate no agent-level memory across invocations. Case-Based Reasoning (CBR) retains case-level plan traces, and JPAF personality adaptation records cognitive function activation signals, but there is no mechanism for agents to remember individual experiences, synthesize higher-level insights, form new goals, or revise existing ones based on accumulated experience.

This design establishes the cross-cutting architecture for agent learning — the data flow from worker execution through experience recording, reflection synthesis, and goal evolution, feeding back into routing and planning at next dispatch.

## Architectural Decisions

1. **Agent memories reuse `CaseMemoryStore`** with `entityId=agentId` and domain-based isolation (`"experience"`, `"relationship"`, `"reflection"`). No separate agent-level store — the existing neocortex backends (JPA, Qdrant, Graphiti) all support domain-scoped queries.

2. **Recorder pattern** for the engine→neocortex bridge. An `AgentExperienceRecorder` in engine-runtime (parallel to `PersonalitySignalRecorder`) calls neocortex via `ExperienceRecorder` SPI (interface in neocortex-memory-api, implemented by `ExperienceStream` in neocortex-memory). Follows the `DispositionSignalStore` / `CaseMemoryStore` precedent — engine depends only on API interfaces, never on implementation modules.

3. **Configurable hybrid reflection trigger** — importance threshold accumulation (primary) with a completion-count ceiling (secondary). Configured per CaseDefinition via `ReflectionConfig`. The LLM synthesis call runs async on a virtual thread, off the critical path.

4. **Separate `GoalLifecycleStore` SPI** in eidos-api for runtime goal state. Declared goals on `AgentDescriptor` remain immutable (identity). The store tracks discovered goals, priority evolution, and revision history. Mirrors the `DispositionSignalStore` pattern (base state on descriptor + learned state in separate store, merged at evaluation time).

## Agent Memory Data Flow

The system forms a learning loop with five phases:

```
Worker Execution (engine)
    ↓ record
Experience Storage (neocortex)
    ↓ observe
Relationship Detection (neocortex, automatic)
    ↓ threshold trigger
Reflection Synthesis (neocortex + engine trigger)
    ↓ goal candidates
Goal Evolution (eidos + engine evaluation)
    ↓ effective goals/personality feed back into
Routing & Planning (engine, next dispatch)
```

### Phase 1 — Experience Recording (engine → neocortex)

`WorkflowExecutionCompletedHandler` calls `AgentExperienceRecorder.record()` at both the success and failure call sites (same positions as `PersonalitySignalRecorder` and `GoalOutcomeRecorder`). The recorder constructs an `Outcome` event (the `ExperienceEvent` sealed subtype for results) from the worker context:

- `agentId` — from worker name (resolved via `CaseDefinition.agentDescriptorFor()`)
- `tenantId` — mapped from `caseInstance.tenancyId` (naming bridge: engine uses `tenancyId`, neocortex uses `tenantId`)
- `caseId` — from the completing case
- `turnId` — the idempotency key from the completion event
- `description` — constructed: `"Worker {workerName} {result} on capability {capabilityName}"`
- `importance` — defaulted from outcome (SUCCESS/COMPLETED=0.7, DECLINED=0.3, FAILED=0.5, EXPIRED=0.2)
- `result` — outcome status string: `"SUCCESS"`, `"COMPLETED"`, `"DECLINED"`, `"FAILED"`, `"EXPIRED"`
- `capability` — capability name from the binding (nullable)
- `metadata` — includes `ExperienceAttributeKeys.RESULT`, `CAPABILITY`, `EVENT_TYPE="worker-completion"`

Calls `ExperienceRecorder.record()` with explicit error isolation — the recorder wraps the call in a try/catch (logging and swallowing non-security exceptions), ensuring failure never blocks case progression. This follows the `MemoryEmitter` pattern rather than relying on the handler's outer exception handler.

**Naming bridge:** engine uses `tenancyId` (from `CaseInstance.tenancyId`), neocortex memory API uses `tenantId` (on `ExperienceEvent.tenantId()`). `AgentExperienceRecorder` maps `caseInstance.tenancyId` → `ExperienceEvent.tenantId()` at the bridge point.

`ExperienceRecorder` is injected via `Instance<ExperienceRecorder>` — transparent no-op when neocortex-memory is not on the classpath.

### Phase 2 — Relationship Detection (neocortex, automatic)

Already wired: `RelationshipObserver` (`@Observes ExperienceRecorded`) auto-creates `RelationshipEvent` when an experience has a `target-agent` attribute. No engine changes needed — relationships are tracked automatically once Phase 1 provides the attribute.

**`target-agent` derivation:** `AgentExperienceRecorder` populates `ExperienceAttributeKeys.TARGET_AGENT` from the case's binding graph. When a completing worker's binding has downstream bindings that consume its output, the agents assigned to those downstream bindings are the target agents. If no downstream consumers exist or the case has a single worker, `target-agent` is omitted and no relationship event fires. Multi-target interactions (multiple downstream consumers) produce one experience record per target agent, each with a single `target-agent` value — `RelationshipObserver` handles each independently.

### Phase 3 — Reflection Trigger (engine → neocortex)

After recording an experience, `AgentExperienceRecorder` checks the configurable hybrid trigger:

1. Atomically (via `ConcurrentHashMap.compute()` on the per-agent key `(agentId, tenancyId)`):
   a. If a reflection is already in progress for this agent, accumulate counters but do not trigger — return
   b. Accumulate the experience's importance score
   c. Increment the completion counter
   d. On first access (cache miss), bootstrap from `CaseMemoryStore` — queries experience count and summed importance since the agent's last reflection timestamp, seeding with accumulated state (restart-resilient without persistent counter storage)
   e. If either threshold is exceeded, set the reflecting flag and capture `since` timestamp — trigger
2. If triggered, publish a `ReflectionTriggerEvent` on the Vert.x event bus (`EventBusAddresses.REFLECTION_TRIGGER`)

**Trigger lifecycle:** Counters are NOT reset at trigger time. They are reset only when the reflection completes successfully. If reflection fails, the reflecting flag is cleared but counters remain — the next experience may re-trigger immediately. This prevents the lost-experience window where counter reset + failed reflection loses accumulated state.

**v1 constraint:** Counters are in-memory, not durable across instances. The bootstrap-from-store mechanism handles restarts (queries since last reflection timestamp). Multi-instance deployments will not accumulate correctly across instances — v1 targets single-instance deployment. Durable counters for multi-instance support are a future concern.

`ReflectionTriggerHandler` (`@ConsumeEvent(EventBusAddresses.REFLECTION_TRIGGER)`, `@RunOnVirtualThread`) calls `ReflectionOrchestrator.reflect(agentId, tenantId, since, config.maxSourceMemories())`. On completion, notifies `AgentExperienceRecorder` to update trigger state:
- **Success:** Reset counters, clear reflecting flag
- **Failure:** Clear reflecting flag only (counters preserved for re-trigger)

The reflection orchestrator (`ReflectionService`):
1. Queries recent experiences via `ExperienceQuery.forAgent(agentId, tenantId).withLimit(maxSourceMemories).withSince(since)`
2. Calls `ReflectionSynthesizer.synthesize()` — LLM-backed implementation generates structured insights
3. Stores reflections via `CaseMemoryStore.storeAll()` — batch write, returns `StoreAllResult` with per-item success/failure. Fires `ReflectionRecorded` via `fireAsync()` only for successfully stored reflections. Partial store failures are logged; the method throws only if zero reflections were stored (triggering the failure path: clear reflecting flag, preserve counters for re-trigger)

### Phase 4 — Goal Evolution (engine → eidos)

`GoalEvolutionObserver` (engine-runtime, `@ObservesAsync ReflectionRecorded`) examines reflection content for goal candidates. Goal evolution for a given agent is serialized via a per-agent lock (`ConcurrentHashMap<AgentKey, ReentrantLock>`) to prevent concurrent reflections from racing on goal state:

1. Acquires per-agent lock on `(agentId, tenancyId)`
2. Parses reflection text for goal-related insights (via `GoalExtractor` — pattern-based in v1, LLM-backed future)
3. Stores discovered goals in `GoalLifecycleStore.addDiscoveredGoal()`
4. Adjusts priority of existing goals via discrete state transitions: `GoalPriority` is a binary enum (`PRIMARY`, `SECONDARY`). Accumulated `BehavioralSignalStore` signals determine the direction — when SUCCESS count for a goal's capabilities crosses a configurable threshold, `updatePriority(SECONDARY → PRIMARY)`; when DECLINE count crosses the threshold, `updatePriority(PRIMARY → SECONDARY)`. This is a toggle, not continuous adjustment — the "evolution" is the accumulation-and-threshold logic that decides *when* to flip, not a gradual weighting
5. Releases per-agent lock

### Phase 5 — Routing Feedback (engine)

At next dispatch, two evolutions to the existing composable routing pipeline:

1. **`ExperienceSignalProvider` evolution** — the existing provider (id="experience") currently scores candidates via `ExperienceAnalyser.workerSuccessRates()` from `context.experiences()` (case-level). It evolves to also query `CaseMemoryStore` via `ExperienceQuery.forAgents(candidateAgentIds, tenantId)` — a single batched query for all candidate agents in the evaluation — for persistent agent-level experience memories, incorporating cross-case experience quality into the scoring. The batch query avoids per-candidate database round-trips on the routing critical path. Consistent with the read/write split: `ExperienceRecorder` writes, `CaseMemoryStore` reads, `ExperienceQuery` builds the read queries. No new provider — the existing one gains a richer signal source. The two signal components are distinct data, not overlapping records needing deduplication: CBR `RetrievedExperience` (from `context.experiences()`) scores candidates by similarity to the current case context; agent-level experience memories (from `ExperienceQuery`) provide cross-case capability success rates. The provider composes them into a single `CandidateSignal.Score` per candidate — the CBR signal is the primary score (case-context-specific), with the agent-level rate as a base rate when CBR data is sparse.
2. **`GoalSignalProvider` evolution** — the existing provider (id="goal") currently scores candidates via `GoalAbandonmentEvaluator.activeGoals()` against `descriptor.goals()` (declared only). It evolves to use `GoalLifecycleStore.effectiveGoals()` as its total goal set, incorporating discovered goals and lifecycle state. `GoalAbandonmentEvaluator` evolves to query `GoalLifecycleStore` for effective goal state (declared + discovered - abandoned).

## Cross-Repo Integration Points

### Dependency Direction

```
engine-runtime → neocortex-memory-api  (existing: CbrCaseMemoryStore, PlanAdapter)
engine-runtime → neocortex-memory-api  (NEW: ExperienceRecorder, ReflectionOrchestrator)
engine-runtime → eidos-api             (existing: DispositionSignalStore, BehavioralSignalStore)
engine-runtime → eidos-api             (NEW: GoalLifecycleStore, GoalExtractor)
engine-api     → neocortex-memory-api  (existing: CbrConfig, FeatureExtractor)
engine-api     → eidos-api             (existing: AgentDescriptor, CognitiveDemand)
```

### Repo Ownership

| Repo | Owns | New additions |
|------|------|---------------|
| **neocortex** | Memory storage, retrieval, similarity, synthesis | `ExperienceRecorder` + `ReflectionOrchestrator` SPIs in memory-api, LLM-backed `ReflectionSynthesizer` implementation |
| **engine** | Orchestration, when to record/reflect/evolve | `AgentExperienceRecorder`, `ReflectionTriggerHandler`, `GoalEvolutionObserver`, `ReflectionConfig` on CaseDefinition |
| **eidos** | Agent identity, goals, disposition, capability health | `GoalLifecycleStore` SPI + in-memory/JPA implementations, `GoalExtractor` SPI |
| **blocks** | Agentic patterns, decomposition, summarisation | Memory-informed decomposition (future, Sub-epic B) |

### CDI Wiring Pattern

- `ExperienceRecorder` — interface in neocortex-memory-api, `@ApplicationScoped` implementation (`ExperienceStream`) in neocortex-memory. Injected via `Instance<ExperienceRecorder>` in engine (transparent no-op when absent)
- `ReflectionOrchestrator` — interface in neocortex-memory-api, `@ApplicationScoped` implementation (`ReflectionService`) in neocortex-memory. Injected via `Instance<ReflectionOrchestrator>` in engine
- `GoalLifecycleStore` — `@DefaultBean` no-op in eidos-api, real implementation in eidos-runtime/eidos-memory (follows `BehavioralSignalStore` precedent)
- `GoalExtractor` — `@DefaultBean` no-op in eidos-api, pattern-based implementation in eidos-runtime
- `ReflectionSynthesizer` — `@DefaultBean` no-op already exists in neocortex-memory, LLM implementation displaces it when `ChatModel` is available
- All new engine-side components use `Instance<>` injection for optional dependencies

## Configuration Model

### ReflectionConfig on CaseDefinition

```yaml
spec:
  reflection:
    enabled: true
    importanceThreshold: 10.0
    completionCountCeiling: 20
    maxSourceMemories: 100
    synthesizerId: "llm"
```

```java
public record ReflectionConfig(
    double importanceThreshold,      // default 10.0
    int completionCountCeiling,      // default 20
    int maxSourceMemories,           // default 100
    String synthesizerId             // nullable, resolved via StrategyResolver
)
```

CaseDefinitions without a `reflection:` block don't trigger reflection. Experience recording is unconditional when `ExperienceRecorder` is resolvable — experiences are agent-level state, not case-level.

**Trigger scope vs reflection scope (intentional asymmetry):** The per-case-definition config controls *when* reflection triggers (the heuristic). The reflection query is *agent-wide* — `ExperienceQuery.forAgent(agentId, tenantId)` returns experiences from all case types. This is deliberate: the trigger threshold is a deployment knob (different case types may have different tolerance for reflection overhead), while the reflection scope reflects the learning model (an agent should synthesize across all its work, not in isolated per-case-type silos). A CaseDefinition omitting the reflection block means "this case type doesn't contribute to triggering" — not "experiences from this case type should be excluded from reflection."

**Multi-case-type trigger behavior:** When an agent serves multiple case types with different `ReflectionConfig` thresholds, the shared per-agent counter creates emergent properties that operators should understand when tuning: (1) **Lowest-threshold dominance** — the case type with the lowest `importanceThreshold` dominates trigger timing; higher thresholds on other case types are effectively overridden because the lower threshold resets the shared counter first. (2) **Subset counting** — case types without a `reflection:` block do not accumulate the counter despite recording experiences unconditionally; the counter is a proxy for reflection-enabled experience volume, not total experience volume. (3) **Interleaving sensitivity** — the same total workload with different case-type completion ordering produces different reflection timing. These are consequences of the shared-counter + per-case-threshold design, not bugs. The effective trigger rate for a multi-case-type agent is determined by the case type with the lowest threshold.

### Experience Importance Defaults

| Outcome | Default importance | Rationale |
|---------|-------------------|-----------|
| SUCCESS | 0.7 | Positive reinforcement, standard weight |
| COMPLETED | 0.7 | Lifecycle completion — treated identically to SUCCESS |
| FAILED | 0.5 | Failures are learning opportunities |
| DECLINED | 0.3 | Signals preference, not a strong event |
| EXPIRED | 0.2 | Timeout, minimal learning signal |

Configurable via platform preferences (not per-case config).

### Goal Lifecycle — No Per-Case Config

Goal formation and revision are agent-level concerns driven by reflection output, not case-definition configuration. `GoalLifecycleStore` is always-on when available. `GoalAbandonmentEvaluator` retains its own config (`casehub.engine.goal.abandonment-threshold`, default 5).

## New Types

### engine-api

```java
// Case definition configuration
public record ReflectionConfig(
    double importanceThreshold,
    int completionCountCeiling,
    int maxSourceMemories,
    String synthesizerId
)
```

`CaseDefinition` gains `ReflectionConfig reflectionConfig` (nullable). Builder: `.reflectionConfig(ReflectionConfig)`. YAML: `reflection:` block under `spec:`.

### engine-runtime

```java
// Experience recording bridge — explicit error isolation
@ApplicationScoped
public class AgentExperienceRecorder {
    @Inject Instance<ExperienceRecorder> experienceRecorder;
    
    public void record(CaseInstance instance, String workerName,
                       String capabilityName, WorkerOutcome<?> outcome);
    // Resolves CaseDefinition internally via CaseDefinitionRegistry
    // (matches PersonalitySignalRecorder and GoalFailureRecorder pattern).
    // Wraps ExperienceRecorder.record() in try/catch — logs and swallows
    // non-security exceptions (MemoryEmitter pattern, not handler-catch pattern)
}

// Reflection trigger event
public record ReflectionTriggerEvent(
    String agentId, String tenancyId, Instant since,
    ReflectionConfig config
)

// New constant: EventBusAddresses.REFLECTION_TRIGGER = "casehub.reflection.trigger"
// Added to existing EventBusAddresses class in engine-common

// Reflection trigger handler
@ApplicationScoped
public class ReflectionTriggerHandler {
    @ConsumeEvent(EventBusAddresses.REFLECTION_TRIGGER)
    @RunOnVirtualThread
    public void onTrigger(ReflectionTriggerEvent event);
}

// Goal evolution observer
@ApplicationScoped
public class GoalEvolutionObserver {
    public void onReflectionRecorded(@ObservesAsync ReflectionRecorded event);
}

// ExperienceSignalProvider evolves (no new class) — gains Instance<CaseMemoryStore>
// to query agent-level memories via ExperienceQuery.forAgents() (batched) alongside context.experiences()

// GoalSignalProvider evolves (no new class) — gains Instance<GoalLifecycleStore>
// to use effectiveGoals() instead of descriptor.goals()
```

### neocortex-memory-api

```java
// SPI for experience recording — engine depends on this interface, not ExperienceStream.
// Contract: implementations MUST fire ExperienceRecorded CDI event for each successfully
// stored experience. Downstream observers (RelationshipObserver, future Phase 2 consumers)
// depend on this event chain. An implementation that stores without firing is type-correct
// but silently breaks relationship detection.
public interface ExperienceRecorder {
    String record(ExperienceEvent event);
    ExperienceStoreResult recordAll(List<ExperienceEvent> events);
}

// SPI for reflection orchestration — engine depends on this interface, not ReflectionService
public interface ReflectionOrchestrator {
    List<String> reflect(String agentId, String tenantId, Instant since, int maxSourceMemories);
}
```

`ExperienceStream` implements `ExperienceRecorder`. `ReflectionService` implements `ReflectionOrchestrator`. Both are `@ApplicationScoped` in neocortex-memory (runtime). Follows the `CaseMemoryStore` / `DispositionSignalStore` precedent.

### eidos-api

```java
// Goal extraction from reflection content
@FunctionalInterface
public interface GoalExtractor {
    List<AgentGoal> extract(String reflectionContent, AgentDescriptor descriptor);
}

// Goal lifecycle store
public interface GoalLifecycleStore {
    void addDiscoveredGoal(String agentId, String tenancyId, AgentGoal goal,
                           String sourceReflectionId);
    void reviseGoal(String agentId, String tenancyId, String goalName,
                    GoalRevision revision);
    void updatePriority(String agentId, String tenancyId, String goalName,
                        GoalPriority priority);
    List<AgentGoal> discoveredGoals(String agentId, String tenancyId);
    List<EffectiveGoal> effectiveGoals(AgentDescriptor descriptor);
    // Merges declared (descriptor) + discovered - abandoned, preserving provenance
}

public record EffectiveGoal(AgentGoal goal, GoalSource source) {}
public enum GoalSource { DECLARED, DISCOVERED }

public record GoalRevision(
    String description,    // nullable, updated description
    GoalPriority priority, // nullable, updated priority
    String reason          // why the revision occurred
)
```

`NoOpGoalExtractor` (`@DefaultBean`) returns empty list. Pattern-based implementation in eidos-runtime parses structured reflection output for goal-like statements. LLM-backed implementation (future) uses ChatModel.

`NoOpGoalLifecycleStore` (`@DefaultBean @ApplicationScoped`) in eidos-api returns descriptor goals wrapped as `EffectiveGoal(goal, DECLARED)`.

Abandonment of discovered goals uses the same `BehavioralSignalStore` mechanism as declared goals — `GoalAbandonmentEvaluator.isAbandoned()` is goal-source-agnostic.

## Sub-Epic Scope and Implementation Order

### Sub-epic A: Agent Memory Patterns (this branch)

Foundation — experience flows into storage, reflection synthesizes insights.

| # | Title | Repo | Status |
|---|-------|------|--------|
| 1 | Experience stream integration | engine | SPIs exist, needs engine caller |
| 2 | Relationship memory | neocortex | Automatic once #1 wired |
| 3 | Reflective diary | engine + neocortex | Needs trigger + LLM synthesizer |
| 4 | Personality-aware retrieval | neocortex | **Done** |
| 5 | Personality evolution memory | engine | Schema exists, needs producer |
| 6 | Memory decay and forgetting | neocortex | Needs salience composition |

### Sub-epic B: Agent Reflection & Planning (future branch)

Consumes accumulated memories to inform planning and dispatch.

| # | Title | Repo | Depends on |
|---|-------|------|------------|
| 7 | Reflection orchestration (#801) | engine | A.1, A.3 |
| 8 | Hierarchical planning (#802) | engine | A.1 |
| 9 | Plan adaptation (#803) | engine | A.1, B.7 |
| 10 | Memory-informed action selection (#804) | engine | A.1 |

### Sub-epic C: Goal Lifecycle Management (future branch)

Consumes reflection output to evolve goals over time.

| # | Title | Repo | Depends on |
|---|-------|------|------------|
| 11 | Goal formation (#805) | engine + eidos | A.3 |
| 12 | Goal revision (#806) | eidos + engine | C.11 |
| 13 | Goal abandonment | engine | **Done** |
| 14 | Goal priority evolution | eidos | C.11 |
| 15 | Goal discovery from memory (#808) | engine + neocortex | A.3, C.11 |
| 16-18 | Goal routing/termination/mapping | engine | **Done** (#784, #785, #860) |

## Existing Infrastructure Leveraged

| Component | Location | Reused as |
|-----------|----------|-----------|
| `ExperienceEvent` sealed hierarchy | neocortex memory-api | Agent experience ingestion types |
| `ExperienceStream` | neocortex memory runtime | Implements `ExperienceRecorder` SPI — recording entry point |
| `ReflectionService` | neocortex memory runtime | Implements `ReflectionOrchestrator` SPI — reflection orchestration |
| `RelationshipObserver` | neocortex memory runtime | Automatic relationship detection |
| `ReflectionSynthesizer` SPI | neocortex memory-api | LLM synthesis interface (NoOp default exists) |
| `MemoryEmitter` | neocortex memory runtime | Fire-and-forget memory write bridge — error isolation pattern template |
| `PersonalityWeightedRetrieval` | neocortex memory-api | Disposition-weighted memory re-ranking |
| `PersonalityTransitionSchema` | neocortex memory-api | CBR schema for personality evolution cases |
| `PersonalitySignalRecorder` | engine runtime | Pattern template for AgentExperienceRecorder |
| `GoalOutcomeRecorder` | engine runtime | Pattern template + co-located call site |
| `ExperienceSignalProvider` | engine runtime (id="experience") | Evolves to incorporate agent-level memory signals |
| `GoalSignalProvider` | engine runtime (id="goal") | Evolves to use `GoalLifecycleStore.effectiveGoals()` |
| `AgentGoalCompletionMarker` | engine runtime | Co-located at handler call site, marks goals as met |
| `GoalAbandonmentEvaluator` | engine runtime | Extended for GoalLifecycleStore queries |
| `BehavioralSignalStore` | eidos-api | Goal priority signal accumulation |
| `DispositionSignalStore` | eidos-api | Pattern template for GoalLifecycleStore |
| `AgentGraphStore` | eidos-api | Agent task/outcome recording — structurally adjacent (audit, not routing) |
| `CbrConfig` on CaseDefinition | engine-api | Pattern template for ReflectionConfig |
| `ComposableAgentRoutingStrategy` | engine runtime | Host for routing signal providers |
| `RoutingSignalProvider` SPI | engine-api | Existing providers evolve (experience, goal) |

## References

- Landscape analysis: `casehub-examples/wacky-manor/docs/llm-autonomy-landscape-2026.md`
- Smallville (Park et al., UIST 2023) — memory stream + reflection architecture
- Emergence World — triple memory (episodic, reflective diary, relationship state)
- JPAF (arXiv:2601.10025) — personality evolution via reinforcement-compensation-reflection
- Concordia — component-mediated planning
