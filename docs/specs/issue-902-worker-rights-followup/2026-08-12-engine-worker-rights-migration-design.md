# Engine Worker Rights Migration — Design Spec

**Issue:** casehubio/engine#902
**Date:** 2026-08-12
**Status:** Approved
**Depends on:** casehubio/platform#221 (generalized worker rights SPI)

## 1. Problem Statement

Platform commit `cafb326` generalized the worker rights SPI types in
`platform-api` (WorkerAction enum → record, WorkerCredential UUID caseId →
ResourceId, WorkerPermissionRequest caseDefinitionId →
WorkerAuthorizationContext, WorkerCredentialStore method renames). The engine
currently has 6 compilation errors and cannot build against the updated
platform-api.

Additionally, the `CaseDefinitionYamlMapper` has a pre-existing gap: it
never converts `permissionIntent` from the schema model's `List<String>` to
the API model's `List<WorkerAction>`. Every YAML-specified permission intent
is silently ignored, defaulting to `[READ_CONTEXT]`.

## 2. Scope

### In scope

- `EngineWorkerActions` constants class in engine-api
- `EngineAuthorizationContext` record in engine-api
- `WorkerGrantOrchestrator` migration to `ResourceId` + `WorkerAuthorizationContext`
- `CaseContextChangedEventHandler` action constant references
- `CaseDefinitionYamlMapper` — add missing `permissionIntent` conversion
- Delete `WorkerCredentialFilter` from engine-rest
- `CaseScopeExtractor` implementation in engine-rest
- `casehub-platform-acl-worker` dependency in engine-rest
- All test updates + new tests

### Not in scope

- `ResourceId` retrofit into `AccessControlProvider` (platform issue)
- `AclResourceType` engine-specific constants extraction (platform issue)

## 3. Changes

### 3.1 EngineWorkerActions (engine-api, new)

Package: `io.casehub.api.acl`

Constants class with all 8 engine-specific `WorkerAction` records. Includes
a static lookup map keyed by kebab-case name for YAML parsing:

| Constant | Name | AclAction |
|----------|------|-----------|
| READ_CONTEXT | READ_CONTEXT | READ |
| WRITE_CONTEXT | WRITE_CONTEXT | WRITE |
| SIGNAL_CASE | SIGNAL_CASE | WRITE |
| READ_EVENT_LOG | READ_EVENT_LOG | READ |
| READ_PLAN_ITEMS | READ_PLAN_ITEMS | READ |
| SPAWN_SUB_CASE | SPAWN_SUB_CASE | WRITE |
| CLAIM_WORK_ITEM | CLAIM_WORK_ITEM | CLAIM |
| ADMIN | ADMIN | ADMIN |

`fromKebabCase(String)` converts kebab-case YAML values (e.g. `read-context`)
to the corresponding constant by uppercasing and replacing hyphens with
underscores. Throws `IllegalArgumentException` for unknown names.

### 3.2 EngineAuthorizationContext (engine-api, new)

Package: `io.casehub.api.acl`

```java
public record EngineAuthorizationContext(String caseDefinitionId)
    implements WorkerAuthorizationContext {}
```

### 3.3 WorkerGrantOrchestrator (engine runtime, modify)

Method signature changes:

- `grantAndMint(...)`: parameter `UUID caseId` stays (engine concept), but
  internally constructs `ResourceId(AclResourceType.CASE, caseId.toString())`
  for `WorkerCredential` and ACL calls. Parameter `String caseDefinitionId`
  stays as caller-facing, but wraps it in `EngineAuthorizationContext` for
  the `WorkerPermissionRequest`.
- `revokeForWorker(...)`: `UUID caseId` → constructs `ResourceId` internally.
  Calls `findActiveByActorAndResource(actorId, resourceId)` instead of
  `findActiveByActorAndCase`.
- `revokeForCase(UUID caseId)`: calls `revokeByResource(resourceId)` instead
  of `revokeByCase`.

The method signatures keep `UUID caseId` as the engine-facing parameter —
the engine thinks in case UUIDs. The orchestrator constructs `ResourceId`
internally. This keeps the change local to the orchestrator.

### 3.4 CaseContextChangedEventHandler (engine runtime, modify)

Two occurrences of `WorkerAction.READ_CONTEXT` → `EngineWorkerActions.READ_CONTEXT`.

### 3.5 CaseDefinitionYamlMapper (engine-api, modify)

In `convertBinding()`, add permissionIntent mapping after the existing
field conversions:

```java
if (schemaBinding.getPermissionIntent() != null
    && !schemaBinding.getPermissionIntent().isEmpty()) {
    builder.permissionIntent(
        schemaBinding.getPermissionIntent().stream()
            .map(EngineWorkerActions::fromKebabCase)
            .toList());
}
```

### 3.6 WorkerCredentialFilter (engine-rest, delete)

Delete `WorkerCredentialFilter.java` and `WorkerCredentialFilterTest.java`.
Replaced by platform's `acl-worker` module.

### 3.7 CaseScopeExtractor (engine-rest, new)

Package: `io.casehub.engine.rest.filter`

Implements `WorkerScopeExtractor` SPI. Extracts case UUID from URL paths
matching `cases/{uuid}`. Returns `Optional<ResourceId>` with type
`AclResourceType.CASE`.

### 3.8 Dependencies

engine-rest `pom.xml`: add `casehub-platform-acl-worker` (compile scope).

## 4. Testing Strategy

| Test | Scope |
|------|-------|
| `EngineWorkerActionsTest` | All 8 constants, fromKebabCase round-trip, unknown name rejection |
| `WorkerGrantOrchestratorTest` | Update to EngineWorkerActions + ResourceId construction; verify ResourceId in credential |
| `WorkerRightsIntegrationTest` | Update to EngineWorkerActions + ResourceId; verify revokeByResource |
| `CaseScopeExtractorTest` | URL extraction: matching path, non-matching path, no case ID |
| `BindingPermissionIntentTest` | Update to EngineWorkerActions constants |
| `CaseDefinitionYamlMapperTest` | New test: permissionIntent kebab-case parsing |
| `WorkerCredentialFilterTest` | Deleted (filter deleted) |
