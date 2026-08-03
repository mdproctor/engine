# ACL Engine-REST Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #768 — feat: move ACL checks into engine-rest SPI
**Issue group:** #768

**Goal:** Move ACL enforcement from per-deployment duplication into
engine-rest as built-in service-layer checks via `CaseService.requireCaseAccess()`.

**Architecture:** Single consolidated guard method in `CaseService` that
performs tenant isolation + existence check + ACL check atomically. All
REST resources call this one method. `AccessDeniedExceptionMapper`
sanitized to not leak internal context. Startup health check warns on
lockout risk.

**Tech Stack:** Quarkus CDI, JAX-RS, `casehub-platform-api` ACL types

## Global Constraints

- `AccessControlProvider` is in `casehub-platform-api` (already a dependency of engine-rest)
- `NoOpAccessControlProvider` (`@DefaultBean`) returns `true` for all `canAccess()` — zero migration impact
- `AclAction` enum: `READ`, `WRITE`, `ADMIN`, `CLAIM`
- `AclResourceType` constants: `CASE = "case"`, `CASE_DEFINITION = "casedefinition"`
- Resource IDs are `type:id` format — e.g. `case:abc-123`
- `AccessDeniedException` already has an `ExceptionMapper` in engine-rest
- IntelliJ MCP required for all code navigation and editing

---

### Task 1: `CaseService.requireCaseAccess()` + unit tests

**Files:**
- Modify: `rest/src/main/java/io/casehub/engine/rest/service/CaseService.java`
- Create: `rest/src/test/java/io/casehub/engine/rest/service/CaseServiceAclTest.java`

**Interfaces:**
- Consumes: `AccessControlProvider.canAccess(String, String, AclAction)` from platform-api
- Produces: `CaseService.requireCaseAccess(UUID caseId, AclAction action) → CaseInstance`

- [ ] **Step 1: Write failing tests**

```java
package io.casehub.engine.rest.service;

import static org.assertj.core.api.Assertions.*;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.rest.exception.EntityNotFoundException;
import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.api.acl.AccessDeniedException;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseServiceAclTest {

    private CaseService caseService;
    private CaseInstanceRepository repo;
    private AccessControlProvider aclProvider;
    private CurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        repo = mock(CaseInstanceRepository.class);
        aclProvider = mock(AccessControlProvider.class);
        principal = mock(CurrentPrincipal.class);
        caseService = new CaseService();
        // inject mocks via field access (package-private fields)
        caseService.instanceRepository = repo;
        caseService.accessControlProvider = aclProvider;
        caseService.currentPrincipal = principal;
    }

    @Test
    void requireCaseAccess_allowed_returnsInstance() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        when(principal.tenancyId()).thenReturn("t1");
        when(principal.actorId()).thenReturn("alice");
        when(repo.findByUuid(caseId, "t1")).thenReturn(instance);
        when(aclProvider.canAccess("alice", "case:" + caseId, AclAction.READ)).thenReturn(true);

        CaseInstance result = caseService.requireCaseAccess(caseId, AclAction.READ);
        assertThat(result).isSameAs(instance);
    }

    @Test
    void requireCaseAccess_denied_throwsAccessDenied() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        when(principal.tenancyId()).thenReturn("t1");
        when(principal.actorId()).thenReturn("alice");
        when(repo.findByUuid(caseId, "t1")).thenReturn(instance);
        when(aclProvider.canAccess("alice", "case:" + caseId, AclAction.WRITE)).thenReturn(false);

        assertThatThrownBy(() -> caseService.requireCaseAccess(caseId, AclAction.WRITE))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireCaseAccess_notFound_throwsEntityNotFound() {
        UUID caseId = UUID.randomUUID();
        when(principal.tenancyId()).thenReturn("t1");
        when(repo.findByUuid(caseId, "t1")).thenReturn(null);

        assertThatThrownBy(() -> caseService.requireCaseAccess(caseId, AclAction.READ))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void requireCaseAccess_buildsCorrectResourceId() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        when(principal.tenancyId()).thenReturn("t1");
        when(principal.actorId()).thenReturn("bob");
        when(repo.findByUuid(caseId, "t1")).thenReturn(instance);
        when(aclProvider.canAccess(eq("bob"), eq("case:" + caseId), eq(AclAction.ADMIN))).thenReturn(true);

        caseService.requireCaseAccess(caseId, AclAction.ADMIN);
        verify(aclProvider).canAccess("bob", "case:" + caseId, AclAction.ADMIN);
    }
}
```

Add static imports for Mockito at the top: `import static org.mockito.Mockito.*;`
and `import static org.mockito.ArgumentMatchers.*;`

- [ ] **Step 2: Run tests — verify they fail** (method doesn't exist yet)

Run: `mvn test -pl rest -Dtest="CaseServiceAclTest" -f pom.xml`
Expected: compilation failure — `requireCaseAccess` not defined

- [ ] **Step 3: Implement `requireCaseAccess()` in CaseService**

Add two fields and the method to `CaseService`:

```java
@Inject AccessControlProvider accessControlProvider;
@Inject CurrentPrincipal currentPrincipal;

private static final Logger LOG = Logger.getLogger(CaseService.class);

public CaseInstance requireCaseAccess(UUID caseId, AclAction action) {
    String tenancyId = currentPrincipal.tenancyId();
    CaseInstance instance = instanceRepository.findByUuid(caseId, tenancyId);
    if (instance == null) {
        throw new EntityNotFoundException("Case not found: " + caseId);
    }
    String actorId = currentPrincipal.actorId();
    String resourceId = AclResourceType.CASE + ":" + caseId;
    if (!accessControlProvider.canAccess(actorId, resourceId, action)) {
        LOG.warnf("ACL denied: actor=%s resource=%s action=%s", actorId, resourceId, action);
        throw new AccessDeniedException(actorId, resourceId, action);
    }
    return instance;
}
```

Imports: `io.casehub.platform.api.acl.AccessControlProvider`, `io.casehub.platform.api.acl.AclAction`,
`io.casehub.platform.api.acl.AclResourceType`, `io.casehub.platform.api.acl.AccessDeniedException`,
`io.casehub.platform.api.identity.CurrentPrincipal`, `org.jboss.logging.Logger`

- [ ] **Step 4: Run tests — verify they pass**

Run: `mvn test -pl rest -Dtest="CaseServiceAclTest" -f pom.xml`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```
feat(#768): add CaseService.requireCaseAccess() with ACL enforcement

Consolidated tenant + existence + ACL guard. Logs denials at WARN.
Refs #768
```

---

### Task 2: Sanitize `AccessDeniedExceptionMapper`

**Files:**
- Modify: `rest/src/main/java/io/casehub/engine/rest/exception/AccessDeniedExceptionMapper.java`

**Interfaces:**
- Consumes: `AccessDeniedException` from platform-api
- Produces: HTTP 403 with generic `ProblemDetail` (no internal context)

- [ ] **Step 1: Write failing test**

```java
// In CaseServiceAclTest or a new AccessDeniedExceptionMapperTest
@Test
void mapper_returns403_withoutInternalContext() {
    var mapper = new AccessDeniedExceptionMapper();
    var ex = new AccessDeniedException("alice", "case:abc-123", AclAction.READ);
    var response = mapper.toResponse(ex);
    assertThat(response.getStatus()).isEqualTo(403);
    var body = (ProblemDetail) response.getEntity();
    assertThat(body.detail()).doesNotContain("alice");
    assertThat(body.detail()).doesNotContain("abc-123");
    assertThat(body.detail()).isEqualTo("Insufficient permissions");
}
```

- [ ] **Step 2: Run test — verify it fails** (current mapper leaks context)

- [ ] **Step 3: Update AccessDeniedExceptionMapper**

Replace the `toResponse` body:

```java
@Override
public Response toResponse(AccessDeniedException exception) {
    return Response.status(403)
        .entity(new ProblemDetail("Access denied", 403, "Insufficient permissions"))
        .build();
}
```

- [ ] **Step 4: Run test — verify it passes**

- [ ] **Step 5: Commit**

```
fix(#768): sanitize 403 response — remove internal actorId/resourceId
Refs #768
```

---

### Task 3: Wire ACL into existing resources

**Files:**
- Modify: `rest/src/main/java/io/casehub/engine/rest/CaseInstanceResource.java`
- Modify: `rest/src/main/java/io/casehub/engine/rest/SignalResource.java`
- Modify: `rest/src/main/java/io/casehub/engine/rest/EventLogResource.java`

**Interfaces:**
- Consumes: `CaseService.requireCaseAccess(UUID, AclAction)` from Task 1

- [ ] **Step 1: Update `CaseInstanceResource`**

Replace `caseService.requireCase(caseId, currentPrincipal.tenancyId())` with
`caseService.requireCaseAccess(caseId, AclAction.READ)` in these methods:
- `getCaseInstance` — `READ`
- `getContext` — `READ`
- `getContextPath` — `READ`
- `getPlanItems` — `READ`
- `getGoals` — `READ`

For methods that used the returned instance, capture it from `requireCaseAccess()`.
For methods that only checked existence, the return value can be ignored.

Add import: `io.casehub.platform.api.acl.AclAction`

- [ ] **Step 2: Update `SignalResource`**

Replace `caseService.requireCase(caseId, currentPrincipal.tenancyId())` with
`caseService.requireCaseAccess(caseId, AclAction.WRITE)`.

- [ ] **Step 3: Update `EventLogResource`**

Replace `caseService.requireCase(caseId, currentPrincipal.tenancyId())` with
`caseService.requireCaseAccess(caseId, AclAction.READ)`.

- [ ] **Step 4: Run existing REST tests to verify no regressions**

Run: `mvn test -pl rest -f pom.xml`
Expected: all existing tests PASS (NoOp provider returns true)

- [ ] **Step 5: Commit**

```
feat(#768): wire requireCaseAccess into CaseInstance, Signal, EventLog resources
Refs #768
```

---

### Task 4: Fix `CaseControlResource` — full CaseService integration

**Files:**
- Modify: `rest/src/main/java/io/casehub/engine/rest/CaseControlResource.java`
- Create: `rest/src/test/java/io/casehub/engine/rest/CaseControlResourceAclTest.java`

**Interfaces:**
- Consumes: `CaseService.requireCaseAccess(UUID, AclAction)` from Task 1

- [ ] **Step 1: Write failing integration test**

```java
@QuarkusTest
class CaseControlResourceAclTest {

    @Test
    void suspend_withWrongTenant_returns404() {
        UUID randomCaseId = UUID.randomUUID();
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/v1/cases/" + randomCaseId + "/suspend")
        .then()
            .statusCode(404);
    }
}
```

This tests that CaseControlResource now checks case existence (currently it doesn't — it calls `runtime.suspendCase()` directly which throws `IllegalArgumentException`).

- [ ] **Step 2: Run test — verify current behavior**

The current code catches `IllegalArgumentException` and maps to `EntityNotFoundException`.
This test may already pass with the current code (the exception mapping might produce 404).
Check and adjust the test if needed.

- [ ] **Step 3: Update `CaseControlResource`**

Inject `CaseService` and `CurrentPrincipal`. Replace direct `runtime` calls:

```java
@Inject CaseService caseService;
@Inject CurrentPrincipal currentPrincipal;

public CaseControlResponse suspend(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.suspendCase(caseId);
    return new CaseControlResponse(caseId, "suspend", "completed");
}

public CaseControlResponse resume(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.resumeCase(caseId);
    return new CaseControlResponse(caseId, "resume", "completed");
}

public CaseControlResponse cancel(@PathParam("caseId") UUID caseId, CaseControlRequest request) {
    caseService.requireCaseAccess(caseId, AclAction.ADMIN);
    runtime.cancelCase(caseId);
    return new CaseControlResponse(caseId, "cancel", "completed");
}
```

Remove the `try-catch` blocks — `requireCaseAccess()` handles the 404 case.

- [ ] **Step 4: Run all REST tests**

Run: `mvn test -pl rest -f pom.xml`
Expected: all PASS

- [ ] **Step 5: Commit**

```
feat(#768): integrate CaseControlResource with CaseService ACL guard

Fixes missing tenant isolation on suspend/resume/cancel endpoints.
Refs #768
```

---

### Task 5: `AclEnforcementHealthCheck` — lockout prevention

**Files:**
- Create: `rest/src/main/java/io/casehub/engine/rest/health/AclEnforcementHealthCheck.java`

**Interfaces:**
- Consumes: `AccessControlProvider` (injected), `NoOpAccessControlProvider` (type check)

- [ ] **Step 1: Implement health check**

```java
package io.casehub.engine.rest.health;

import io.casehub.platform.api.acl.AccessControlProvider;
import io.casehub.platform.acl.NoOpAccessControlProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

@Readiness
@ApplicationScoped
public class AclEnforcementHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(AclEnforcementHealthCheck.class);

    @Inject AccessControlProvider accessControlProvider;

    @Override
    public HealthCheckResponse call() {
        boolean isNoOp = accessControlProvider instanceof NoOpAccessControlProvider;
        if (!isNoOp) {
            LOG.warn("ACL enforcement is active — ensure grants are provisioned "
                + "via AccessControlProvider.grant() or the authorization: YAML block. "
                + "Without grants, all case access will be denied.");
        }
        return HealthCheckResponse.named("acl-enforcement")
            .status(true)
            .withData("provider", accessControlProvider.getClass().getSimpleName())
            .withData("mode", isNoOp ? "permissive (NoOp)" : "enforcing")
            .build();
    }
}
```

Note: This always reports UP — it's informational, not a gate. The warning log is the signal.

- [ ] **Step 2: Verify health endpoint includes the check**

Run the app and check: `GET /q/health/ready` should include `acl-enforcement: UP`.

- [ ] **Step 3: Commit**

```
feat(#768): add AclEnforcementHealthCheck — warns when real ACL provider has no grants
Refs #768
```

---

### Task 6: Integration test — ACL enforcement end-to-end

**Files:**
- Create: `rest/src/test/java/io/casehub/engine/rest/AclEnforcementIntegrationTest.java`

**Interfaces:**
- Consumes: All changes from Tasks 1-5

- [ ] **Step 1: Create a denying `AccessControlProvider` test alternative**

```java
@Alternative
@Priority(1)
@ApplicationScoped
public static class DenyingAccessControlProvider implements AccessControlProvider {
    @Override
    public boolean canAccess(String actorId, String resourceId, AclAction action) {
        return false;
    }
}
```

- [ ] **Step 2: Write integration tests**

```java
@QuarkusTest
@TestProfile(AclEnforcementIntegrationTest.DenyingProfile.class)
class AclEnforcementIntegrationTest {

    // Need a real case — create one via CaseHub before the deny provider kicks in.
    // Actually, with deny-all, startCase still works (not guarded).
    // But requireCaseAccess will deny. So create a case first, then test access.

    @Test
    void getCaseInstance_denied_returns403() {
        // startCase is not guarded — this succeeds
        UUID caseId = startTestCase();

        given()
        .when()
            .get("/api/v1/cases/" + caseId)
        .then()
            .statusCode(403)
            .body("detail", equalTo("Insufficient permissions"));
    }

    @Test
    void sendSignal_denied_returns403() {
        UUID caseId = startTestCase();

        given()
            .contentType("application/json")
            .body("{\"path\": \"test\", \"value\": \"val\"}")
        .when()
            .post("/api/v1/cases/" + caseId + "/signals")
        .then()
            .statusCode(403);
    }

    @Test
    void suspend_denied_returns403() {
        UUID caseId = startTestCase();

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/v1/cases/" + caseId + "/suspend")
        .then()
            .statusCode(403);
    }

    @Test
    void startCase_notGuarded_succeeds() {
        // startCase is explicitly NOT guarded in v1
        given()
            .contentType("application/json")
            .body(startCasePayload())
        .when()
            .post("/api/v1/cases")
        .then()
            .statusCode(201);
    }
}
```

Helper methods (`startTestCase()`, `startCasePayload()`) and `DenyingProfile`
with `getEnabledAlternatives()` returning `DenyingAccessControlProvider`.

- [ ] **Step 3: Run all tests**

Run: `mvn test -pl rest -f pom.xml`
Expected: all PASS (existing tests use NoOp, new tests use deny alternative)

- [ ] **Step 4: Commit**

```
test(#768): ACL enforcement integration tests — deny-all provider validates 403 paths
Refs #768
```

---

### Task 7: Final verification

- [ ] **Step 1: Full module test suite**

Run: `mvn test -pl rest -f pom.xml`
Expected: all PASS

- [ ] **Step 2: Cross-module build**

Run: `mvn install -DskipTests -f pom.xml` (verify no compilation errors across modules)

- [ ] **Step 3: Verify diagnostics**

Run `ide_diagnostics` on all modified files — zero errors.

- [ ] **Step 4: Final commit if any cleanup needed**

```
chore(#768): final cleanup
Refs #768
```
