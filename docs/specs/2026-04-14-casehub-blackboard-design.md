# casehub-blackboard Design
**Date:** 2026-04-14
**Status:** Approved — ready for implementation planning
**Repo:** casehub-engine (new module)

---

## Overview

`casehub-blackboard` is an optional Maven module that layers CMMN/Blackboard orchestration onto casehub-engine. Without it, the engine is pure choreography — all eligible Bindings fire when their trigger conditions match. With it, Workers can be organised into Stages with entry/exit criteria, lifecycle tracking, and deliberate sequencing via pluggable PlanningStrategies.

Both execution models can coexist in the same case: Workers assigned to a Stage are orchestrated; Workers not in any Stage remain choreographic and fire freely.

---

## Module Structure

```
api/
└── io.casehub.api.plan/
    └── PlanElement.java    ← marker interface; Worker implements it here

casehub-engine/
└── casehub-blackboard/
    └── src/main/java/io/casehub/blackboard/
        ├── plan/        — PlanItem, PlanItemStatus, CasePlanModel, CasePlanModelRegistry
        ├── stage/       — Stage (implements PlanElement), SubCase (implements PlanElement)
        └── strategy/    — PlanningStrategy, DefaultPlanningStrategy,
                           PlanningStrategyLoopControl,
                           SubCaseCompletionStrategy, DefaultSubCaseCompletionStrategy
```

**Dependency rule:** `casehub-blackboard` depends on `engine/` + `api/`. Zero reverse dependency — `engine/` and `api/` have no knowledge of this module.

**Activation:** `PlanningStrategyLoopControl` is registered as `@Alternative @Priority(10)`. When `casehub-blackboard` is on the classpath, CDI automatically selects it over `ChoreographyLoopControl`. No configuration required — presence on the classpath is the switch.

---

## Core Type Hierarchy

### PlanElement

Marker interface in `api/` for what a `PlanItem` can contain. Lives in `api/` — not `casehub-blackboard/` — because `Worker` (already in `api/`) must implement it, and Java sealed interfaces cannot span Maven module boundaries.

```java
// In api/ — io.casehub.api.plan
public interface PlanElement {}   // not sealed — implementations span modules
```

`Worker` gains `implements PlanElement` in `api/`. `Stage` and `SubCase` implement `PlanElement` in `casehub-blackboard`. The open/closed constraint is enforced by convention and documentation rather than the compiler.

### PlanItemStatus

```
PENDING → ACTIVE → COMPLETED
                 → TERMINATED   (external stop, parent terminated, or mapped from sub-case outcome)
                 → FAULTED      (worker exhausted retries, or sub-case mapped to FAULTED by strategy)
```

FAULTED is distinct from TERMINATED: FAULTED means "stopped by failure", TERMINATED means "stopped by design". A FAULTED child can propagate upward to fault or terminate its parent Stage depending on configured behaviour.

### PlanItem

Generic lifecycle container, hierarchical with full lineage:

```java
public class PlanItem<T extends PlanElement> {
    T element;                        // Stage, Worker, or SubCase
    PlanItemStatus status;            // starts PENDING
    PlanItem<?> parent;               // null at root
    List<PlanItem<?>> children;       // ordered, populated on activation
    Instant activatedAt;
    Instant completedAt;
}
```

The hierarchy is arbitrarily deep. A `PlanItem<Stage>` may contain `PlanItem<Worker>`, `PlanItem<Stage>` (nested), and `PlanItem<SubCase>` as children:

```
PlanItem<Stage>                 ← root stage
├── PlanItem<Worker>
├── PlanItem<Stage>             ← nested stage
│   ├── PlanItem<Worker>
│   └── PlanItem<SubCase>       ← spawns a child CaseInstance
└── PlanItem<SubCase>
```

Lifecycle propagates bidirectionally:
- Parent activating → children become available for activation
- Parent TERMINATED → all children TERMINATED
- All children terminal → parent re-evaluates exit criteria

---

## Stage Definition

`Stage` is the authored definition; `PlanItem<Stage>` is the runtime lifecycle wrapper.

```java
public class Stage implements PlanElement {
    String name;
    ExpressionEvaluator entryCondition;  // required
    ExpressionEvaluator exitCondition;   // optional — if absent, exits when all children terminal
    List<Worker> workers;
    List<Stage> nestedStages;
    List<SubCase> subCases;
}
```

### Entry and Exit Conditions

Everywhere a condition is accepted, three builder overloads are available — this is the **universal condition pattern** across the entire API:

```java
// JQ string shorthand
.entry(".documentsReady == true")

// Lambda shorthand
.entry(ctx -> ctx.getPath("documentsReady").equals(true))

// Any ExpressionEvaluator
.entry(myCustomEvaluator)
```

This applies to `Stage.entry()`, `Stage.exit()`, and retrospectively to `Milestone.condition()` and `Goal.condition()` (which currently lack the lambda convenience overload — add as part of this work).

### Exit Logic

- If `exitCondition` present → stage completes when condition becomes true on CaseContext
- If absent → stage completes when all child PlanItems reach a terminal state (COMPLETED, TERMINATED, or FAULTED)

---

## CasePlanModel

The authored orchestration definition. Separate from `CaseDefinition` — a `CaseDefinition` does not reference its `CasePlanModel`.

```java
public class CasePlanModel {
    String name;
    List<Stage> stages;   // root-level stages, ordered — order matters for DefaultPlanningStrategy
}
```

`casehub-blackboard` maintains a **registry** that maps `CaseDefinition` identity (namespace + name + version) to a `CasePlanModel`. `CaseDefinition` in `api/` is unchanged.

At runtime, the `CasePlanModel` is resolved into a **plan instance** — a tree of `PlanItem`s mirroring the Stage hierarchy, all starting PENDING. The plan instance is held in memory; it can be reconstructed from the `CasePlanModel` definition and the EventLog if needed.

Workers not referenced in any Stage remain choreographic — their Bindings fire freely via the engine's normal loop regardless of `CasePlanModel` presence.

---

## PlanningStrategy and LoopControl Bridge

### PlanningStrategy

Works on `PlanItem`s, giving full hierarchy and lineage context to the strategy:

```java
public interface PlanningStrategy {
    List<PlanItem<?>> select(CaseContext context, List<PlanItem<?>> eligible);
}
```

`eligible` is the list of `PlanItem<Worker>` and `PlanItem<SubCase>` whose Workers belong to currently ACTIVE Stages and whose Binding conditions have been evaluated as true by the engine.

### DefaultPlanningStrategy

Activates Stages in definition order (sequential). Within an ACTIVE Stage, fires all eligible Worker PlanItems simultaneously — orchestration at Stage level, choreography within Stage.

### PlanningStrategyLoopControl

Implements `LoopControl`, registered as `@Alternative @Priority(10)`:

```
CaseContextChangedEvent fires
        ↓
Engine evaluates all Binding trigger conditions → List<Binding> eligible
        ↓
PlanningStrategyLoopControl.select(context, eligible):
  1. Evaluate Stage entry criteria against CaseContext → activate PENDING stages
  2. Evaluate Stage exit criteria for ACTIVE stages → complete finished stages
  3. Filter eligible Bindings to those in ACTIVE stages → List<PlanItem<?>>
  4. Pass free-floating Bindings through unchanged (choreographic)
  5. Delegate to PlanningStrategy.select() → selected PlanItems
  6. Translate selected PlanItems back to Bindings → return to engine
```

The engine sees only `Binding`s in and `Binding`s out. `PlanItem` is entirely internal to the Blackboard layer.

---

## Sub-case Wiring

### SubCase

```java
public class SubCase implements PlanElement {
    String namespace;
    String name;
    String version;
    Function<CaseContext, Map<String, Object>> contextMapper;  // maps parent context to child initial context
    SubCaseCompletionStrategy completionStrategy;
}
```

### Activation Flow

When `PlanningStrategyLoopControl` activates a `PlanItem<SubCase>`:
1. Spawns a new `CaseInstance` using the `SubCase` reference
2. Sets `parentPlanItemId` on the child `CaseInstance` (new nullable field — null for root cases)
3. Child runs independently with its own `CasePlanModel`, Goals, Workers, Stages

### Completion Flow

```
CASE_STATUS_CHANGED (child CaseInstance) received
        ↓
SubCaseCompletionListener:
  1. Check parentPlanItemId — is this a sub-case?
  2. Look up PlanItem<SubCase> in parent case's plan instance
  3. Apply SubCaseCompletionStrategy → PlanItemStatus
  4. Transition PlanItem<SubCase> to resolved status
  5. Re-evaluate parent Stage exit criteria
  6. Propagate upward through PlanItem hierarchy as needed
```

### SubCaseCompletionStrategy

```java
public interface SubCaseCompletionStrategy {
    PlanItemStatus resolve(CaseStatus subCaseStatus, CaseContext subCaseContext);
}
```

Default mapping:

| Child `CaseStatus` | Default `PlanItemStatus` |
|---|---|
| `COMPLETED` | `COMPLETED` |
| `FAULTED` | `FAULTED` |
| `CANCELLED` | `TERMINATED` |

Custom strategies receive the full child `CaseContext` and can inspect what the sub-case produced before deciding the parent outcome.

**Impact on `api/` and `engine/`:** Two small additions — `PlanElement` marker interface in `api/`, `implements PlanElement` on `Worker` in `api/`, and `UUID parentPlanItemId` (nullable) on `CaseInstance` in `engine/`. No behavioural changes outside `casehub-blackboard`.

---

## Universal Condition Pattern

This is a cross-cutting rule established in this design and applied retroactively:

**Everywhere a condition is accepted in the API, three builder overloads must be provided:**

| Overload | Type | Use |
|---|---|---|
| `condition(String jq)` | JQ expression | Most common, succinct |
| `condition(Predicate<CaseContext> lambda)` | Java lambda | Type-safe, IDE-friendly |
| `condition(ExpressionEvaluator evaluator)` | Any evaluator | Custom engines, Drools, etc. |

Applies to: `Stage.entry()`, `Stage.exit()`, `Milestone.condition()`, `Goal.condition()`.

---

## Testing Strategy

### Unit Tests (plain JUnit 5 + AssertJ)

- `PlanItem` lifecycle — valid transitions succeed, invalid transitions throw
- `DefaultPlanningStrategy` — sequential stage activation, all workers fire within active stage
- `DefaultSubCaseCompletionStrategy` — each `CaseStatus` maps to correct `PlanItemStatus`
- `Stage` builder — null conditions throw, all three condition overloads work
- Hierarchy propagation — TERMINATED parent terminates children, FAULTED child propagates

### Integration Tests (`@QuarkusTest`)

- Pure choreography unchanged when no `CasePlanModel` registered
- Single stage activates on entry condition, completes when workers done
- Single stage with explicit exit condition — completes on condition, not worker completion
- Nested stage — child activates only when parent is ACTIVE
- Two sequential stages — stage 2 activates only after stage 1 completes
- Sub-case spawns on activation, parent PlanItem resolves on child completion
- Mixed choreography + orchestration — free-floating workers fire while stage workers controlled
- Lambda condition on entry/exit — same behaviour as JQ condition

### Regression Guard

All 327 existing tests must pass unchanged. `casehub-blackboard` absent = identical engine behaviour.

---

## Open Items

- **Milestone lifecycle upgrade (Phase 2 detail):** When `CasePlanModel` is present, Milestones can be promoted to PENDING → ACHIEVED lifecycle tracking. Not required for initial implementation — Milestones fire as events regardless.
- **Stage FAULTED propagation policy:** Whether a FAULTED child PlanItem faults or terminates its parent Stage is not yet configurable. Default: propagates as FAULTED. A `StageFaultPolicy` SPI can be added later.
- **Plan instance persistence:** The plan instance is in-memory for Phase 2. EventLog reconstruction strategy is deferred.
