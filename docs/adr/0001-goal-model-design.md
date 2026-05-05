# 0001 — Goal Model Design

Date: 2026-04-09
Status: Accepted

## Context and Problem Statement

CaseHub cases complete on quiescence — when no eligible TaskDefinitions remain.
This conflates "nothing more can run" with "the case achieved its purpose".
The caller has no way to declare what success looks like at case creation time,
`awaitCompletion()` has no way to distinguish success from stalled, and the
system cannot detect that a goal is already satisfied (wasted work) or has
become impossible (cases that never terminate).

## Decision Drivers

* Callers need a formal contract: "I consider this case done when X"
* The system must distinguish COMPLETED (goal achieved) from WAITING (quiescent but not done)
* Goal evaluation must be path-independent — satisfied by any route, not only by specific TaskDefinitions running
* Backward compatibility — cases without a Goal must continue to work as before
* Terminology must not collide with LangChain4j-agenticai's "Goal" (an outputKey string) or its "Plan" (an action sequence)

## Considered Options

* **Option A — Goal as predicate + named Milestones** — a `CaseGoal` declared at `createAndSolve()` time, satisfied when all named `Milestone`s (each a `Predicate<CaseFile>`) are achieved; evaluated by a dedicated `GoalEvaluator` separate from task execution
* **Option B — Goal as final Milestone only** — no explicit Goal object; the terminal Milestone's predicate drives `completionExpression`; simpler but loses the explicit caller contract and the PENDING/ACTIVE/ACHIEVED/ABANDONED lifecycle
* **Option C — Implicit goal via case type registry** — goal predicates registered at case type level (like `@CaseType` on TaskDefinitions); no per-instance goal declaration; loses runtime flexibility and adaptive case management

## Decision Outcome

Chosen option: **Option A — Goal as predicate + named Milestones**, because:
- It provides an explicit caller contract at `createAndSolve()` time (the system knows what done looks like before any work starts)
- Goal evaluation is path-independent: `Predicate<CaseFile>` tests observable state, not execution history
- The dedicated `GoalEvaluator` enables opportunistic achievement (BDI insight: if the goal is already satisfied before planning, skip planning)
- Milestones are the right CMMN primitive for named intermediate achievement states; `Goal` is the aggregate completion condition across them
- Cases without a Goal fall back to existing quiescence behaviour — zero regression

### Positive Consequences

* `createAndSolve()` becomes a formal contract — the system can report ACHIEVED vs WAITING vs ABANDONED
* `awaitGoal()` returns `GoalResult` with per-Milestone breakdown — meaningful progress reporting
* `GoalEvaluator` runs before `ListenerEvaluator` — goals already satisfied trigger no unnecessary TaskDefinition activation
* Abandonment predicates prevent impossible goals from keeping cases alive indefinitely
* Terminology is distinct from LangChain4j: CaseHub uses `Milestone` (CMMN term) and `CaseGoal`; LangChain4j uses `outputKey` (string) and `Plan` (action sequence)

### Negative Consequences / Tradeoffs

* Additional complexity in `CaseEngine` control loop — `GoalEvaluator` runs before and after each iteration
* `createAndSolve()` API changes — existing callers that omit Goal must be handled (optional parameter, backward-compatible)
* AND/OR/custom Goal completion semantics deferred — only AND (all Milestones) for now; OR and custom predicates when real use cases demand them

## Pros and Cons of the Options

### Option A — Goal as predicate + named Milestones

* ✅ Explicit caller contract at creation time
* ✅ Path-independent satisfaction (any route that produces the state succeeds)
* ✅ Enables opportunistic achievement (BDI) and goal abandonment (KAOS)
* ✅ CMMN-aligned — Goal ≈ completionExpression, Milestone ≈ CMMN Milestone with sentry
* ✅ Backward compatible — Goal is optional
* ❌ More complexity in CaseEngine loop
* ❌ AND/OR semantics deferred — some use cases will need it eventually

### Option B — Goal as final Milestone only

* ✅ Simpler — no new Goal object
* ✅ Pure CMMN Milestone semantics
* ❌ No explicit caller contract — still no "what do you want?" at createAndSolve() time
* ❌ No PENDING/ACTIVE/ACHIEVED/ABANDONED lifecycle on the overall goal
* ❌ Multiple terminal Milestones are awkward — which one is "the" goal?

### Option C — Implicit goal via case type registry

* ✅ Zero API change at createAndSolve() time
* ❌ No per-instance runtime flexibility — adaptive case management impossible
* ❌ All cases of a type share the same goal — can't vary by caller intent
* ❌ Loses the "case as something you call with a stated objective" ergonomic

## Open Questions (deferred)

* AND/OR/custom completion: `Goal.all()` (default), `Goal.any()`, `Goal.completedWhen(predicate)` — revisit when real use cases surface
* Goal SLA/deadline: should `CaseGoal` carry a `Duration` that abandons if not achieved in time?
* Milestone abandonment: how does the system detect that a Milestone is no longer reachable?
* Multiple simultaneous active Goals: priority and conflict resolution
* Sub-goal creation during execution: a TaskDefinition's output activating a new Goal

## Links

* Issue #7 — Add Goal model: CaseGoal, Milestone enhancement, GoalEvaluator
* `docs/research/goal-model-research.md` — full research synthesis (GOAP, BDI, HTN, DCR, CMMN, LangChain4j)
* `docs/research/choreography-expected-flows.md` — related: Trajectory concept sits above Goals/Milestones
