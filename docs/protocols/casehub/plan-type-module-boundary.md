---
id: PP-20260727-5267d2
title: "Plan-definition types in engine-api; execution types in engine-common"
type: rule
scope: platform
applies_to: "engine-api, engine-common — any new plan or execution type"
severity: important
refs:
  - docs/specs/2026-07-15-unified-execution-model-design.md
violation_hint: "A plan-definition type (used by consumers to declare plans) appearing in engine-common, or an execution-infrastructure type (used only internally by DagDriver/schedulers) appearing in engine-api."
created: 2026-07-27
---

Plan-definition types that consumers reference to declare or inspect plans — `DagPlan`, `DagNode`, `JoinType`, `PlanItemDefinition`, `CompletionSemantics`, `DispatchMode` — belong in `engine-api`. Execution-infrastructure types used only internally to run plans — `DagDriver`, `DagResult`, `NodeState`, `DagEventListener`, `PlanItemExecutionState` — stay in `engine-common`. The boundary test: does a consumer (blocks, domain repo) need to import this type? If yes → engine-api. If only the engine's internal dispatch/scheduling uses it → engine-common. Adding engine-common as a dependency of engine-api creates a circular dependency (common already depends on api) — move the type instead.
