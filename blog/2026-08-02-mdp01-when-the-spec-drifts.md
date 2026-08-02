---
layout: post
title: "When the Spec Drifts and the Code Stays Still"
date: 2026-08-02
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [milestone, goal, alignment, expression-evaluator, dead-code]
---

The original epic (#84) was written when Stage was alive — containment hierarchies, `parentStageId` pointers, CMMN 5.4.4 alignment. A year later, Stage is retired. Compound replaced it. The epic's vision of structural milestone containment was dead on arrival, but nobody had updated the issue.

The first instinct was to carry the vision forward: replace `parentStageId` with `parentCompoundId`. Three approaches, all implementing the same idea — structural containment with back-pointers. We nearly went with it. Then I stopped and asked: does this platform actually need structural containment at all?

casehub already has `ExpressionEvaluator` — pluggable expression evaluation (JQ, MVEL, lambda) against `CaseContext`. Milestone state is written to `milestones.<name>.*` in the working layer. Any compound entry condition, binding trigger, or goal condition can reference that data directly. The integration point already exists. A structural back-pointer would add a second composition mechanism alongside the one the platform already chose.

We removed `parentStageId` instead of replacing it.

The deeper finding was in the milestone state model. Milestone lifecycle was tracked in three places: EventLog (queried on every `CONTEXT_CHANGED` for every milestone), `CasePlanModel` (a ConcurrentHashMap updated by `MilestoneAchievementHandler`), and CaseContext (written by the activated/completed handlers). IntelliJ's `findReferences` on `getMilestoneStatus()` scoped to production files returned zero hits. The CasePlanModel milestone tracking — six interface methods, the handler class, the ConcurrentHashMap — was dead infrastructure. Written to, never read. The CaseContext writes were what everything actually consumed.

The consolidation was straightforward: `MilestoneLifecycleManager` reads from CaseContext instead of scanning EventLog. O(1) map lookup replaces O(milestones x events) per context change. But the design review caught something we'd missed: `MilestoneSLATimeoutJob` is a Quartz job that fires after JVM restarts, when CaseContext isn't populated. It needs EventLog. The canonical state source depends on the execution context — event-driven handlers read CaseContext; scheduled jobs read EventLog. A clean-looking refactoring that unified on CaseContext would have silently broken SLA violation detection after restarts, with no error — the job would read null and default to PENDING.

The Goal/Milestone boundary got a small but meaningful enforcement change. The registry already warned when a Goal wasn't referenced in any completion expression. We upgraded it to a hard rejection. A Goal not in any `GoalExpression` is functionally a Milestone — a non-terminal checkpoint with a kind label. Enforcing this at registration makes the conceptual boundary machine-checked: goals are terminal, milestones are not, and the overlap the original epic identified can't reappear.

The session's implicit architectural decision worth naming: expression-based composition over structural containment is casehub's deliberate position, not an accident of missing features. CMMN needed Stage-to-Milestone containment because it lacks a general-purpose expression language. casehub has one. The structural hierarchy is replaced by a data dependency — milestones write state, conditions read it, and any evaluator can reference any milestone from any scope. That's more flexible and requires no containment bookkeeping.
