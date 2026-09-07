---
title: "The missing half of compensation visibility"
date: 2026-09-07
tags: [graphql, compensation, saga, subscription, real-time, ops-dashboard]
series: issue-1048-compensation-subscriptions-timeline
entry_type: note
subtype: diary
projects: [casehubio/engine]
author: mdp
---

# The missing half of compensation visibility

We landed the compensation graph and timeline queries in #390 — you could ask "show me the saga state for this case" and get a structured answer. Design-time graph, runtime timeline, ledger chain. Three views, three queries, done.

Except it wasn't done. An ops dashboard needs to watch compensation happen, not poll for its completion. The timeline query told you what already happened. It couldn't tell you what was happening right now.

The gap was specific: step-level compensation events (`COMPENSATION_STEP_STARTED`, `COMPENSATION_STEP_COMPLETED`) existed as EventLog entries but never fired as CDI events. `CaseEventPublisher` — the bridge between engine internals and GraphQL subscriptions — never saw them. Case-level transitions (COMPENSATING, COMPENSATED, COMPENSATION_FAULTED) already flowed through the existing `caseLifecycle` subscription via `CaseLifecycleEvent`. But the per-step granularity that makes an ops dashboard actually useful was invisible to subscribers.

The fix is a new `CompensationStepEvent` CDI event fired by `CaseCompensationServiceImpl.appendStepEvent()` alongside the existing EventLog write. `CaseEventPublisher` gets a third emitter stream. `CaseSubscriptionResolver` gets a new `compensationProgress(caseId)` subscription. Three files, one pattern repeated.

## Attempt grouping

The more interesting change is what happened to the timeline query itself. When a COMPENSATION_FAULTED case gets retried — an operator says "try again" — the engine fires a new `COMPENSATION_STARTED` event and re-executes the failing steps. Before this work, the timeline returned a flat list of all compensation steps across all attempts. No way to tell attempt 1's faulted step from attempt 2's successful re-run of the same step.

We restructured `CompensationTimelineType` from a flat `List<CompensationStepType>` to `List<CompensationAttemptType>`, where each attempt carries its own steps, start/end timestamps, and outcome (COMPLETED, FAULTED, or IN_PROGRESS for the current attempt). The grouping is deterministic: each `COMPENSATION_STARTED` EventLog entry delineates a new attempt, and PlanItems are assigned to attempts by their `createdAt` timestamp falling within the attempt's time window.

The old `triggeredBy`, `reason`, `compensationStartedAt`, and `compensationCompletedAt` fields moved from the top-level timeline into each attempt — because they're per-attempt data, not per-saga data.

## Error enrichment and sub-case linkage

Two smaller additions that make the dashboard actually usable for triage:

`CompensationStepType` gained `errorReason` and `failureCategory` fields. When a compensation step faults, the timeline assembly reads `_diagnostics.<bindingName>.latestDiagnosis` from the case context and surfaces the classification (Transient, Knowledge, Infeasible) alongside the human-readable error. An operator sees "Knowledge: Agent declined — missing access credentials" and knows this needs credential provisioning, not another retry.

`CompensationTimelineType` gained `childCompensationCaseIds` — when parent case compensation propagates to child cases, the timeline now tells you which children are being compensated. The dashboard queries each child's timeline separately, which matches the existing per-case query pattern. Simple, flat linkage — no recursive types in the GraphQL schema.

## What this opens up

The subscription infrastructure is the foundation the blocks-ui ops dashboard component will consume. That's separate work in the pages repo — a React component subscribing to `compensationProgress` and polling `compensationTimeline` for the structured state. The engine-side contract is now complete: real-time step events for progress tracking, grouped attempts for retry history, error classification for triage, and child case links for propagation visibility.
