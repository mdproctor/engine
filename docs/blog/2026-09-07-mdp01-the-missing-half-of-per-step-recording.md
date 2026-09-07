---
title: "The missing half of per-step recording"
date: 2026-09-07
author: mdp
entry_type: note
subtype: diary
series: issue-1050-routing-outcome-per-step
projects:
  - casehubio/engine
tags: [cbr, spi, routing, step-outcome, fsitrading]
---

The engine already fires a per-step signal when workers complete — `RoutingOutcomeRecorder` runs on both success and failure paths in `WorkflowExecutionCompletedHandler`. I'd assumed fsitrading's C6a request (per-step CBR recording) needed a new firing point. It doesn't. The signal fires. The problem is what it carries.

`RoutingOutcomeRecorder` passes an `AgentRoutingContext` — caseId, capabilityName, a JsonNode snapshot. Enough for routing feedback ("was this agent selection good?"), not enough for CBR recording ("what were the market conditions when this step ran?"). No `caseType` for definition lookup, no `Map<String, Object>` for feature extraction. The consumer can't build a CBR case from what it receives.

`CaseOutcomeObserver` has the right shape — `CaseOutcomeEvent` carries `caseType`, `tenancyId`, a Map-shaped context snapshot. But it fires once at case close. fsitrading needs that shape at step granularity: "when agent MomentumStrategy executed reduce-exposure during high volatility at 2am, the outcome was Y."

So we added `StepOutcomeObserver` — symmetric with `CaseOutcomeObserver`, fired from the same handler that already fires `RoutingOutcomeRecorder`. Same `Instance<>` injection, same `isUnsatisfied()` guard, same catch-and-log isolation. Two new types in `api/spi/`, one `@DefaultBean` no-op, two call sites.

The interesting design choice is snapshot timing. The success path captures the working layer *before* output application — the conditions under which the decision was made, not the world after the step changed it. This matters for plan adaptation: when `FsiPlanAdapter` compares current volatility against a stored case's volatility to decide whether to boost a step, both sides need to be input conditions. If the stored case captured post-step state (where the step may have already reduced exposure), the comparison would be against a dampened signal. The failure path uses the current snapshot — no output was applied, so there's no before/after distinction.

The engine stays a notifier. The consumer owns the recording shape. fsitrading will implement `FsiStepOutcomeObserver`, extract features from `contextSnapshot`, and call `CbrCaseMemoryStore.store()` per step. A clinical app would record something completely different. The SPI carries enough context for any domain to build its own CBR cases without leaking engine internals.
