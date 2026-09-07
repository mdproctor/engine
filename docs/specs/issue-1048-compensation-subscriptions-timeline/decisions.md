# Decisions — #1048 Compensation Subscriptions + Enriched Timeline

## D1: Subscription architecture

**Choice:** Dedicated `compensationProgress(caseId)` subscription for step-level events only
**Alternatives:**
- Unified compensation subscription — emits both case-level and step-level in one stream; overlaps with existing `caseLifecycle`
- Extend caseLifecycle — adds step-level events to existing subscription; mixes orchestration granularity levels
**Rationale:** Case-level transitions (COMPENSATING, COMPENSATED, COMPENSATION_FAULTED) already flow through `caseLifecycle` via `CaseLifecycleEvent`. Step-level events (STEP_STARTED, STEP_COMPLETED) are a different granularity — an ops dashboard subscribes to step progress for real-time tracking, while lifecycle transitions serve broader case monitoring. Clean separation avoids subscription overlap and keeps each stream focused.
**Trade-offs:** Dashboard needs two subscriptions to get the full picture (caseLifecycle + compensationProgress). This is acceptable because the dashboard already subscribes to caseLifecycle for other state transitions.
**Sources:** CaseSubscriptionResolver.java:40-56, CaseEventPublisher.java:34-44, CaseCompensationServiceImpl.java:378-393
**Exploration:** quick
**Status:** captured

## D2: Retry tracking model

**Choice:** Grouped attempts — `CompensationTimelineType` restructured with `List<CompensationAttemptType>`
**Alternatives:**
- Flat with attempt index — each step gains attemptNumber, dashboard groups client-side; simpler model but pushes grouping logic to every consumer
- Separate query — new `compensationAttempts(caseId)` for attempt metadata only; decoupled but requires two queries for the same view
**Rationale:** The ops dashboard needs to render attempt boundaries clearly — "attempt 1 faulted at step C, attempt 2 succeeded." Grouping server-side means every consumer gets structured data without reimplementing the same EventLog-counting logic. Each `COMPENSATION_STARTED` EventLog entry delineates a new attempt, making the grouping deterministic.
**Trade-offs:** Breaking change to `CompensationTimelineType` — the flat `compensationSteps` field is replaced by `attempts`. Pre-release, so no backward compat concern.
**Sources:** CaseCompensationServiceImpl.java:92-117 (compensate method creates COMPENSATION_STARTED), CaseQueryResolver.java:157-268 (current flat assembly)
**Exploration:** quick
**Status:** captured

## D3: Sub-case propagation model

**Choice:** Child case IDs at timeline level — `CompensationTimelineType` gains `childCompensationCaseIds: List<UUID>`
**Alternatives:**
- Inline child timelines — recursive `List<CompensationTimelineType>`, one query returns the full tree; richer but N+1 query complexity and unbounded nesting
- Step-level child link — `CompensationStepType` gains `childCaseId` per step; per-step granularity but the dashboard must correlate step→child→timeline
**Rationale:** Simple, flat linkage. The dashboard queries each child's timeline separately, which aligns with the existing per-case query pattern. Avoids recursive types in GraphQL (SmallRye GraphQL handles them but they add schema complexity). The number of child compensations is typically small (bounded by sub-case count).
**Trade-offs:** Dashboard needs N+1 queries (1 parent + N children). Acceptable because child compensation is a rare path and the number of sub-cases per case is small.
**Sources:** CaseCompensationServiceImpl.java:119-202 (fireNextCompensationStep handles sub-case dispatch), saga-compensation-design.md §5.1 (sub-case propagation)
**Exploration:** quick
**Status:** captured

## D4: Error enrichment fields

**Choice:** Reason + failure category on `CompensationStepType`
**Alternatives:**
- Reason only — simpler but dashboard can't distinguish transient vs fundamental failures for triage
- Full diagnostics (reason + category + missingContext) — maximum detail but leaks internal engine concepts (`missingContext` is an implementation artifact of the Knowledge failure classifier)
**Rationale:** `errorReason` gives human-readable context for operators. `failureCategory` (Transient/Knowledge/Infeasible) gives structured classification for dashboard rendering (e.g., color-coding severity, suggesting retry vs escalation). This is the right surface area — enough for operational decisions without exposing engine internals.
**Trade-offs:** `failureCategory` vocabulary couples the GraphQL surface to the engine's `FailureCategory` sealed type naming. Mitigated by using String (not enum) in the GraphQL type — the category is informational, not a contract.
**Sources:** CompensationStepType.java (current fields), FailureCategory sealed interface in api/model/, _diagnostics context schema (CLAUDE.md Worker Outcome Handling section)
**Exploration:** quick
**Status:** captured
