# HANDOFF — Slot 69 / engine#833 ACL Engine Integration

**Branch:** `issue-833-acl-engine-integration`
**Date:** 2026-08-03
**Epic:** casehubio/engine#833 — All 3 batches implemented, ready to land

## What was done

All three batches of the ACL engine integration epic are complete:

- **Batch 1** (platform#220): actorId on CaseInstance, PropagationContext identity wiring
- **Batch 2** (engine#768): CaseService.requireCaseAccess(), 10 REST endpoints wired, AccessDeniedExceptionMapper sanitized
- **Batch 3** (platform#221): Worker rights model — WorkerAction enum, WorkerCredentialStore SPI, WorkerGrantOrchestrator, WorkerCredentialFilter (REST structural isolation), token threading through dispatch/completion/revocation, code reviewed and all findings fixed

## Phase A complete — ready to land

Branch pushed to origin. `.phase-a-complete` marker written. Run `work-slot merge` from the main engine repo to land.

## What's left before merge

- YAML mapper support for `permissionIntent` and `serviceAccountId` (deferred — `CaseDefinitionYamlMapper` changes, straightforward follow-on)
- Close engine#833, engine#768, platform#220, platform#221 after merge

## Commits on this branch

30 commits across 3 batches. Key implementation commits:
- `c6cd30ee` feat(#221): WorkerAction, WorkerCredential, WorkerCredentialStore foundation types
- `f43f1c9e` feat(#221): permissionIntent on Binding, workerServiceAccountIds on CaseDefinition
- `c1042efd` feat(#221): WorkerIdentityResolver and WorkerGrantOrchestrator
- `a1094db8` feat(#221): token threading through dispatch/completion/revocation
- `d8c73847` feat(#221): WorkerCredentialFilter for REST structural isolation
- `59ef3cbd` feat(#221): wire grant orchestration into dispatch and provisioning handlers
- `58b8c1c1` fix(#221): code review fixes — expiry filter, token logging, UUID parsing, grant TTL
- `ca407e20` docs(#221): worker rights YAML example — loan approval scenario
