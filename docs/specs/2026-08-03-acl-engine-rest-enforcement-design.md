# ACL Engine-REST Enforcement — Design Specification

**Date:** 2026-08-03
**Status:** Reviewed (light — coherence, structure, robustness, cross-cutting)
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

### 3.1 `CaseService` — consolidated `requireCaseAccess()` method

A single method that performs tenant isolation, existence check, and ACL check atomically. Eliminates the fragility of separate `requireCase()` + `requireAccess()` calls where one could be omitted.

```java
@Inject AccessControlProvider accessControlProvider;
@Inject CurrentPrincipal currentPrincipal;

public CaseInstance requireCaseAccess(UUID caseId, AclAction action) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
        throw new EntityNotFoundException("Case not found: " + caseId);
    }
    String actorId = currentPrincipal.actorId();
    String resourceId = AclResourceType.CASE + ":" + caseId;
    if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
        throw new AccessDeniedException(actorId, resourceId, action);
    }
    return instance;
}
```

The existing `requireCase(caseId, tenancyId)` remains for call sites that only need existence + tenant checks (e.g. internal engine paths that don't cross the REST boundary).

`AccessControlProvider` is injected directly — the platform's `@DefaultBean` `NoOpAccessControlProvider` (`canAccess()` → `true`) is always available. No `Instance<>` wrapper needed. When a real implementation is on the classpath (e.g. `acl-jpa`), it displaces the NoOp automatically.

**Action subsumption:** `AclAction.satisfiedBy()` defines a subsumption hierarchy — `READ` is satisfied by `{READ, WRITE, ADMIN}`, `WRITE` by `{WRITE, ADMIN}`, `ADMIN` by `{ADMIN}` only, `CLAIM` by `{CLAIM}` only. All `AccessControlProvider` implementations use `satisfiedBy()` inside `canAccess()`: an actor with an `ADMIN` grant passes `READ` and `WRITE` checks. `requireCaseAccess()` does not need to handle subsumption; it is internal to the provider.

### 3.2 `AccessDeniedExceptionMapper` — sanitize 403 response

The current `AccessDeniedExceptionMapper` includes `actorId` and `resourceId` in the response body via `exception.getMessage()`. This leaks internal security context to the caller.

Fix: return a generic 403 with no internal identifiers:

```java
@Override
public Response toResponse(AccessDeniedException exception) {
    return Response.status(403)
        .entity(new ProblemDetail("Access denied", 403, "Insufficient permissions"))
        .build();
}
```

The specific actorId/resourceId/action are logged server-side (see §3.6) but never returned to the client.

### 3.3 Endpoint ACL wiring

Each resource replaces its `requireCase()` call with `requireCaseAccess()`:

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
CaseInstance instance = caseService.requireCaseAccess(caseId, AclAction.READ);
// use instance directly — no separate requireCase() needed
```

### 3.4 Fix `CaseControlResource` — full `CaseService` integration

`CaseControlResource` currently injects only `CaseHubRuntime` and calls `runtime.suspendCase(caseId)` directly — no tenant check, no existence check, no ACL. Fix:

1. Inject `CaseService` and `CurrentPrincipal`
2. Call `caseService.requireCaseAccess(caseId, AclAction.ADMIN)` before each operation
3. Wrap `runtime.suspendCase/resumeCase/cancelCase` in try-catch consistent with the service layer pattern (map `IllegalArgumentException` to `EntityNotFoundException`)

This fixes the tenant isolation gap and the inconsistent error handling as side effects.

### 3.5 Not guarded (v1 scope exclusions)

**`POST /cases` (startCase):** No case instance exists yet — the ACL authorization model spec (§3.1) maps `startCase` to `ADMIN` on `casedefinition:<id>`. The SPI supports creating such grants (`AccessControlProvider.grant()` can target `casedefinition:<id>` resources), but no provisioning workflow populates definition-level grants today. The `authorization:` YAML block (§10.3) creates grants for the *case instance* at start time — it cannot gate start itself. Deferred to Phase 3 (authorization service SPI). **Tracked:** engine#TBD.

**`GET /cases` (listCases):** Returns all cases in the tenant. Per-instance list filtering via `accessibleResources()` is deferred — tenant isolation is the current boundary. The `NoOpAccessControlProvider.accessibleResources()` returns an empty list, which would incorrectly filter out all cases. **Tracked:** engine#TBD.

**`CaseDefinitionResource` endpoints (`/api/v1/case-definitions`):** Three read-only endpoints operating on case definitions (resource type `casedefinition`). Already tenant-scoped. Definition-level ACL is the same deferred concern as `startCase`. **Tracked:** engine#TBD.

**`CLAIM` action:** Not relevant to engine-rest endpoints. `CLAIM` is a `casehub-work` operation for work item claiming — engine-rest does not expose work item claim endpoints.

### 3.6 Access denied logging

Log every `AccessDeniedException` at WARN level before the exception mapper converts it to a 403. This goes in `requireCaseAccess()`:

```java
if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
    LOG.warnf("ACL denied: actor=%s resource=%s action=%s", actorId, resourceId, action);
    throw new AccessDeniedException(actorId, resourceId, action);
}
```

Server-side only — the client receives a generic 403 (§3.2).

---

## 4. Scaffold AclRequestFilter Coexistence

During the transition period where scaffold's `AclRequestFilter` and engine-rest enforcement are both active, ACL checks would run twice — once in the filter, once in `CaseService`. This is harmless (double-allow is still allow, double-deny is still deny) but wasteful.

**Transition plan:**
1. Land this spec (engine-rest enforcement)
2. Verify scaffold's integration tests pass with engine-rest enforcement active
3. Remove scaffold's `AclRequestFilter` (scaffold#35)

Steps 2-3 are tracked in scaffold#35 and are not blocking for this spec.

---

## 5. Dependencies

### 5.1 engine-rest pom.xml

`casehub-platform-api` is already a direct dependency in `rest/pom.xml` (scope: `provided`). No new dependency is needed — `AclAction`, `AclResourceType`, `AccessDeniedException`, and `AccessControlProvider` are all in `io.casehub.platform.api.acl` within this artifact.

### 5.2 engine-rest test classpath

Tests that exercise ACL enforcement need a controllable `AccessControlProvider`. Options:
- Use `NoOpAccessControlProvider` (default) for tests that should pass without ACL
- Define a test `@Alternative` that denies specific actor/resource/action combinations

---

## 6. Deployment Safety

### 6.1 NoOp default (no migration impact)

Deployments without `acl-jpa` on the classpath get the `NoOpAccessControlProvider` (`canAccess()` → `true`). Behavior is identical to today.

### 6.2 Lockout prevention

Deploying a real `AccessControlProvider` (e.g. `acl-jpa`) without pre-populating grants will deny all access — every `canAccess()` call returns `false` because no grants exist. This is a silent lockout.

**Mitigation:** Add a startup health check (`AclEnforcementHealthCheck`) that warns when:
1. A non-NoOp `AccessControlProvider` is active (real enforcement is on)
2. The `acl_entry` table has zero rows (no grants provisioned)

Log at WARN: `"ACL enforcement is active but no grants exist — all case access will be denied. Provision grants via AccessControlProvider.grant() or the authorization: YAML block."` This does not block startup — it's informational. But it makes the lockout diagnosable.

---

## 7. Test Plan

### 7.1 Unit tests — `CaseService`

- `requireCaseAccess_allowed_returnsInstance` — NoOp provider, verify instance returned
- `requireCaseAccess_denied_throwsAccessDeniedException` — mock provider returning false, verify exception
- `requireCaseAccess_notFound_throwsEntityNotFound` — verify 404 before ACL check (tenant isolation first)
- `requireCaseAccess_buildsCorrectResourceId` — verify `"case:" + caseId` format
- `requireCaseAccess_logsOnDenial` — verify WARN log emitted

### 7.2 Integration tests — REST endpoints

- `getCaseInstance_withDeniedAccess_returns403` — real REST call, deny via test `AccessControlProvider`
- `getCaseInstance_returns403_withoutInternalContext` — verify response body has no actorId/resourceId
- `sendSignal_withDeniedAccess_returns403` — verify WRITE action enforcement
- `suspend_withDeniedAccess_returns403` — verify ADMIN action enforcement
- `suspend_withWrongTenant_returns404` — verify `CaseControlResource` now checks tenancy
- `startCase_noAclCheck_succeeds` — verify startCase is not guarded (v1 exclusion)
- `lockout_realProviderNoGrants_allDenied` — verify behavior when real provider has no grants

### 7.3 Contract preservation

- All existing REST tests continue to pass — the `NoOpAccessControlProvider` makes ACL transparent when no real implementation is deployed.
- **`CaseControlResource` caveat:** The §3.4 change introduces a `requireCaseAccess()` call that depends on `CurrentPrincipal.tenancyId()`. Existing integration tests already provide a valid `CurrentPrincipal` bean (other resources depend on it), so existing tests are expected to pass without modification.
