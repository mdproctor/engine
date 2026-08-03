# HANDOFF — Slot 69 / engine#833 ACL Engine Integration

**Branch:** `issue-833-acl-engine-integration`
**Date:** 2026-08-03
**Epic:** casehubio/engine#833 — Batches 1-2 complete, Batch 3 remaining

## What was done

### Batch 1 — Identity propagation (platform#220)
- Added `actorId` field to `CaseInstance` domain object, persisted via JPA (`actor_id` column on `CaseInstanceEntity`)
- `CaseHubReactor.buildInstance()` sets `actorId` from `currentPrincipal.actorId()` at case creation
- `CaseInstanceResponse` REST DTO exposes `actorId`
- `PropagationContext` identity wiring (userId + roles in `inheritedAttributes`) was already done in prior commits on this branch
- Contract tests for actorId round-trip, integration test in `ConsumerInMemoryStartCaseTest`, sub-case propagation assertions

### Batch 2 — REST enforcement (engine#768)
- `CaseService.requireCaseAccess(UUID caseId, AclAction action)` — consolidated tenant + existence + ACL guard
- All 10 case-instance REST endpoints wired with appropriate `AclAction` (READ/WRITE/ADMIN)
- `CaseControlResource` fixed — was bypassing `CaseService` entirely (no tenant check, no existence check). Now uses `requireCaseAccess(caseId, ADMIN)`
- `AccessDeniedExceptionMapper` sanitized — returns generic "Insufficient permissions", no internal actorId/resourceId
- `AclEnforcementHealthCheck` — startup WARN when real `AccessControlProvider` is active (lockout prevention)
- `TestAccessControlProvider` for rest module test classpath
- 6 new unit tests (`CaseServiceAclTest`), 2 mapper tests (`AccessDeniedMapperTest`), all 20 REST tests passing
- Design spec at `docs/specs/2026-08-03-acl-engine-rest-enforcement-design.md` (light design review completed)

## What's next — Batch 3 (platform#221)

**Worker rights model and authorization service SPI** — XL scope, high complexity.

The ACL spec Phase 3 (§17) frames the design space:
- In-process workers are sandboxed architecturally — no ACL needed
- External workers are API callers governed by normal ACL
- Privileged external workers (service-account identity) need a new authorization model
- Authorization service SPI for offline approval of worker permission intents

This needs a design session before implementation. The spec has the framing but no concrete design.

## Known issues found during this session

1. **`epic_manager.py` cross-repo parsing bug** — the batch plan regex only matches `#N`, not `owner/repo#N`. Fixed locally but the fix was overwritten by an external modification. Needs to be re-applied. The `.slot` file uses `casehubio/platform#220` format which the parser can't read.

2. **`SubCasePropagationContextTest` CDI failure** — pre-existing `GroupMembershipProvider` unsatisfied dependency. Not caused by this branch's changes. The planning module's test classpath needs a `@DefaultBean` or test alternative.

3. **`design-review` degree persistence bug** — `review.py` doesn't persist `--degree` in the workspace. On resume, degree defaults to standard instead of light. Reported to user. Additionally, the `~2 min` estimate for light reviews is per-dimension, not total.

4. **Work lifecycle epic awareness audit** — 11 findings across `work`, `work-end`, `work-pause`, `work-resume`, `work-slot` skills. Audit report delivered to user for another Claude to apply. Key finding: `work-end` has no pre-condition check for incomplete epics.

## Commits on this branch (this session)

```
eb187980 feat(#220): store actorId on CaseInstance at creation
8c34047d docs: diary entry on identity propagation design
3bee3695 docs(#768): ACL engine-rest enforcement design spec
d6b24137 docs(#768): update spec with design review findings
f616eda5 feat(#768): add CaseService.requireCaseAccess() with ACL enforcement
b9e8c97f fix(#768): sanitize 403 response — remove internal actorId/resourceId
0b3e313e feat(#768): wire requireCaseAccess into CaseInstance, Signal, EventLog resources
0b4427c4 feat(#768): integrate CaseControlResource with CaseService ACL guard
0e8be448 feat(#768): add ACL enforcement startup warning for lockout prevention
87d5aa85 test(#768): ACL mapper tests — verify 403 body sanitization
7c1cb435 docs: diary entry on ACL engine-rest enforcement design
```
