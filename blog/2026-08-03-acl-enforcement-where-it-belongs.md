---
title: "ACL Enforcement: Where It Belongs"
date: 2026-08-03
type: diary
tags: [acl, authorization, rest, case-management, service-layer]
---

There's a pattern in web frameworks that seems reasonable until you think about it for five minutes: every deployment writes its own request filter to check permissions. Parse the path. Extract the resource ID. Look up the action. Call the authorization provider. Abort on deny. The code is always the same. The bugs are always the same. And every new deployment copies it from the last one.

We had this pattern in casehub. Scaffold had an `AclRequestFilter`. Every consumer deployment that wanted ACL would need one too. The engine-rest module — which defines all the case endpoints — shipped with zero authorization. ACL was "a deployment concern."

The problem with "deployment concern" is that it sounds like a principled architectural boundary when it's actually just deferred work. The module that defines the endpoints knows what actions they perform. The module that defines `CaseService` knows what resource types they operate on. Pushing that knowledge out to every consumer means every consumer must independently reconstruct the same mapping table — and get it right.

## The chokepoint was already there

The interesting thing about engine-rest was that the enforcement chokepoint already existed. Every resource that accesses a case instance called `caseService.requireCase(caseId, tenancyId)` — a method that checks tenant isolation and existence. Every resource except `CaseControlResource`, which bypassed `CaseService` entirely and called `runtime.suspendCase(caseId)` directly. No tenant check. No existence check. No ACL. Three admin-level operations with no guard at all.

So the design question wasn't "filter vs service layer" — it was "do we add a second chokepoint (filter) or consolidate on the one we already have (service)?" We consolidated. `CaseService.requireCaseAccess(caseId, action)` does tenant isolation, existence check, and ACL check in a single atomic call. One method. Every endpoint calls it. If you forget it, the case data doesn't load — you can't accidentally serve data without checking permissions because the permission check returns the data.

```java
public CaseInstance requireCaseAccess(UUID caseId, AclAction action) {
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) throw new EntityNotFoundException("Case not found: " + caseId);
    if (!accessControlProvider.canAccess(actorId, resourceId, action))
        throw new AccessDeniedException(actorId, resourceId, action);
    return instance;
}
```

The `AccessControlProvider` is a platform SPI with a `@DefaultBean` no-op that returns `true` for everything. Deployments without `acl-jpa` on the classpath see no change. Deployments that add it get enforcement on every endpoint automatically. Zero per-deployment code.

## What the review caught

We ran a light adversarial design review across three dimensions — coherence, structure, robustness. The robustness reviewer caught that the `AccessDeniedExceptionMapper` was leaking internal context in the 403 response body: actorId, resourceId, and the action. Useful for debugging, terrible for security. The fix was a one-line change — return `"Insufficient permissions"` instead of `exception.getMessage()`.

The same reviewer flagged the lockout risk: deploy a real `AccessControlProvider` without pre-populating grants and every `canAccess()` call returns false. Silent lockout, no error message, just 403 everywhere. We added a startup observer that logs a WARN when a non-NoOp provider is active — diagnosable in the first log line instead of the third support ticket.

## The action mapping

The mapping table tells the whole story of what each endpoint actually does:

| Endpoint | Action |
|---|---|
| GET /cases/{id}, context, plan-items, goals, events | READ |
| POST /cases/{id}/signals | WRITE |
| POST /cases/{id}/suspend, resume, cancel | ADMIN |

`startCase` and `listCases` are intentionally unguarded — `startCase` needs definition-level grants that don't exist yet, and `listCases` filtering requires a non-NoOp provider to have any meaning. Both are tracked for Phase 3.

The CLAIM action doesn't appear because it's a `casehub-work` concern — work item claiming lives in a different module with its own API boundary. ACL actions map to the module that owns the operation, not to a global action registry.
