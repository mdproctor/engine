# SDD Progress — engine#636 batch fixes

Plan: plans/2026-07-04-batch-fixes.md
Branch: issue-636-worker-runtime-batch-fixes
Started: 2026-07-04

## Tasks

Task 1: complete (commits 15a9889d..00f9f614, review clean)
Task 2: complete (commits 00f9f614..84e73c23, review clean)
Task 3: complete (commits 84e73c23..4f192b79, review: 1 Important fixed, 2 Minor)
Task 4: complete (commits 4f192b79..8d132917, review clean)
Task 5: complete (commits 8d132917..58c8537c, review clean — 3 Minor)
Task 6: complete (cross-repo: parent + garden commits, #643 closed, docs-only — no code review needed)
Task 7: complete (CLAUDE.md sync, full build 815/817 pass — 2 pre-existing flaky)

## Final Whole-Branch Review

Verdict: APPROVE_WITH_FIXES
- Important #1: PlanItem incomplete CAS conversion (multi-source-state methods) — filed as #649
- Important #2: CLAUDE.md findByName docs mismatch — fixed (commit 6451edd3)
- 3 Minor observations (CaseTerminatedException package placement, ActionGateScheduleEvent coupling, test goal duplication)

## Minor Findings

- HybridOrchestrationIntegrationTest fails on main (pre-existing) — #637 CAS guard is not the cause
- CaseTerminatedException in common/internal/model — may need to move to api if workers catch it by type
- SpawnChildBean goal defined twice in integration test (no correctness impact)

## Follow-on Issues Filed

- #646: per-case CONTEXT_CHANGED serialization (Option B)
- #649: PlanItem multi-source-state CAS loops
