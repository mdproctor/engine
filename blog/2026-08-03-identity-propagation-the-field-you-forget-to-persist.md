---
title: "Identity Propagation: The Field You Forget to Persist"
date: 2026-08-03
type: diary
tags: [acl, identity, case-management, propagation-context]
---

Every case management engine has a moment where identity falls through the cracks. Ours was line 254 of `CaseHubReactor.buildInstance()` — `caseInstanceRepository.save(instance, currentPrincipal.tenancyId())`. The tenancy ID is persisted. The actor ID is used for ACL grants three lines earlier. Then it's gone. The case instance has no record of who created it.

This isn't a bug in the traditional sense. The engine worked. Cases started, workers dispatched, goals evaluated. But when we started wiring ACL enforcement for internal execution paths — not just the REST boundary — the question "who started this case?" had no durable answer.

## Two kinds of identity

The fix looks simple: add `actorId` to `CaseInstance`, set it from `currentPrincipal.actorId()` at creation, persist it via JPA. We did that. But the interesting part is what was already there and why it wasn't enough.

`PropagationContext.inheritedAttributes` was designed for exactly this — identity that flows from parent to child across a case hierarchy. `createChild()` inherits all attributes automatically. We'd already wired `userId` and `roles` into the root `PropagationContext` at case creation. Sub-cases inherit them. Workers receive them via `WorkerContext`. The infrastructure worked.

So why add `actorId` to `CaseInstance`?

Because `PropagationContext` isn't persisted by JPA. `CaseInstanceEntity` stores uuid, state, parentCaseId, labels — structural fields. The propagation context lives only in the in-memory `CaseInstanceCache`. After a JVM restart, the case is reconstructed from the database with `propagationContext = null`. The identity carried through `PropagationContext` — userId, roles, trace lineage — is gone.

These two mechanisms serve different questions. `actorId` on `CaseInstance` answers "who called `startCase()` for this specific instance?" It's durable, queryable, and survives restarts. `PropagationContext.getAttribute("userId")` answers "who is the human behind this entire case hierarchy?" It flows through sub-cases where the engine's `SystemCurrentPrincipal` is the technical caller but the original human's identity needs to reach the leaf workers.

## The sub-case distinction

For root cases, both mechanisms carry the same value — the human user's ID. For sub-cases, they diverge. `SubCaseExecutionHandler` calls `startCase()` under the engine's system identity, so `actorId` on the child `CaseInstance` is `"system"`. But the child's `PropagationContext` inherits the human's userId from the parent via `createChild()`. Both are correct. They answer different questions.

The ACL layer needs `actorId` for the case creator's automatic ADMIN grant. The worker execution layer needs `PropagationContext` for identity-aware dispatch and on-behalf-of semantics. Conflating them — using one mechanism for both — would force either the ACL to look up the root case's identity for every sub-case check, or the worker layer to carry a denormalised field that drifts from the propagation chain.

## What this unlocks

With `actorId` durable on `CaseInstance` and identity flowing through `PropagationContext`, ACL enforcement can move from the REST boundary into the engine runtime. The next step — [engine#768](https://github.com/casehubio/engine/issues/768) — moves the `AccessControlProvider` check into engine-rest as an SPI, so deployments get ACL transparently instead of duplicating the pattern per consumer. That check needs to know who created the case. Now it can ask.
