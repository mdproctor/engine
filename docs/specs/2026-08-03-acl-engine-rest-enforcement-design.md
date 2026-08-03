# ACL Engine-REST Enforcement — Design Specification

**Date:** 2026-08-03
**Status:** Draft
**Scope:** Move ACL enforcement from per-deployment duplication into engine-rest as a built-in SPI
**Tracking:** casehubio/engine#768

---

## 1. Problem

The engine-rest module ships resources with no ACL checks. ACL is a deployment concern handled per-consumer (e.g. scaffold's `AclRequestFilter`). Every deployment duplicates the same pattern: parse path params, resolve resource type, check `AccessControlProvider`, abort on deny.

Additionally, `CaseControlResource` (suspend/resume/cancel) bypasses `CaseService` entirely — no tenant isolation, no existence check. This is a correctness gap independent of ACL.

---

## 2. Design Decision: Service-Layer Enforcement

Enforcement is programmatic in `CaseService`, not via a `@ServerRequestFilter`. This follows the ACL authorization model spec's (`2026-06-08-acl-authorization-model-design.md`) explicit choice (§2.5, §6): "All enforcement is programmatic via `canAccess()`. No annotations, no interceptor."

**Why not a filter:** A filter needs URI path parsing to extract `caseId`, a mapping table from method+path to `AclAction`, and special-case handling for `startCase` (no caseId) and `listCases` (no per-instance check). The service layer already has the caseId, already knows the operation, and is already the chokepoint — all resources except `CaseControlResource` already call `CaseService`.

---

## 3. Changes

### 3.1 `CaseService` — new `requireAccess()` method

```java
@Inject AccessControlProvider accessControlProvider;
@Inject CurrentPrincipal currentPrincipal;

public void requireAccess(UUID caseId, AclAction action) {
    String actorId = currentPrincipal.actorId();
    String resourceId = AclResourceType.CASE + ":" + caseId;
    if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
        throw new AccessDeniedException(actorId, resourceId, action);
    }
}
```

`AccessControlProvider` is injected directly — the platform's `@DefaultBean` `NoOpAccessControlProvider` (`canAccess()` → `true`) is always available. No `Instance<>` wrapper needed. When a real implementation is on the classpath (e.g. `acl-jpa`), it displaces the NoOp automatically.

`AccessDeniedException` is already mapped to HTTP 403 by `AccessDeniedExceptionMapper` in engine-rest.

**Action subsumption:** `AclAction.satisfiedBy()` defines a subsumption hierarchy — `READ` is satisfied by `{READ, WRITE, ADMIN}`, `WRITE` by `{WRITE, ADMIN}`, `ADMIN` by `{ADMIN}` only, `CLAIM` by `{CLAIM}` only. All `AccessControlProvider` implementations use `satisfiedBy()` inside `canAccess()`: an actor with an `ADMIN` grant passes `READ` and `WRITE` checks. Grant provisioning can rely on this — granting `ADMIN` implicitly covers all lower-privilege actions. `requireAccess()` does not need to handle subsumption; it is internal to the provider.

### 3.2 Endpoint ACL wiring

Each resource that accesses a case instance adds a `requireAccess()` call after `requireCase()`:

| Resource | Endpoint | AclAction |
|---|---|---|
| `CaseInstanceResource` | `GET /cases/{caseId}` | `READ` |
| `CaseInstanceResource` | `GET /cases/{caseId}/context` | `READ` |
| `CaseInstanceResource` | `GET /cases/{caseId}/context/{path}` | `READ` |
| `CaseInstanceResource` | `GET /cases/{caseId}/plan-items` | `READ` |
| `CaseInstanceResource` | `GET /cases/{caseId}/goals` | `READ` |
| `SignalResource` | `POST /cases/{caseId}/signals` | `WRITE` |
| `CaseControlResource` | `POST /cases/{caseId}/suspend` | `ADMIN` |
| `CaseControlResource` | `POST /cases/{caseId}/resume` | `ADMIN` |
| `CaseControlResource` | `POST /cases/{caseId}/cancel` | `ADMIN` |
| `EventLogResource` | `GET /cases/{caseId}/events` | `READ` |

Call pattern in each resource method:

```java
String tenancyId = currentPrincipal.tenancyId();
caseService.requireCase(caseId, tenancyId);
caseService.requireAccess(caseId, AclAction.READ); // or WRITE, ADMIN
```

### 3.3 Fix `CaseControlResource` — inject `CaseService`

`CaseControlResource` currently injects only `CaseHubRuntime` and calls `runtime.suspendCase(caseId)` directly — no tenant check, no existence check, no ACL. Fix:

1. Inject `CaseService` and `CurrentPrincipal`
2. Call `caseService.requireCase(caseId, tenancyId)` before each operation
3. Call `caseService.requireAccess(caseId, AclAction.ADMIN)` for all three operations

This fixes the tenant isolation gap as a side effect.

### 3.4 Not guarded (v1 scope exclusions)

**`POST /cases` (startCase):** No case instance exists yet — the ACL authorization model spec (§3.1) maps `startCase` to `ADMIN` on `casedefinition:<id>`. The SPI supports creating such grants (`AccessControlProvider.grant()` can target `casedefinition:<id>` resources), but no provisioning workflow populates definition-level grants today. The `authorization:` YAML block (§10.3) creates grants for the *case instance* at start time — it cannot gate start itself, since by the time grants exist the case already exists. Gating `startCase` requires a mechanism to pre-populate `casedefinition:<id>` grants at definition deployment/registration time. Deferred to Phase 3 (authorization service SPI).

**`GET /cases` (listCases):** Returns all cases in the tenant. Per-instance list filtering via `accessibleResources()` is deferred — tenant isolation is the current boundary. Implementing list filtering requires a non-NoOp `AccessControlProvider`: the `NoOpAccessControlProvider.accessibleResources()` returns an empty list (by design — it has no grant store), which would incorrectly filter out all cases. Until a real provider (e.g. `acl-inmem`, `acl-jpa`) is deployed and grants are provisioned, list filtering cannot be meaningfully tested or enforced.

**`CaseDefinitionResource` endpoints (`/api/v1/case-definitions`):** Three read-only endpoints — list all definitions, get by namespace/name, get by key. These operate on case definitions (resource type `casedefinition`), not case instances (resource type `case`). Already tenant-scoped via `currentPrincipal.tenancyId()`. Definition-level ACL is the same deferred concern as `startCase` — no mechanism populates `casedefinition:<id>` grants today. These endpoints will be guarded when definition-level access control lands.

---

## 4. Dependencies

### 4.1 engine-rest pom.xml

`casehub-platform-api` is already a direct dependency in `rest/pom.xml` (scope: `provided`). No new dependency is needed — `AclAction`, `AclResourceType`, `AccessDeniedException`, and `AccessControlProvider` are all in `io.casehub.platform.api.acl` within this artifact.

### 4.2 engine-rest test classpath

Tests that exercise ACL enforcement need a controllable `AccessControlProvider`. Options:
- Use `NoOpAccessControlProvider` (default) for tests that should pass without ACL
- Define a test `@Alternative` that denies specific actor/resource/action combinations

---

## 5. Test Plan

### 5.1 Unit tests — `CaseService`

- `requireAccess_allowed_noException` — NoOp provider, verify no throw
- `requireAccess_denied_throwsAccessDeniedException` — mock provider returning false, verify exception with correct actorId/resourceId/action
- `requireAccess_buildsCorrectResourceId` — verify `"case:" + caseId` format

### 5.2 Integration tests — REST endpoints

- `getCaseInstance_withDeniedAccess_returns403` — real REST call, deny via test `AccessControlProvider`
- `sendSignal_withDeniedAccess_returns403` — verify WRITE action enforcement
- `suspend_withDeniedAccess_returns403` — verify ADMIN action enforcement
- `suspend_withNoTenancy_returns404` — verify `CaseControlResource` now checks tenancy
- `startCase_noAclCheck_succeeds` — verify startCase is not guarded (v1 exclusion)

### 5.3 Contract preservation

- All existing REST tests continue to pass — the `NoOpAccessControlProvider` makes ACL transparent when no real implementation is deployed.
- **`CaseControlResource` caveat:** The §3.3 change introduces a `requireCase(caseId, tenancyId)` call that depends on `CurrentPrincipal.tenancyId()`. Integration tests (e.g. `CaseControlResourceIT` in the flow module) must provide a valid `CurrentPrincipal` bean. The existing Quarkus test infrastructure already provides one (other resources like `CaseInstanceResource` and `CaseDefinitionResource` already depend on `CurrentPrincipal`), and cases created within tests use the same tenant context — so existing tests are expected to pass without modification.

---

## 6. Migration Impact

**Zero.** Deployments without `acl-jpa` on the classpath get the `NoOpAccessControlProvider` (`canAccess()` → `true`). Behavior is identical to today. Deployments that add `acl-jpa` get automatic ACL enforcement on all engine-rest endpoints — no per-deployment filter needed.

Scaffold's `AclRequestFilter` becomes redundant once this lands and can be removed (tracked separately in scaffold#35).
