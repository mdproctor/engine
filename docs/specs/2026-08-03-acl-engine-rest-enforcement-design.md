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

Enforcement is programmatic in `CaseService`, not via a `@ServerRequestFilter`. This follows the ACL spec's explicit choice (§2.5, §6): "All enforcement is programmatic via `canAccess()`. No annotations, no interceptor."

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

**`POST /cases` (startCase):** No instance exists yet. The ACL spec maps this to `ADMIN` on `casedefinition:<id>`, but no mechanism exists to create definition-level grants. The YAML `authorization:` block creates instance-level grants *after* the case starts — it cannot gate start itself. Definition-level access control is deferred to Phase 3 (authorization service SPI).

**`GET /cases` (listCases):** Returns all cases in the tenant. Per-instance list filtering via `accessibleResources()` is deferred — tenant isolation is the current boundary. The `NoOpAccessControlProvider.accessibleResources()` returns empty list, which would incorrectly filter everything; real filtering requires a non-NoOp implementation. Type-level ACL is explicitly out of scope per ACL spec §9.3.

---

## 4. Dependencies

### 4.1 engine-rest pom.xml

Add `casehub-platform-api` dependency (for `AclAction`, `AclResourceType`, `AccessDeniedException`, `AccessControlProvider`). This may already be a transitive dependency — verify before adding.

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

---

## 6. Migration Impact

**Zero.** Deployments without `acl-jpa` on the classpath get the `NoOpAccessControlProvider` (`canAccess()` → `true`). Behavior is identical to today. Deployments that add `acl-jpa` get automatic ACL enforcement on all engine-rest endpoints — no per-deployment filter needed.

Scaffold's `AclRequestFilter` becomes redundant once this lands and can be removed (tracked separately in scaffold#35).
