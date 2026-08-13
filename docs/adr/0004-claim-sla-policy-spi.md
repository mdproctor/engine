# ADR-0004 — Claim SLA Policy as a pluggable CDI strategy

**Date:** 2026-04-23
**Status:** Accepted
**Refs:** casehubio/engine#122, mdproctor/quarkus-work#125

---

## Context

When a WorkItem is unclaimed and returns to the pool — via claim expiry, manual unclaim, or transfer — the system must decide what the new pool-phase deadline is. Four approaches were evaluated:

| | Bounded total time | Fair to each claimant | Complexity |
|---|---|---|---|
| **A — Fresh clocks** | No | Yes | Low |
| **B — Single budget** | Yes | No | Low |
| **C — Phase clocks + cap** | Yes | Yes | High |
| **D — Continuation** | Approximately | Partial | Medium |

## Decision

**Approach D (Continuation) as the default, implemented as a pluggable CDI strategy** following the same pattern as `WorkerSelectionStrategy`.

The SPI (`ClaimSlaPolicy`) is defined in `quarkus-work-api`. Four implementations ship in `quarkus-work-core`: `ContinuationPolicy` (D, default), `FreshClockPolicy` (A), `SingleBudgetPolicy` (B), `PhaseClockPolicy` (C). Applications override with `@ApplicationScoped @Alternative @Priority(1)`.

`ClaimSlaPolicy` is a WorkItem lifecycle concern — it lives in `quarkus-work-api` alongside `EscalationPolicy`, not in `quarkus-work-api`'s Worker selection types. The four implementations live in `quarkus-work-core` because they are pure deadline math with no WorkItem-specific dependencies, reusable by any work domain (including casehub-engine for its own task SLA computation).

## Rationale

**Why D as default:** Remaining pool time carries forward, creating natural urgency as a task ages. Total elapsed time is bounded without requiring a separate cap mechanism. Simpler than C, fairer than B, bounded unlike A. Appropriate for most operational contexts without a hard contractual deadline.

**Why SPI:** The correct policy is deployment-specific. Regulated industries need B (hard deadline from submission). Most enterprise deployments need C (individual fairness + systemic guarantee). Making it a CDI strategy means no code change to adopt a stricter policy — one `@Alternative` bean swap.

**Why `quarkus-work-api`:** `EscalationPolicy` sets the precedent — it is a WorkItem concept that lives in `quarkus-work-api` because WorkItems runtime depends on that module. `ClaimSlaPolicy` follows the same pragmatic placement. A separate `quarkus-workitems-api` module was evaluated and rejected (deleted upstream).

## Consequences

- Applications needing approach B (regulated hard deadlines) inject `SingleBudgetPolicy` as an `@Alternative`.
- Applications needing approach C (phase clocks + total cap) inject `PhaseClockPolicy`.
- Per-task-type policies can be implemented as custom `@Alternative` beans with qualifier injection.
- The data model on `WorkItemEntity` tracks `accumulatedUnclaimedSeconds` and `lastReturnedToPoolAt` to support continuation calculation across claim cycles.
