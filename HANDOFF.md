# HANDOFF — engine#994 Governed Yield

**Branch:** `issue-994-governed-yield`
**Date:** 2026-08-26

## Last Session

Two major milestones: fixed the worker-api SNAPSHOT compilation blocker, then completed the HumanTaskTarget → JudgmentTarget migration.

### HumanTaskTarget Deletion (this session)

Clean delete of HumanTaskTarget and old HumanTask SPIs. JudgmentTarget now subsumes all human task functionality:

- **Deleted:** `HumanTaskTarget.java`, `HumanTaskScheduler.java`, `HumanTaskScheduleRequest.java`
- **Sealed permits:** Removed `HumanTaskTarget` from `BindingTarget`
- **Binding API:** Removed `.humanTask()` builder method; `.judgment()` is the sole entry point
- **YAML/Deserializer:** `humanTask:` YAML blocks now produce `JudgmentTarget.forHuman()` (backward-compatible)
- **Handlers:** Removed `publishHumanTaskSchedule()` and old helper methods from `CaseContextChangedEventHandler`; all dispatch routes through `publishJudgment()`
- **CloudEvent module:** `CloudEventHumanTaskScheduler` migrated to implement `JudgmentScheduler` with `JudgmentRequest`/`JudgmentPayload.BindingPayload`
- **Switch cases:** All exhaustive `BindingTarget` switches updated (6 files)
- **Tests:** Migrated 9 test files, deleted 6 obsolete test files (2264 lines removed)
- **Production compilation:** Clean across all modules (except pre-existing generator schema issue)

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

### Deferred (7 tasks — 3 completed this session)

| Task | Status |
|------|--------|
| Delete HumanTaskTarget + old SPIs | **DONE** |
| Update CloudEvent module | **DONE** (migrated to JudgmentScheduler) |
| Update consumer examples | **unblocked** — HumanTaskTarget deleted |
| blocks#171 LLM JudgmentScheduler | **unblocked** — engine-api installs |
| blocks#172 verification strategies | **unblocked** — engine-api installs |
| blocks#173 yield-aware patterns | Mid-pattern yield infrastructure needed (future) |
| engine#1000 DagNode judgment | DagNode.task non-null constraint (future) |
| qhorus#412 E4 trust routing | Blocked by qhorus E4 (#401) + ledger#200 |
| qhorus#413 E5 compliance evidence | Blocked by qhorus E5 (#402) + ledger#201 |
| qhorus#414 E7 formal verification | Blocked by qhorus E7 (#404) |

## Immediate Next Step

1. **CloudEvent tests** — `CloudEventHumanTaskSchedulerTest` and `DistributedHumanTaskRoundTripTest` were deleted (tested old SPI). Need JudgmentScheduler equivalents.
2. **Generator schema** — pre-existing MissingNode cast on judgment YAML types
3. **Consumer examples** — mechanical `humanTask:` → `judgment:` YAML migration
4. **blocks#171/#172** — cross-repo, engine-api now installs
