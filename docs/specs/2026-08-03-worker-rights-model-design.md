# Worker Rights Model and Authorization Service — Design Spec

**Issue:** casehubio/platform#221
**Epic:** casehubio/engine#833 (Batch 3)
**Date:** 2026-08-03
**Status:** Draft
**Depends on:** Batch 1 (identity propagation, platform#220), Batch 2 (REST enforcement, engine#768)

## 1. Problem Statement

External workers with privileged access (service-account identity, elevated grants) need a
rights model that controls what they can do on a case. The engine currently has no mechanism to:

1. Create and manage worker-specific identities
2. Grant case-scoped ACL entries at provisioning time
3. Structurally isolate workers to their assigned case
4. Revoke grants on worker completion or case termination
5. Declare needed permissions in the case definition

In-process workers are architecturally sandboxed by `inputSchema`/`outputSchema` and
`WorkerRuntime` — they don't need ACL. Identity-inheriting external workers are covered by
Batch 2 REST enforcement. This spec addresses the third isolation level: **privileged external
workers** with their own service-account identity and elevated grants.

## 2. Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Interaction path | Agnostic | Rights model declares what workers CAN do; enforcement is per-path (REST, channel, internal) |
| Rights scope | Layered | Case-scoped base + resource-type granularity + action-based declaration |
| Declaration model | Action-based intent | Workers declare actions; engine maps to ACL grants. Decouples workers from ACL internals |
| Identity | Dual | Engine mints ephemeral identity OR accepts pre-existing service-account |
| Approval | Auto-grant from definition | The definition IS the authorization. Offline approval deferred |
| Isolation | Structural via scoped tokens | Case-bound credentials — token physically can't reference another case |
| Revocation | Event-driven + time expiry | Immediate revocation on completion; expiry as safety net; case terminal sweep |
| SPI location | platform-api | Worker authorization is a platform concern, not engine-specific |

## 3. Permission Intent Model

### 3.1 WorkerAction

`WorkerAction` (enum, `io.casehub.platform.api.acl`) — named actions that workers declare.
Each action maps to concrete ACL grants via `toAclGrants()`.

| Action | AclAction | AclResourceType |
|--------|-----------|-----------------|
| `READ_CONTEXT` | READ | CASE |
| `WRITE_CONTEXT` | WRITE | CASE |
| `SIGNAL_CASE` | WRITE | CASE |
| `READ_EVENT_LOG` | READ | EVENT_LOG |
| `READ_PLAN_ITEMS` | READ | PLAN_ITEM |
| `SPAWN_SUB_CASE` | WRITE | CASE |
| `ADMIN` | ADMIN | CASE |

```java
public enum WorkerAction {
    READ_CONTEXT(List.of(new AclGrant(AclAction.READ, AclResourceType.CASE))),
    WRITE_CONTEXT(List.of(new AclGrant(AclAction.WRITE, AclResourceType.CASE))),
    SIGNAL_CASE(List.of(new AclGrant(AclAction.WRITE, AclResourceType.CASE))),
    READ_EVENT_LOG(List.of(new AclGrant(AclAction.READ, AclResourceType.EVENT_LOG))),
    READ_PLAN_ITEMS(List.of(new AclGrant(AclAction.READ, AclResourceType.PLAN_ITEM))),
    SPAWN_SUB_CASE(List.of(new AclGrant(AclAction.WRITE, AclResourceType.CASE))),
    ADMIN(List.of(new AclGrant(AclAction.ADMIN, AclResourceType.CASE)));

    private final List<AclGrant> grants;
    // constructor, toAclGrants()
}
```

`AclGrant` — record `(AclAction action, String resourceType)` in `platform-api`. Note:
`AclResourceType` is a constants class with `String` fields, not an enum — the record field
type is `String`.

### 3.2 Declaration Surface

**On `Binding`** — `permissionIntent` (`List<WorkerAction>`, nullable):

```yaml
bindings:
  - name: risk-assessment
    capability: assess-risk
    worker: claudony-risk-agent
    permissionIntent:
      - read-context
      - signal-case
      - read-event-log
```

Java DSL:
```java
.binding("risk-assessment")
    .capability("assess-risk")
    .worker("claudony-risk-agent")
    .permissionIntent(WorkerAction.READ_CONTEXT, WorkerAction.SIGNAL_CASE, WorkerAction.READ_EVENT_LOG)
```

**Default:** If no `permissionIntent` is declared on a binding that dispatches a worker with
`serviceAccountId` set or via the provisioner path, the engine applies
`[READ_CONTEXT, SIGNAL_CASE]` — the minimum viable set. Workers without `serviceAccountId`
(and not provisioner-dispatched) get no grants — they are in-process and don't need ACL.

**External worker detection:** A worker needs grants when either (a) it has a
`serviceAccountId` declared, or (b) it is dispatched via `WorkerProvisioner.provision()`.
There is no `isExternal()` method — the grant orchestrator checks these two conditions.

## 4. Worker Identity Model

### 4.1 Identity Modes

**Mode 1 — Engine-minted ephemeral identity (default):**

Format: `agent:worker-<caseId>-<shortUuid>` (e.g., `agent:worker-a1b2c3d4-7f3e`).

Unique per provisioning. The `caseId` prefix is informational (logging/debugging), not a
security boundary — the scoped token provides structural isolation. `ActorTypeResolver`
already recognizes the `agent:` prefix as `ActorType.AGENT`.

**Mode 2 — Pre-declared service-account identity:**

Workers with a stable identity declare it on the `Worker` definition:

```yaml
workers:
  - name: claudony-risk-agent
    serviceAccountId: "agent:claudony-pool-risk@acme.io"
    capabilities:
      - assess-risk
```

Java DSL:
```java
Worker.builder()
    .name("claudony-risk-agent")
    .serviceAccountId("agent:claudony-pool-risk@acme.io")
    .capabilityName("assess-risk")
```

### 4.2 WorkerIdentityResolver

`WorkerIdentityResolver` (`runtime/internal/identity/`, `@ApplicationScoped`) — resolves the
actorId for a worker at dispatch time.

```java
public record WorkerIdentity(String actorId, boolean ephemeral) {}
```

Logic: if `worker.getServiceAccountId() != null`, use it with `ephemeral=false`. Otherwise,
mint `agent:worker-<caseId>-<shortUuid>` with `ephemeral=true`.

The `ephemeral` flag determines cleanup behavior:
- Ephemeral: all grants fully revoked on completion
- Service-account: only case-scoped grants revoked, identity persists

### 4.3 New Field on Worker

`Worker` gains `serviceAccountId` (nullable String). Builder: `.serviceAccountId(String)`.
YAML: `serviceAccountId:` on worker definitions. `CaseDefinitionYamlMapper` parses it.

## 5. Scoped Token & Credential Store

### 5.1 WorkerCredential

Record (`io.casehub.platform.api.acl`):

```java
public record WorkerCredential(
    String token,               // opaque, 32 bytes hex-encoded via SecureRandom
    String actorId,             // worker identity
    UUID caseId,                // structural scope — token can ONLY reference this case
    Set<WorkerAction> actions,  // allowed actions
    Instant expiresAt,          // time expiry safety net
    Instant createdAt
) {}
```

Token generation: `SecureRandom` → 32 bytes → hex. No signatures — the token is looked up,
not decoded.

### 5.2 WorkerCredentialStore

SPI (`io.casehub.platform.api.acl`):

```java
public interface WorkerCredentialStore {
    void store(WorkerCredential credential);
    Optional<WorkerCredential> lookup(String token);
    void revoke(String token);
    List<WorkerCredential> revokeByCase(UUID caseId);
    List<WorkerCredential> revokeByActor(String actorId);
}
```

- `@DefaultBean` no-op in platform (returns empty optionals)
- `InMemoryWorkerCredentialStore` for tests (in `casehub-platform` or test module)
- Persistent implementation is consumer-provided (not in scope for this issue)

### 5.3 Token Lifecycle

| Event | Action |
|-------|--------|
| Worker dispatched | `store(credential)` — expiry from worker timeout or PropagationContext deadline (shorter wins) |
| REST request with token | `lookup(token)` → validate expiry, extract actorId + caseId, verify request targets scoped caseId |
| Worker completes | `revoke(token)` |
| Case terminal state | `revokeByCase(caseId)` — sweep all surviving credentials |

### 5.4 WorkerCredentialFilter (REST)

JAX-RS `ContainerRequestFilter` (`engine-rest`, priority before ACL enforcement). Checks for
the `X-Worker-Credential` header containing the opaque token.

On match:
1. `credentialStore.lookup(token)` — 401 if not found
2. Validate `expiresAt` — 401 if expired
3. Extract `caseId` from request path, compare to credential's `caseId` — **403 if mismatch**
   (structural isolation, before ACL check)
4. Set request-scoped `CurrentPrincipal` to the credential's `actorId`
5. Normal ACL enforcement proceeds via `CaseService.requireCaseAccess()`

On no match (no `X-Worker-Credential` header): pass through — normal auth flow.

**Header convention:** Workers present their credential via the `X-Worker-Credential` header
(not the `Authorization` header) to avoid interfering with existing auth mechanisms. This is a
custom header — no collision with OAuth/JWT/Bearer token flows.

### 5.5 Token Delivery

The credential token is threaded to workers via:

| Worker type | Delivery path |
|-------------|---------------|
| Provisioner-dispatched | `ProvisionContext.workerCredentialToken` (new nullable String field) |
| Quartz-dispatched | `WorkerScheduleEvent.workerCredentialToken` → EventLog metadata → `QuartzWorkerExecutionJob` → `WorkerContext.credentialToken` |
| Qhorus channel | Included in COMMAND payload JSON as `credentialToken` field |

Workers access the token via `((WorkerRuntime) scope).context().credentialToken()`.

## 6. Grant Orchestration

### 6.1 WorkerGrantOrchestrator

`@ApplicationScoped` (`runtime/internal/acl/`):

```java
public class WorkerGrantOrchestrator {
    @Inject AccessControlProvider accessControlProvider;
    @Inject WorkerCredentialStore credentialStore;
    @Inject WorkerIdentityResolver identityResolver;

    public WorkerCredential grantAndMint(Worker worker, Binding binding,
                                         UUID caseId, String tenancyId, Instant deadline);
    public void revokeForWorker(String token, String actorId, UUID caseId, boolean ephemeral);
    public void revokeForCase(UUID caseId);
}
```

### 6.2 grantAndMint Flow

Called from `CaseContextChangedEventHandler` at dispatch time for external workers:

1. `identityResolver.resolve(worker, caseId)` → `WorkerIdentity(actorId, ephemeral)`
2. `binding.getPermissionIntent()` → actions (or default `[READ_CONTEXT, SIGNAL_CASE]`)
3. Expand: `actions.stream().flatMap(a -> a.toAclGrants().stream())` → deduplicated `AclGrant` set
4. Create ACL entries: `accessControlProvider.grantBatch(requests)` — resource IDs are
   `<resourceType>:<caseId>` (e.g., `case:a1b2c3d4`, `eventlog:a1b2c3d4`)
5. Compute expiry: `min(workerTimeout, propagationContext.deadline, now + 1 hour)` — 1 hour
   hard ceiling as safety net
6. Mint credential: `new WorkerCredential(...)` → `credentialStore.store(credential)`
7. Return credential

### 6.3 revokeForWorker Flow

Called from `WorkflowExecutionCompletedHandler` on all outcomes (success/declined/failed/expired):

1. `credentialStore.revoke(token)`
2. Build revocation requests from the credential's `actions` → ACL grants
3. `accessControlProvider.revokeBatch(requests)`

### 6.4 revokeForCase Flow

Called from `CaseStatusChangedHandler` on terminal state (COMPLETED/FAULTED/CANCELLED):

1. `credentialStore.revokeByCase(caseId)` — returns revoked credentials
2. For each revoked credential: `accessControlProvider.revokeAll(actorId, resourceId)`

### 6.5 Integration Points

| Handler | Method | When |
|---------|--------|------|
| `CaseContextChangedEventHandler.publishWorkerSchedule()` | `grantAndMint()` | External worker dispatched |
| `CaseContextChangedEventHandler.tryProvision()` | `grantAndMint()` | Provisioner-dispatched worker |
| `WorkflowExecutionCompletedHandler` | `revokeForWorker()` | Any worker outcome |
| `CaseStatusChangedHandler` | `revokeForCase()` | Case terminal state |

In-process workers are excluded — `grantAndMint()` is only called when
`binding.getPermissionIntent() != null` or the worker is dispatched via a provisioner.

## 7. Data Flow

### 7.1 New Fields on Existing Types

| Type | Field | Type | Nullable |
|------|-------|------|----------|
| `Worker` | `serviceAccountId` | String | Yes |
| `Binding` | `permissionIntent` | `List<WorkerAction>` | Yes |
| `ProvisionContext` | `workerCredentialToken` | String | Yes |
| `WorkerScheduleEvent` | `workerCredentialToken` | String | Yes |
| `WorkerContext` | `credentialToken` | String | Yes |

### 7.2 EventLog Metadata

`workerCredentialToken` is stored in EventLog metadata at dispatch time (alongside
`bindingName`, `signalId`, etc.). On recovery, `WorkerRecoveryCoordinator` reads the token
and calls `revokeForWorker()` for workers that were in-flight when the engine restarted.

## 8. YAML Schema

### 8.1 Worker serviceAccountId

```yaml
workers:
  - name: claudony-risk-agent
    serviceAccountId: "agent:claudony-pool-risk@acme.io"
    capabilities:
      - assess-risk
    agent:
      # ...
```

### 8.2 Binding permissionIntent

```yaml
bindings:
  - name: risk-assessment
    capability: assess-risk
    worker: claudony-risk-agent
    permissionIntent:
      - read-context
      - signal-case
      - read-event-log
```

YAML values are kebab-case (`read-context`), mapped to enum constants (`READ_CONTEXT`) by
`CaseDefinitionYamlMapper`.

## 9. Testing Strategy

| Layer | Approach |
|-------|----------|
| `WorkerAction` mapping | Unit — each action maps to correct AclAction + AclResourceType |
| `WorkerIdentityResolver` | Unit — ephemeral minting, service-account passthrough |
| `WorkerGrantOrchestrator` | Unit — mock AccessControlProvider + WorkerCredentialStore, verify grant/revoke sequences |
| `WorkerCredentialFilter` | Unit — token lookup, caseId scope enforcement, expiry rejection, missing token passthrough |
| `InMemoryWorkerCredentialStore` | Unit — store/lookup/revoke/revokeByCase lifecycle |
| Dispatch integration | `@QuarkusTest` — CaseHub with external worker + permissionIntent, verify credential created and passed through |
| Revocation integration | `@QuarkusTest` — complete a worker, verify credential revoked and ACL grants removed |
| Case terminal sweep | `@QuarkusTest` — force-complete case with active workers, verify all credentials swept |
| Structural isolation | `@QuarkusTest` — mint credential for case A, attempt REST call to case B, assert 403 |

## 10. Scope Boundaries

### In Scope

- `WorkerAction` enum in `platform-api`
- `AclGrant` record in `platform-api`
- `WorkerCredential` record in `platform-api`
- `WorkerCredentialStore` SPI in `platform-api` + `@DefaultBean` no-op + `InMemoryWorkerCredentialStore`
- `WorkerIdentityResolver` in engine runtime
- `WorkerGrantOrchestrator` in engine runtime
- `WorkerCredentialFilter` JAX-RS filter in engine rest
- `permissionIntent` on `Binding`, `serviceAccountId` on `Worker`
- Threading through `ProvisionContext`, `WorkerScheduleEvent`, `WorkerContext`
- Grant/revoke wiring in handlers
- YAML support for `permissionIntent` and `serviceAccountId`
- `WorkerRecoveryCoordinator` integration for in-flight credential cleanup

### Not In Scope

- Persistent `WorkerCredentialStore` implementation (PostgreSQL) — consumer-provided
- Offline approval workflow — deferred; the definition IS the approval
- Cross-case worker access — separate design
- Token refresh/rotation — short-lived tokens with case-terminal sweep sufficient for v1
- Qhorus channel-level authorization — orthogonal concern
