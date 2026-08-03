# Worker Rights Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** casehubio/platform#221 — worker rights model and authorization service SPI
**Issue group:** casehubio/engine#833 (epic Batch 3)

**Goal:** Enable privileged external workers to access cases via REST with
case-scoped, structurally-isolated credentials and auto-managed ACL grants.

**Architecture:** Foundation types (WorkerAction, WorkerCredential, WorkerCredentialStore)
in engine-common. Declaration surface (permissionIntent, serviceAccountId) on Binding and
CaseDefinition. Grant orchestration in runtime. Scoped-token REST filter in engine-rest.
Token threading via WorkerScheduleEvent → EventLog → WorkflowExecutionCompleted.

**Tech Stack:** Quarkus CDI, JAX-RS ContainerRequestFilter, java.security.SecureRandom

## Global Constraints

- `serviceAccountId` is stored as `Map<String, String>` on `CaseDefinition` (keyed by worker
  name), NOT on the `io.casehub.worker.api.Worker` record (published foundation-tier artifact).
  `WorkerIdentityResolver` takes both Worker and CaseDefinition as parameters.
- All `WorkerAction` grants map to `AclResourceType.CASE` — per-resource-type enforcement
  deferred until REST layer supports it.
- Default permissionIntent is `[READ_CONTEXT]` (fail-closed for writes).
- `InMemoryWorkerCredentialStore` is `@DefaultBean` — feature works out of the box.
- `serviceAccountId` must resolve to `ActorType.AGENT` via `ActorTypeResolver` — validated
  at CaseDefinition build time.

---

### Task 1: Foundation types in engine-common

**Files:**
- Create: `common/src/main/java/io/casehub/engine/common/acl/AclGrant.java`
- Create: `common/src/main/java/io/casehub/engine/common/acl/WorkerAction.java`
- Create: `common/src/main/java/io/casehub/engine/common/acl/WorkerCredential.java`
- Create: `common/src/main/java/io/casehub/engine/common/acl/WorkerCredentialStore.java`
- Create: `common/src/main/java/io/casehub/engine/common/acl/InMemoryWorkerCredentialStore.java`
- Test: `common/src/test/java/io/casehub/engine/common/acl/WorkerActionTest.java`
- Test: `common/src/test/java/io/casehub/engine/common/acl/InMemoryWorkerCredentialStoreTest.java`

**Interfaces:**
- Consumes: `io.casehub.platform.api.acl.AclAction`, `io.casehub.platform.api.acl.AclResourceType`
- Produces: `WorkerAction` enum, `AclGrant` record, `WorkerCredential` record, `WorkerCredentialStore` SPI, `InMemoryWorkerCredentialStore`

- [ ] **Step 1: Write WorkerAction test**

```java
package io.casehub.engine.common.acl;

import static org.junit.jupiter.api.Assertions.*;
import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WorkerActionTest {

    @ParameterizedTest
    @EnumSource(WorkerAction.class)
    void allActionsMaptoCaseResourceType(WorkerAction action) {
        AclGrant grant = action.toAclGrant();
        assertEquals(AclResourceType.CASE, grant.resourceType());
    }

    @Test
    void readContextMapsToRead() {
        assertEquals(AclAction.READ, WorkerAction.READ_CONTEXT.toAclGrant().action());
    }

    @Test
    void writeContextMapsToWrite() {
        assertEquals(AclAction.WRITE, WorkerAction.WRITE_CONTEXT.toAclGrant().action());
    }

    @Test
    void signalCaseMapsToWrite() {
        assertEquals(AclAction.WRITE, WorkerAction.SIGNAL_CASE.toAclGrant().action());
    }

    @Test
    void adminMapsToAdmin() {
        assertEquals(AclAction.ADMIN, WorkerAction.ADMIN.toAclGrant().action());
    }

    @Test
    void readEventLogMapsToRead() {
        assertEquals(AclAction.READ, WorkerAction.READ_EVENT_LOG.toAclGrant().action());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl common -Dtest=WorkerActionTest -q`
Expected: FAIL — `WorkerAction` not found

- [ ] **Step 3: Implement AclGrant and WorkerAction**

Create `common/src/main/java/io/casehub/engine/common/acl/AclGrant.java`:
```java
package io.casehub.engine.common.acl;

import io.casehub.platform.api.acl.AclAction;

public record AclGrant(AclAction action, String resourceType) {}
```

Create `common/src/main/java/io/casehub/engine/common/acl/WorkerAction.java`:
```java
package io.casehub.engine.common.acl;

import io.casehub.platform.api.acl.AclAction;
import io.casehub.platform.api.acl.AclResourceType;

public enum WorkerAction {
    READ_CONTEXT(AclAction.READ),
    WRITE_CONTEXT(AclAction.WRITE),
    SIGNAL_CASE(AclAction.WRITE),
    READ_EVENT_LOG(AclAction.READ),
    READ_PLAN_ITEMS(AclAction.READ),
    SPAWN_SUB_CASE(AclAction.WRITE),
    ADMIN(AclAction.ADMIN);

    private final AclAction aclAction;

    WorkerAction(AclAction aclAction) {
        this.aclAction = aclAction;
    }

    public AclGrant toAclGrant() {
        return new AclGrant(aclAction, AclResourceType.CASE);
    }

    public AclAction aclAction() {
        return aclAction;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl common -Dtest=WorkerActionTest -q`
Expected: PASS

- [ ] **Step 5: Write InMemoryWorkerCredentialStore test**

```java
package io.casehub.engine.common.acl;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryWorkerCredentialStoreTest {

    private InMemoryWorkerCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkerCredentialStore();
    }

    @Test
    void storeAndLookup() {
        var credential = credential("token-1", "agent:w1", UUID.randomUUID());
        store.store(credential);
        var found = store.lookup("token-1");
        assertTrue(found.isPresent());
        assertEquals("agent:w1", found.get().actorId());
    }

    @Test
    void lookupMissing_returnsEmpty() {
        assertTrue(store.lookup("nonexistent").isEmpty());
    }

    @Test
    void revoke_removesCredential() {
        var credential = credential("token-1", "agent:w1", UUID.randomUUID());
        store.store(credential);
        store.revoke("token-1");
        assertTrue(store.lookup("token-1").isEmpty());
    }

    @Test
    void revokeByCase_removesAllForCase() {
        UUID caseId = UUID.randomUUID();
        store.store(credential("t1", "agent:w1", caseId));
        store.store(credential("t2", "agent:w2", caseId));
        store.store(credential("t3", "agent:w3", UUID.randomUUID()));

        var revoked = store.revokeByCase(caseId);
        assertEquals(2, revoked.size());
        assertTrue(store.lookup("t1").isEmpty());
        assertTrue(store.lookup("t2").isEmpty());
        assertTrue(store.lookup("t3").isPresent());
    }

    @Test
    void revokeByActor_removesAllForActor() {
        store.store(credential("t1", "agent:pool", UUID.randomUUID()));
        store.store(credential("t2", "agent:pool", UUID.randomUUID()));
        store.store(credential("t3", "agent:other", UUID.randomUUID()));

        var revoked = store.revokeByActor("agent:pool");
        assertEquals(2, revoked.size());
        assertTrue(store.lookup("t3").isPresent());
    }

    @Test
    void findActiveByActorAndCase_returnsMatching() {
        UUID caseId = UUID.randomUUID();
        store.store(credential("t1", "agent:pool", caseId));
        store.store(credential("t2", "agent:pool", caseId));
        store.store(credential("t3", "agent:pool", UUID.randomUUID()));

        var active = store.findActiveByActorAndCase("agent:pool", caseId);
        assertEquals(2, active.size());
    }

    private WorkerCredential credential(String token, String actorId, UUID caseId) {
        return new WorkerCredential(
            token, actorId, caseId,
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().plusSeconds(3600), Instant.now());
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn test -pl common -Dtest=InMemoryWorkerCredentialStoreTest -q`
Expected: FAIL — classes not found

- [ ] **Step 7: Implement WorkerCredential, WorkerCredentialStore, InMemoryWorkerCredentialStore**

Create `common/src/main/java/io/casehub/engine/common/acl/WorkerCredential.java`:
```java
package io.casehub.engine.common.acl;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WorkerCredential(
    String token,
    String actorId,
    UUID caseId,
    Set<WorkerAction> actions,
    Instant expiresAt,
    Instant createdAt) {

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
```

Create `common/src/main/java/io/casehub/engine/common/acl/WorkerCredentialStore.java`:
```java
package io.casehub.engine.common.acl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerCredentialStore {
    void store(WorkerCredential credential);
    Optional<WorkerCredential> lookup(String token);
    void revoke(String token);
    List<WorkerCredential> revokeByCase(UUID caseId);
    List<WorkerCredential> revokeByActor(String actorId);
    List<WorkerCredential> findActiveByActorAndCase(String actorId, UUID caseId);
}
```

Create `common/src/main/java/io/casehub/engine/common/acl/InMemoryWorkerCredentialStore.java`:
```java
package io.casehub.engine.common.acl;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@DefaultBean
@ApplicationScoped
public class InMemoryWorkerCredentialStore implements WorkerCredentialStore {

    private final ConcurrentHashMap<String, WorkerCredential> store = new ConcurrentHashMap<>();

    @Override
    public void store(WorkerCredential credential) {
        store.put(credential.token(), credential);
    }

    @Override
    public Optional<WorkerCredential> lookup(String token) {
        return Optional.ofNullable(store.get(token));
    }

    @Override
    public void revoke(String token) {
        store.remove(token);
    }

    @Override
    public List<WorkerCredential> revokeByCase(UUID caseId) {
        var revoked = store.values().stream()
            .filter(c -> c.caseId().equals(caseId))
            .toList();
        revoked.forEach(c -> store.remove(c.token()));
        return revoked;
    }

    @Override
    public List<WorkerCredential> revokeByActor(String actorId) {
        var revoked = store.values().stream()
            .filter(c -> c.actorId().equals(actorId))
            .toList();
        revoked.forEach(c -> store.remove(c.token()));
        return revoked;
    }

    @Override
    public List<WorkerCredential> findActiveByActorAndCase(String actorId, UUID caseId) {
        return store.values().stream()
            .filter(c -> c.actorId().equals(actorId) && c.caseId().equals(caseId))
            .toList();
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn test -pl common -Dtest=InMemoryWorkerCredentialStoreTest -q`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/io/casehub/engine/common/acl/ common/src/test/java/io/casehub/engine/common/acl/
git commit -m "feat(#221): add WorkerAction, WorkerCredential, WorkerCredentialStore foundation types

Refs #833"
```

---

### Task 2: Declaration surface — permissionIntent on Binding, serviceAccountId on CaseDefinition

**Files:**
- Modify: `api/src/main/java/io/casehub/api/model/Binding.java` — add `permissionIntent` field, getter, setter, builder method
- Modify: `api/src/main/java/io/casehub/api/model/CaseDefinition.java` — add `workerServiceAccountIds` map, builder method, validation
- Modify: `api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java` — parse YAML `permissionIntent` and `serviceAccountId`
- Test: `api/src/test/java/io/casehub/api/model/BindingPermissionIntentTest.java`
- Test: `api/src/test/java/io/casehub/api/model/WorkerServiceAccountIdTest.java`
- Test: `api/src/test/java/io/casehub/api/model/converter/CaseDefinitionYamlMapperTest.java` — add YAML test cases

**Interfaces:**
- Consumes: `WorkerAction` from Task 1
- Produces: `Binding.getPermissionIntent()`, `CaseDefinition.getWorkerServiceAccountId(String workerName)`, YAML parsing

- [ ] **Step 1: Write Binding permissionIntent test**

```java
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.*;
import io.casehub.engine.common.acl.WorkerAction;
import java.util.List;
import org.junit.jupiter.api.Test;

class BindingPermissionIntentTest {

    @Test
    void permissionIntent_setAndGet() {
        var binding = Binding.builder()
            .name("b1")
            .capability(new io.casehub.worker.api.Capability("cap1", null, null))
            .on(new ContextChangeTrigger(".ready"))
            .permissionIntent(List.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE))
            .build();

        assertEquals(List.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE),
            binding.getPermissionIntent());
    }

    @Test
    void permissionIntent_defaultsNull() {
        var binding = Binding.builder()
            .name("b1")
            .capability(new io.casehub.worker.api.Capability("cap1", null, null))
            .on(new ContextChangeTrigger(".ready"))
            .build();

        assertNull(binding.getPermissionIntent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl api -Dtest=BindingPermissionIntentTest -q`
Expected: FAIL — `permissionIntent` method not found

- [ ] **Step 3: Add permissionIntent to Binding**

Use `ide_insert_member` to add the field, getter/setter, and builder method to `Binding.java`.

Field (after `executionMode`):
```java
private List<WorkerAction> permissionIntent;
```

Getter:
```java
public List<WorkerAction> getPermissionIntent() {
    return permissionIntent;
}
```

Setter:
```java
public void setPermissionIntent(List<WorkerAction> permissionIntent) {
    this.permissionIntent = permissionIntent != null ? List.copyOf(permissionIntent) : null;
}
```

Builder field:
```java
private List<WorkerAction> permissionIntent;
```

Builder method:
```java
public Builder permissionIntent(List<WorkerAction> permissionIntent) {
    this.permissionIntent = permissionIntent;
    return this;
}
```

In `Builder.build()`, add after executionMode wiring:
```java
if (permissionIntent != null) {
    binding.setPermissionIntent(permissionIntent);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl api -Dtest=BindingPermissionIntentTest -q`
Expected: PASS

- [ ] **Step 5: Write CaseDefinition workerServiceAccountIds test**

```java
package io.casehub.api.model;

import static org.junit.jupiter.api.Assertions.*;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.ActorTypeResolver;
import org.junit.jupiter.api.Test;

class WorkerServiceAccountIdTest {

    @Test
    void workerServiceAccountId_setAndGet() {
        var def = CaseDefinition.builder()
            .namespace("ns").name("test").version("1.0")
            .workerServiceAccountId("my-worker", "agent:pool-1@acme.io")
            .build();

        assertEquals("agent:pool-1@acme.io", def.getWorkerServiceAccountId("my-worker"));
    }

    @Test
    void workerServiceAccountId_missingWorker_returnsNull() {
        var def = CaseDefinition.builder()
            .namespace("ns").name("test").version("1.0")
            .build();

        assertNull(def.getWorkerServiceAccountId("nonexistent"));
    }

    @Test
    void workerServiceAccountId_rejectsHumanIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
            CaseDefinition.builder()
                .namespace("ns").name("test").version("1.0")
                .workerServiceAccountId("my-worker", "mark@acme.io")
                .build());
    }

    @Test
    void workerServiceAccountId_rejectsSystemIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
            CaseDefinition.builder()
                .namespace("ns").name("test").version("1.0")
                .workerServiceAccountId("my-worker", "system:internal")
                .build());
    }

    @Test
    void workerServiceAccountId_acceptsAgentPrefix() {
        var def = CaseDefinition.builder()
            .namespace("ns").name("test").version("1.0")
            .workerServiceAccountId("my-worker", "agent:claudony-1")
            .build();

        assertEquals("agent:claudony-1", def.getWorkerServiceAccountId("my-worker"));
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn test -pl api -Dtest=WorkerServiceAccountIdTest -q`
Expected: FAIL — `workerServiceAccountId` method not found

- [ ] **Step 7: Add workerServiceAccountIds to CaseDefinition**

Add field to `CaseDefinition`:
```java
private Map<String, String> workerServiceAccountIds;
```

Add getter:
```java
public String getWorkerServiceAccountId(String workerName) {
    return workerServiceAccountIds != null ? workerServiceAccountIds.get(workerName) : null;
}

public Map<String, String> getWorkerServiceAccountIds() {
    return workerServiceAccountIds;
}
```

Add setter:
```java
public void setWorkerServiceAccountIds(Map<String, String> workerServiceAccountIds) {
    this.workerServiceAccountIds = workerServiceAccountIds;
}
```

Add to Builder — field:
```java
private Map<String, String> workerServiceAccountIds;
```

Builder method:
```java
public Builder workerServiceAccountId(String workerName, String serviceAccountId) {
    if (this.workerServiceAccountIds == null) {
        this.workerServiceAccountIds = new java.util.HashMap<>();
    }
    this.workerServiceAccountIds.put(workerName, serviceAccountId);
    return this;
}
```

In `Builder.build()`, add validation and wiring:
```java
if (workerServiceAccountIds != null) {
    for (var entry : workerServiceAccountIds.entrySet()) {
        var actorType = ActorTypeResolver.resolve(entry.getValue());
        if (actorType != ActorType.AGENT) {
            throw new IllegalArgumentException(
                "serviceAccountId for worker '" + entry.getKey()
                + "' must resolve to AGENT, got " + actorType
                + " for '" + entry.getValue() + "'");
        }
    }
    caseHubDefinition.setWorkerServiceAccountIds(Map.copyOf(workerServiceAccountIds));
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn test -pl api -Dtest=WorkerServiceAccountIdTest -q`
Expected: PASS

- [ ] **Step 9: Add YAML mapping for permissionIntent and serviceAccountId**

In `CaseDefinitionYamlMapper`, add parsing for:
- `permissionIntent` on binding definitions — kebab-case to enum
- `serviceAccountId` on worker definitions — stored in CaseDefinition map

Verify with existing YAML mapper test patterns. Add test cases:

```java
@Test
void load_bindingPermissionIntent_parsedToWorkerActionList() throws IOException {
    String yaml = """
        spec:
          capabilities:
            - name: assess-risk
          workers:
            - name: risk-agent
              capabilities: [assess-risk]
          bindings:
            - name: b1
              capability: assess-risk
              worker: risk-agent
              on:
                contextChanged: ".ready"
              permissionIntent:
                - read-context
                - signal-case
        """;
    var def = loadDefinition(yaml);
    var binding = def.getBindings().get(0);
    assertEquals(List.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE),
        binding.getPermissionIntent());
}

@Test
void load_workerServiceAccountId_storedOnDefinition() throws IOException {
    String yaml = """
        spec:
          capabilities:
            - name: assess-risk
          workers:
            - name: risk-agent
              serviceAccountId: "agent:pool-1@acme.io"
              capabilities: [assess-risk]
        """;
    var def = loadDefinition(yaml);
    assertEquals("agent:pool-1@acme.io", def.getWorkerServiceAccountId("risk-agent"));
}
```

- [ ] **Step 10: Run full API test suite**

Run: `mvn test -pl api -q`
Expected: PASS — all existing tests + new tests

- [ ] **Step 11: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/Binding.java api/src/main/java/io/casehub/api/model/CaseDefinition.java api/src/main/java/io/casehub/api/model/converter/CaseDefinitionYamlMapper.java api/src/test/java/
git commit -m "feat(#221): add permissionIntent on Binding, workerServiceAccountIds on CaseDefinition

Refs #833"
```

---

### Task 3: WorkerIdentityResolver and WorkerGrantOrchestrator

**Files:**
- Create: `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerIdentity.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerIdentityResolver.java`
- Create: `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerGrantOrchestrator.java`
- Test: `runtime/src/test/java/io/casehub/engine/internal/acl/WorkerIdentityResolverTest.java`
- Test: `runtime/src/test/java/io/casehub/engine/internal/acl/WorkerGrantOrchestratorTest.java`

**Interfaces:**
- Consumes: `WorkerAction`, `WorkerCredential`, `WorkerCredentialStore` from Task 1; `CaseDefinition.getWorkerServiceAccountId()` from Task 2; `AccessControlProvider`, `AclEntryRequest` from platform-api
- Produces: `WorkerIdentityResolver.resolve()`, `WorkerGrantOrchestrator.grantAndMint()`, `WorkerGrantOrchestrator.revokeForWorker()`, `WorkerGrantOrchestrator.revokeForCase()`

- [ ] **Step 1: Write WorkerIdentityResolver test**

```java
package io.casehub.engine.internal.acl;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkerIdentityResolverTest {

    private final WorkerIdentityResolver resolver = new WorkerIdentityResolver();

    @Test
    void resolve_withServiceAccountId_usesIt() {
        UUID caseId = UUID.randomUUID();
        var identity = resolver.resolve("agent:pool-1@acme.io", caseId);
        assertEquals("agent:pool-1@acme.io", identity.actorId());
        assertFalse(identity.ephemeral());
    }

    @Test
    void resolve_withoutServiceAccountId_mintsEphemeral() {
        UUID caseId = UUID.randomUUID();
        var identity = resolver.resolve(null, caseId);
        assertTrue(identity.actorId().startsWith("agent:worker-"));
        assertTrue(identity.actorId().contains(caseId.toString().substring(0, 8)));
        assertTrue(identity.ephemeral());
    }

    @Test
    void resolve_ephemeralIdentitiesAreUnique() {
        UUID caseId = UUID.randomUUID();
        var id1 = resolver.resolve(null, caseId);
        var id2 = resolver.resolve(null, caseId);
        assertNotEquals(id1.actorId(), id2.actorId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl runtime -Dtest=WorkerIdentityResolverTest -q`
Expected: FAIL — class not found

- [ ] **Step 3: Implement WorkerIdentity and WorkerIdentityResolver**

Create `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerIdentity.java`:
```java
package io.casehub.engine.internal.acl;

public record WorkerIdentity(String actorId, boolean ephemeral) {}
```

Create `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerIdentityResolver.java`:
```java
package io.casehub.engine.internal.acl;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class WorkerIdentityResolver {

    public WorkerIdentity resolve(String serviceAccountId, UUID caseId) {
        if (serviceAccountId != null) {
            return new WorkerIdentity(serviceAccountId, false);
        }
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        String casePrefix = caseId.toString().substring(0, 8);
        return new WorkerIdentity("agent:worker-" + casePrefix + "-" + shortId, true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl runtime -Dtest=WorkerIdentityResolverTest -q`
Expected: PASS

- [ ] **Step 5: Write WorkerGrantOrchestrator test**

```java
package io.casehub.engine.internal.acl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.casehub.engine.common.acl.*;
import io.casehub.platform.api.acl.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerGrantOrchestratorTest {

    private AccessControlProvider aclProvider;
    private InMemoryWorkerCredentialStore credentialStore;
    private WorkerIdentityResolver identityResolver;
    private WorkerGrantOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        aclProvider = mock(AccessControlProvider.class);
        credentialStore = new InMemoryWorkerCredentialStore();
        identityResolver = new WorkerIdentityResolver();
        orchestrator = new WorkerGrantOrchestrator(aclProvider, credentialStore, identityResolver);
    }

    @Test
    void grantAndMint_createsCredentialAndGrants() {
        UUID caseId = UUID.randomUUID();
        var actions = List.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE);
        Instant deadline = Instant.now().plusSeconds(300);

        var credential = orchestrator.grantAndMint(
            null, actions, caseId, "tenant-1", deadline);

        assertNotNull(credential);
        assertTrue(credential.actorId().startsWith("agent:worker-"));
        assertEquals(caseId, credential.caseId());
        assertTrue(credentialStore.lookup(credential.token()).isPresent());
        verify(aclProvider).grantBatch(anyCollection());
    }

    @Test
    void grantAndMint_withServiceAccount_usesIt() {
        UUID caseId = UUID.randomUUID();
        var actions = List.of(WorkerAction.READ_CONTEXT);
        Instant deadline = Instant.now().plusSeconds(300);

        var credential = orchestrator.grantAndMint(
            "agent:pool-1", actions, caseId, "tenant-1", deadline);

        assertEquals("agent:pool-1", credential.actorId());
    }

    @Test
    void revokeForWorker_ephemeral_revokesAll() {
        UUID caseId = UUID.randomUUID();
        var credential = storeCredential("t1", "agent:w1", caseId);

        orchestrator.revokeForWorker("t1", "agent:w1", caseId, true);

        assertTrue(credentialStore.lookup("t1").isEmpty());
        verify(aclProvider).revokeBatch(anyCollection());
    }

    @Test
    void revokeForWorker_sharedServiceAccount_onlyRevokesUnneededGrants() {
        UUID caseId = UUID.randomUUID();
        // Two credentials for same actor on same case
        credentialStore.store(new WorkerCredential(
            "t1", "agent:pool", caseId,
            Set.of(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE),
            Instant.now().plusSeconds(3600), Instant.now()));
        credentialStore.store(new WorkerCredential(
            "t2", "agent:pool", caseId,
            Set.of(WorkerAction.READ_CONTEXT, WorkerAction.ADMIN),
            Instant.now().plusSeconds(3600), Instant.now()));

        // Revoke t1 — READ_CONTEXT still needed by t2, SIGNAL_CASE is not
        orchestrator.revokeForWorker("t1", "agent:pool", caseId, false);

        assertTrue(credentialStore.lookup("t1").isEmpty());
        assertTrue(credentialStore.lookup("t2").isPresent());
        // Only WRITE (from SIGNAL_CASE) should be revoked, not READ (still needed by t2)
        verify(aclProvider).revokeBatch(argThat(requests -> {
            var list = new ArrayList<>(requests);
            return list.size() == 1
                && list.get(0).action() == AclAction.WRITE;
        }));
    }

    @Test
    void revokeForCase_sweepsAll() {
        UUID caseId = UUID.randomUUID();
        credentialStore.store(credential("t1", "agent:w1", caseId));
        credentialStore.store(credential("t2", "agent:w2", caseId));

        orchestrator.revokeForCase(caseId);

        assertTrue(credentialStore.lookup("t1").isEmpty());
        assertTrue(credentialStore.lookup("t2").isEmpty());
        verify(aclProvider, times(2)).revokeAll(anyString(), anyString());
    }

    private WorkerCredential credential(String token, String actorId, UUID caseId) {
        return new WorkerCredential(token, actorId, caseId,
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().plusSeconds(3600), Instant.now());
    }

    private WorkerCredential storeCredential(String token, String actorId, UUID caseId) {
        var c = credential(token, actorId, caseId);
        credentialStore.store(c);
        return c;
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn test -pl runtime -Dtest=WorkerGrantOrchestratorTest -q`
Expected: FAIL — `WorkerGrantOrchestrator` not found

- [ ] **Step 7: Implement WorkerGrantOrchestrator**

Create `runtime/src/main/java/io/casehub/engine/internal/acl/WorkerGrantOrchestrator.java`:
```java
package io.casehub.engine.internal.acl;

import io.casehub.engine.common.acl.*;
import io.casehub.platform.api.acl.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkerGrantOrchestrator {

    private static final Logger LOG = Logger.getLogger(WorkerGrantOrchestrator.class);
    private static final Duration MAX_CREDENTIAL_TTL = Duration.ofHours(1);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccessControlProvider accessControlProvider;
    private final WorkerCredentialStore credentialStore;
    private final WorkerIdentityResolver identityResolver;

    @Inject
    public WorkerGrantOrchestrator(
            AccessControlProvider accessControlProvider,
            WorkerCredentialStore credentialStore,
            WorkerIdentityResolver identityResolver) {
        this.accessControlProvider = accessControlProvider;
        this.credentialStore = credentialStore;
        this.identityResolver = identityResolver;
    }

    public WorkerCredential grantAndMint(
            String serviceAccountId,
            List<WorkerAction> actions,
            UUID caseId,
            String tenancyId,
            Instant deadline) {

        var identity = identityResolver.resolve(serviceAccountId, caseId);

        Set<AclGrant> grants = actions.stream()
            .map(WorkerAction::toAclGrant)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        String resourceId = AclResourceType.CASE + ":" + caseId;
        List<AclEntryRequest> requests = grants.stream()
            .map(g -> new AclEntryRequest(identity.actorId(), resourceId, g.action(), null))
            .toList();
        accessControlProvider.grantBatch(requests);

        Instant maxExpiry = Instant.now().plus(MAX_CREDENTIAL_TTL);
        Instant expiry = deadline != null && deadline.isBefore(maxExpiry)
            ? deadline : maxExpiry;

        String token = generateToken();
        var credential = new WorkerCredential(
            token, identity.actorId(), caseId,
            Set.copyOf(actions), expiry, Instant.now());
        credentialStore.store(credential);

        LOG.infof("Granted worker credential: actor=%s case=%s actions=%s expires=%s",
            identity.actorId(), caseId, actions, expiry);
        return credential;
    }

    public void revokeForWorker(String token, String actorId, UUID caseId, boolean ephemeral) {
        var revoked = credentialStore.lookup(token);
        credentialStore.revoke(token);

        if (revoked.isEmpty()) {
            LOG.warnf("Credential not found for revocation: token=%s", token);
            return;
        }

        Set<AclGrant> revokedGrants = revoked.get().actions().stream()
            .map(WorkerAction::toAclGrant)
            .collect(Collectors.toSet());

        if (!ephemeral) {
            var remaining = credentialStore.findActiveByActorAndCase(actorId, caseId);
            Set<AclGrant> stillNeeded = remaining.stream()
                .flatMap(c -> c.actions().stream())
                .map(WorkerAction::toAclGrant)
                .collect(Collectors.toSet());
            revokedGrants.removeAll(stillNeeded);
        }

        if (!revokedGrants.isEmpty()) {
            String resourceId = AclResourceType.CASE + ":" + caseId;
            List<AclEntryRequest> requests = revokedGrants.stream()
                .map(g -> new AclEntryRequest(actorId, resourceId, g.action(), null))
                .toList();
            accessControlProvider.revokeBatch(requests);
        }

        LOG.infof("Revoked worker credential: actor=%s case=%s ephemeral=%s", actorId, caseId, ephemeral);
    }

    public void revokeForCase(UUID caseId) {
        var revoked = credentialStore.revokeByCase(caseId);
        for (var credential : revoked) {
            String resourceId = AclResourceType.CASE + ":" + caseId;
            accessControlProvider.revokeAll(credential.actorId(), resourceId);
        }
        if (!revoked.isEmpty()) {
            LOG.infof("Case terminal sweep: revoked %d credential(s) for case=%s", revoked.size(), caseId);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn test -pl runtime -Dtest=WorkerGrantOrchestratorTest -q`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add runtime/src/main/java/io/casehub/engine/internal/acl/ runtime/src/test/java/io/casehub/engine/internal/acl/
git commit -m "feat(#221): add WorkerIdentityResolver and WorkerGrantOrchestrator

Refs #833"
```

---

### Task 4: Token threading — WorkerScheduleEvent, WorkflowExecutionCompleted, EventLog metadata

**Files:**
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/WorkerScheduleEvent.java` — add `workerCredentialToken` field
- Modify: `common/src/main/java/io/casehub/engine/common/internal/event/WorkflowExecutionCompleted.java` — add `workerCredentialToken` field
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkerScheduleEventHandler.java` — write token to EventLog metadata
- Modify: `scheduler-quartz/src/main/java/io/casehub/engine/scheduler/quartz/QuartzWorkerExecutionJob.java` — read token from EventLog metadata, pass to WorkflowExecutionCompleted
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/WorkflowExecutionCompletedHandler.java` — call `revokeForWorker()` on all outcomes
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseStatusChangedHandler.java` — call `revokeForCase()` on terminal state
- Test: `runtime/src/test/java/io/casehub/engine/internal/acl/WorkerGrantRevocationTest.java`

**Interfaces:**
- Consumes: `WorkerGrantOrchestrator` from Task 3
- Produces: Token threading through the full dispatch → completion → revocation lifecycle

- [ ] **Step 1: Add workerCredentialToken to WorkerScheduleEvent**

Add new field to the canonical constructor. Add a convenience constructor that takes the token. Update existing constructors to pass `null` for the new field.

```java
public record WorkerScheduleEvent(
    CaseInstance caseInstance,
    Worker worker,
    Capability capability,
    String bindingName,
    String inputProjectionOverride,
    UUID signalId,
    ExecutionOrigin origin,
    List<RetrievedExperience> experiences,
    io.casehub.api.model.LifecycleScope lifecycleScope,
    io.casehub.api.model.ExecutionMode executionMode,
    String workerCredentialToken) {
    // ... existing compact constructor + convenience constructors updated with null for token
}
```

- [ ] **Step 2: Add workerCredentialToken to WorkflowExecutionCompleted**

Add new field to the canonical constructor. Update existing constructors and factory methods to pass `null`.

```java
public record WorkflowExecutionCompleted(
    CaseInstance caseInstance,
    Worker worker,
    String idempotency,
    Map<String, Object> output,
    String bindingName,
    WorkerOutcome outcome,
    UUID signalId,
    String workerCredentialToken) {
    // ... existing convenience constructors updated
}
```

- [ ] **Step 3: Write EventLog metadata threading in WorkerScheduleEventHandler**

In `WorkerScheduleEventHandler`, where EventLog metadata is written (alongside `bindingName`, `signalId`), add:
```java
if (event.workerCredentialToken() != null) {
    metadata.put("workerCredentialToken", event.workerCredentialToken());
}
```

- [ ] **Step 4: Read token in QuartzWorkerExecutionJob, pass to WorkflowExecutionCompleted**

In `QuartzWorkerExecutionJob`, where EventLog metadata is read (alongside `bindingName`, `signalId`), add:
```java
String workerCredentialToken = (String) metadata.get("workerCredentialToken");
```

Pass to `WorkflowExecutionCompleted` constructor.

- [ ] **Step 5: Add revocation call in WorkflowExecutionCompletedHandler**

Inject `WorkerGrantOrchestrator`. After recording the outcome (success or failure path), add:
```java
String token = event.workerCredentialToken();
if (token != null) {
    orchestrator.revokeForWorker(
        token, event.worker().name(), event.caseInstance().getUuid(),
        /* ephemeral determined from credential lookup */ true);
}
```

The orchestrator's `revokeForWorker` handles the ephemeral/service-account distinction internally via the credential store lookup.

- [ ] **Step 6: Add revokeForCase in CaseStatusChangedHandler**

Inject `WorkerGrantOrchestrator`. In the terminal-state handling block (where `CaseChannelProvider.closeChannel()` is called), add:
```java
orchestrator.revokeForCase(instance.getUuid());
```

- [ ] **Step 7: Fix compilation — update all call sites for the new record fields**

Search for all construction sites of `WorkerScheduleEvent` and `WorkflowExecutionCompleted`:
```
ide_find_references for WorkerScheduleEvent constructor
ide_find_references for WorkflowExecutionCompleted constructor
```

Update each to pass `null` for `workerCredentialToken` where the token is not available.

- [ ] **Step 8: Run the full runtime + scheduler-quartz test suites**

Run: `mvn test -pl runtime,scheduler-quartz -q`
Expected: PASS — all existing tests + new revocation tests

- [ ] **Step 9: Commit**

```bash
git add common/src/main/java/ runtime/src/main/java/ scheduler-quartz/src/main/java/ runtime/src/test/java/
git commit -m "feat(#221): thread credential token through dispatch → completion → revocation

Refs #833"
```

---

### Task 5: WorkerCredentialFilter — REST structural isolation

**Files:**
- Create: `rest/src/main/java/io/casehub/engine/rest/filter/WorkerCredentialFilter.java`
- Create: `rest/src/main/java/io/casehub/engine/rest/filter/WorkerCredentialPrincipal.java`
- Test: `rest/src/test/java/io/casehub/engine/rest/filter/WorkerCredentialFilterTest.java`

**Interfaces:**
- Consumes: `WorkerCredentialStore.lookup()` from Task 1
- Produces: JAX-RS filter that validates X-Worker-Credential header, sets request-scoped CurrentPrincipal

- [ ] **Step 1: Write WorkerCredentialFilter test**

```java
package io.casehub.engine.rest.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.casehub.engine.common.acl.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerCredentialFilterTest {

    private WorkerCredentialStore credentialStore;
    private WorkerCredentialFilter filter;
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() {
        credentialStore = new InMemoryWorkerCredentialStore();
        filter = new WorkerCredentialFilter(credentialStore);
        requestContext = mock(ContainerRequestContext.class);
    }

    @Test
    void noHeader_passesThrough() {
        when(requestContext.getHeaderString("X-Worker-Credential")).thenReturn(null);
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void validToken_matchingCase_setsIdentity() {
        UUID caseId = UUID.randomUUID();
        var cred = new WorkerCredential("tok1", "agent:w1", caseId,
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().plusSeconds(3600), Instant.now());
        credentialStore.store(cred);

        when(requestContext.getHeaderString("X-Worker-Credential")).thenReturn("tok1");
        var uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("cases/" + caseId + "/context");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    void unknownToken_returns401() {
        when(requestContext.getHeaderString("X-Worker-Credential")).thenReturn("bad-token");
        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(r -> r.getStatus() == 401));
    }

    @Test
    void expiredToken_returns401() {
        UUID caseId = UUID.randomUUID();
        var cred = new WorkerCredential("tok1", "agent:w1", caseId,
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().minusSeconds(10), Instant.now().minusSeconds(3600));
        credentialStore.store(cred);

        when(requestContext.getHeaderString("X-Worker-Credential")).thenReturn("tok1");
        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(r -> r.getStatus() == 401));
    }

    @Test
    void tokenForDifferentCase_returns403() {
        UUID caseA = UUID.randomUUID();
        UUID caseB = UUID.randomUUID();
        var cred = new WorkerCredential("tok1", "agent:w1", caseA,
            Set.of(WorkerAction.READ_CONTEXT),
            Instant.now().plusSeconds(3600), Instant.now());
        credentialStore.store(cred);

        when(requestContext.getHeaderString("X-Worker-Credential")).thenReturn("tok1");
        var uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("cases/" + caseB + "/context");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        filter.filter(requestContext);
        verify(requestContext).abortWith(argThat(r -> r.getStatus() == 403));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl rest -Dtest=WorkerCredentialFilterTest -q`
Expected: FAIL — class not found

- [ ] **Step 3: Implement WorkerCredentialFilter**

Create `rest/src/main/java/io/casehub/engine/rest/filter/WorkerCredentialFilter.java`:
```java
package io.casehub.engine.rest.filter;

import io.casehub.engine.common.acl.WorkerCredentialStore;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class WorkerCredentialFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(WorkerCredentialFilter.class);
    private static final String HEADER = "X-Worker-Credential";
    private static final Pattern CASE_ID_PATTERN = Pattern.compile("cases/([0-9a-f-]{36})");

    private final WorkerCredentialStore credentialStore;

    @Inject
    public WorkerCredentialFilter(WorkerCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String token = ctx.getHeaderString(HEADER);
        if (token == null) {
            return;
        }

        var credential = credentialStore.lookup(token);
        if (credential.isEmpty()) {
            ctx.abortWith(Response.status(401).entity("Invalid worker credential").build());
            return;
        }

        var cred = credential.get();
        if (cred.isExpired()) {
            ctx.abortWith(Response.status(401).entity("Worker credential expired").build());
            return;
        }

        String path = ctx.getUriInfo().getPath();
        Matcher matcher = CASE_ID_PATTERN.matcher(path);
        if (matcher.find()) {
            UUID requestCaseId = UUID.fromString(matcher.group(1));
            if (!cred.caseId().equals(requestCaseId)) {
                LOG.warnf("Worker credential scope violation: token case=%s request case=%s",
                    cred.caseId(), requestCaseId);
                ctx.abortWith(Response.status(403).entity("Credential not scoped for this case").build());
                return;
            }
        }

        ctx.setProperty("workerCredential.actorId", cred.actorId());
        ctx.setProperty("workerCredential.caseId", cred.caseId());
    }
}
```

Note: The filter sets request properties; a `WorkerCredentialPrincipal` adapter (implementing `CurrentPrincipal`) is wired as a request-scoped CDI bean that reads these properties. Implementation detail resolved during Step 3.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl rest -Dtest=WorkerCredentialFilterTest -q`
Expected: PASS

- [ ] **Step 5: Run full rest module tests**

Run: `mvn test -pl rest -q`
Expected: PASS — all existing REST tests + new filter tests

- [ ] **Step 6: Commit**

```bash
git add rest/src/main/java/io/casehub/engine/rest/filter/ rest/src/test/java/io/casehub/engine/rest/filter/
git commit -m "feat(#221): add WorkerCredentialFilter for REST structural isolation

Refs #833"
```

---

### Task 6: Handler integration — grant at dispatch, revoke at completion

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/handler/CaseContextChangedEventHandler.java` — call `grantAndMint()` at dispatch, pass token to `WorkerScheduleEvent` and `ProvisionContext`
- Modify: `api/src/main/java/io/casehub/api/model/ProvisionContext.java` — add `workerCredentialToken` field
- Test: `runtime/src/test/java/io/casehub/engine/internal/acl/WorkerGrantDispatchIntegrationTest.java`

**Interfaces:**
- Consumes: `WorkerGrantOrchestrator.grantAndMint()` from Task 3; `Binding.getPermissionIntent()` from Task 2; `CaseDefinition.getWorkerServiceAccountId()` from Task 2
- Produces: Credential token threaded into dispatch events and ProvisionContext

- [ ] **Step 1: Add workerCredentialToken to ProvisionContext**

`ProvisionContext` is a record — add the new field as the last parameter. Update all construction sites to pass `null`.

```java
public record ProvisionContext(
    UUID caseId,
    String tenancyId,
    String taskType,
    WorkerContext workerContext,
    PropagationContext propagationContext,
    String triggerChannelId,
    String triggerCorrelationId,
    String workerCredentialToken) {}
```

- [ ] **Step 2: Wire grantAndMint into CaseContextChangedEventHandler.publishWorkerSchedule()**

Inject `WorkerGrantOrchestrator` and `CaseDefinitionRegistry` (already injected). Before creating `WorkerScheduleEvent`, check if the binding needs grants:

```java
String credentialToken = null;
String serviceAccountId = definition.getWorkerServiceAccountId(worker.name());
boolean needsGrants = serviceAccountId != null || binding.getPermissionIntent() != null;

if (needsGrants) {
    var actions = binding.getPermissionIntent() != null
        ? binding.getPermissionIntent()
        : List.of(WorkerAction.READ_CONTEXT);
    Instant deadline = caseInstance.getPropagationContext()
        .getDeadline().orElse(null);
    var credential = orchestrator.grantAndMint(
        serviceAccountId, actions, caseInstance.getUuid(),
        caseInstance.getTenancyId(), deadline);
    credentialToken = credential.token();
}
```

Pass `credentialToken` to the `WorkerScheduleEvent` constructor.

- [ ] **Step 3: Wire grantAndMint into CaseContextChangedEventHandler.tryProvision()**

Same pattern — before creating `ProvisionContext`, mint if needed. Pass token to `ProvisionContext.workerCredentialToken`.

- [ ] **Step 4: Write integration test**

A `@QuarkusTest` that defines a CaseHub with an external worker (serviceAccountId set) and permissionIntent, starts a case, verifies a credential was created in the store and ACL grants were made.

- [ ] **Step 5: Run runtime tests**

Run: `mvn test -pl runtime -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/casehub/api/model/ProvisionContext.java runtime/src/main/java/ runtime/src/test/java/
git commit -m "feat(#221): wire grant orchestration into dispatch and provisioning handlers

Refs #833"
```

---

### Task 7: Recovery and cleanup

**Files:**
- Modify: `runtime/src/main/java/io/casehub/engine/internal/engine/recovery/WorkerRecoveryCoordinator.java` — revoke credentials for in-flight workers on restart
- Test: `runtime/src/test/java/io/casehub/engine/internal/acl/WorkerCredentialRecoveryTest.java`

**Interfaces:**
- Consumes: `WorkerGrantOrchestrator.revokeForWorker()` from Task 3; EventLog metadata from Task 4

- [ ] **Step 1: Add credential revocation to WorkerRecoveryCoordinator**

In the recovery path where in-flight workers are detected from EventLog entries, read `workerCredentialToken` from metadata and call `orchestrator.revokeForWorker()` if present.

- [ ] **Step 2: Write test**

Unit test mocking the EventLog repository with metadata containing a `workerCredentialToken`, verifying the orchestrator is called.

- [ ] **Step 3: Run tests**

Run: `mvn test -pl runtime -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add runtime/src/main/java/ runtime/src/test/java/
git commit -m "feat(#221): add credential cleanup to worker recovery coordinator

Refs #833"
```

---

### Task 8: Full integration test — end-to-end credential lifecycle

**Files:**
- Test: `runtime/src/test/java/io/casehub/engine/WorkerRightsIntegrationTest.java`

**Interfaces:**
- Consumes: All previous tasks

- [ ] **Step 1: Write end-to-end integration test**

A `@QuarkusTest` that:
1. Defines a CaseHub with a worker that has `serviceAccountId` and `permissionIntent`
2. Starts a case — verifies credential created, ACL grants present
3. Completes the worker — verifies credential revoked, ACL grants removed
4. Starts another case with the same definition, completes to terminal — verifies case-terminal sweep

- [ ] **Step 2: Run the test**

Run: `mvn test -pl runtime -Dtest=WorkerRightsIntegrationTest -q`
Expected: PASS

- [ ] **Step 3: Run the full build**

Run: `mvn clean test -q`
Expected: PASS — all modules

- [ ] **Step 4: Commit**

```bash
git add runtime/src/test/java/io/casehub/engine/WorkerRightsIntegrationTest.java
git commit -m "test(#221): end-to-end worker rights integration test

Refs #833"
```
