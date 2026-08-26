# HANDOFF — engine#994 Governed Yield

**Branch:** `issue-994-governed-yield`
**Date:** 2026-08-26

## Last Session

Fixed the pre-existing worker-api SNAPSHOT mismatch that was blocking engine compilation and downstream module installation.

### Worker-API Alignment (this session)

Worker-api renamed three method families; the engine codebase still referenced the old names:
- `Capability.inputProjection()` / `outputProjection()` → `inputSchema()` / `outputSchema()` (32 files, mechanical rename — fields hold JQ expressions, not JSON Schema)
- `Worker.capabilities()` → `capabilityNames()` (across api, common, runtime, planning, resilience, annotations, examples)
- Added `JudgmentTarget` to exhaustive `BindingTarget` switches in `SchedulerService`, `QuartzWorkerExecutionManager`, `PlanningCasePlanModelSnapshotProvider`
- Fixed star-import checkstyle violations in 4 judgment test files

**Result:** All modules compile and install cleanly (`mvn install -DskipTests -pl '!generator'`). Generator has a pre-existing schema issue (MissingNode→ObjectNode cast on new judgment types — unrelated to method renames).

### Prior Session — Design + Batches 1-3

Designed and implemented the engine foundation for governed yield — a caller-agnostic judgment infrastructure. Full architecture spec with 14 design decisions.

- Spec: `docs/specs/issue-994-governed-yield/2026-08-26-governed-yield-design.md`
- Decisions: `docs/specs/issue-994-governed-yield/decisions.md`
- Plan: `docs/plans/2026-08-26-governed-yield.md`

**Batch 1 — Engine Foundation Types** (complete), **Batch 2 — Handler Wiring + Ledger Events** (complete), **Batch 3 — Qhorus JUDGMENT type** (complete).

### Deferred (10 tasks)

| Task | Blocker |
|------|---------|
| qhorus#412 E4 trust routing | Blocked by qhorus E4 (#401) + ledger#200 |
| blocks#171 LLM JudgmentScheduler | engine-api now installs — **unblocked** |
| blocks#172 verification strategies | engine-api now installs — **unblocked** |
| blocks#173 yield-aware patterns | engine-api installs but mid-pattern yield infrastructure needed (future) |
| engine#1000 DagNode judgment | DagNode.task non-null constraint (future) |
| qhorus#413 E5 compliance evidence | Blocked by qhorus E5 (#402) + ledger#201 |
| qhorus#414 E7 formal verification | Blocked by qhorus E7 (#404) |
| Delete HumanTaskTarget + old SPIs | **unblocked** — engine-api compiles |
| Update CloudEvent module | **unblocked** — engine-api compiles |
| Update consumer examples | Blocked until HumanTaskTarget deletion |

## Immediate Next Step

The engine-api compilation blocker is resolved. Three deferred tasks are now unblocked:
1. **blocks#171 / #172** — LLM JudgmentScheduler and verification strategies (engine-api SNAPSHOT now installs)
2. **Delete HumanTaskTarget + old SPIs** — engine compiles, migration can proceed
3. **Update CloudEvent module** — depends on HumanTaskTarget deletion

Also: fix the generator schema issue (MissingNode cast on judgment YAML types).
