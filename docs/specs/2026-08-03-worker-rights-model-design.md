# Worker Rights Model and Authorization Service — Design Spec

**Issue:** casehubio/platform#221
**Epic:** casehubio/engine#833 (Batch 3)
**Date:** 2026-08-03
**Status:** Reviewed (light)
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
| Rights scope | Case-scoped, action-declared | All actions map to CASE resource type for enforcement. Intent vocabulary is finer-grained for audit. Per-resource-type enforcement deferred until REST layer supports it |
| Declaration model | Action-based intent | Workers declare actions; engine maps to ACL grants. Decouples workers from ACL internals |
| Identity | Dual | Engine mints ephemeral identity OR accepts pre-existing service-account |
| Approval | Auto-grant from definition | The definition IS the authorization. Offline approval deferred |
| Isolation | Structural via scoped tokens | Case-bound credentials — token physically can't reference another case |
| Revocation | Event-driven + time expiry | Immediate revocation on completion; expiry as safety net; case terminal sweep |
| SPI location | engine-common | Engine is the only consumer; platform-api has no use for it. Follows SPI placement rule |

## 3. Permission Intent Model

### 3.1 WorkerAction

`WorkerAction` (enum, `io.casehub.engine.common.acl`) — named actions that workers declare.
Each action maps to concrete ACL grants via `toAclGrants()`.

**Enforcement granularity (design review finding):** All actions currently map to `CASE`
resource type because `CaseService.requireCaseAccess()` only checks `case:<caseId>`. The
intent vocabulary is deliberately finer-grained than enforcement — it serves audit trails
and future per-resource-type enforcement when the REST layer supports it. Multiple WRITE
actions (`WRITE_CONTEXT`, `SIGNAL_CASE`, `SPAWN_SUB_CASE`) collapse to the same ACL grant
after deduplication; this is expected, not a bug.

| Action | AclAction | AclResourceType | Semantic intent |
|--------|-----------|-----------------|-----------------|
| `READ_CONTEXT` | READ | CASE | Read case context |
| `WRITE_CONTEXT` | WRITE | CASE | Modify case context directly |
| `SIGNAL_CASE` | WRITE | CASE | Signal the case with results |
| `READ_EVENT_LOG` | READ | CASE | Read event log (audit: distinct from read-context) |
| `READ_PLAN_ITEMS` | READ | CASE | Read plan items (audit: distinct from read-context) |
| `SPAWN_SUB_CASE` | WRITE | CASE | Spawn sub-cases (audit: distinct from write-context) |
| `ADMIN` | ADMIN | CASE | Administrative operations |

```java
public enum WorkerAction {
    READ_CONTEXT(AclAction.READ),
    WRITE_CONTEXT(AclAction.WRITE),
    SIGNAL_CASE(AclAction.WRITE),
    READ_EVENT_LOG(AclAction.READ),
    READ_PLAN_ITEMS(AclAction.READ),
    SPAWN_SUB_CASE(AclAction.WRITE),
    ADMIN(AclAction.ADMIN);

    private final AclAction aclAction;

    public AclGrant toAclGrant() {
        return new AclGrant(aclAction, AclResourceType.CASE);
    }
}
```

`AclGrant` — record `(AclAction action, String resourceType)` in `engine-common`. Note:
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

**Default (design review finding — fail-closed for writes):** If no `permissionIntent` is
declared on a binding that dispatches an external worker, the engine applies `[READ_CONTEXT]`
only — read access, no write. WRITE actions (`SIGNAL_CASE`, `WRITE_CONTEXT`, `SPAWN_SUB_CASE`)
must be explicitly declared. This is fail-closed: a binding author who forgets `permissionIntent`
gets read-only access, not silent WRITE grants. Workers without `serviceAccountId` (and not
provisioner-dispatched) get no grants — they are in-process and don't need ACL.

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

**Format validation (design review finding):** `serviceAccountId` must resolve to
`ActorType.AGENT` via `ActorTypeResolver`. In practice this means it must start with `agent:`
or match the `[\w-]+:[\w-]+@[\w.]+` pattern. Values that resolve to `HUMAN` or `SYSTEM` are
rejected at definition build time (`CaseDefinition.Builder.build()`) with
`IllegalArgumentException`. This prevents identity impersonation — a case definition cannot
claim a human or system identity for a worker.

**Module placement:** `serviceAccountId` lives on the engine-api `Worker` (the CaseDefinition
schema POJO), NOT on the `casehub-worker-api` Worker record. The worker-api Worker is a
published foundation-tier artifact — adding fields to it would extend the impersonation
surface to all worker-api consumers. The engine-api Worker is internal to the engine's
definition schema.

## 5. Scoped Token & Credential Store

### 5.1 WorkerCredential

Record (`io.casehub.engine.common.acl`):

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

SPI (`io.casehub.engine.common.acl`):

```java
public interface WorkerCredentialStore {
    void store(WorkerCredential credential);
    Optional<WorkerCredential> lookup(String token);
    void revoke(String token);
    List<WorkerCredential> revokeByCase(UUID caseId);
    List<WorkerCredential> revokeByActor(String actorId);
}
```

- `InMemoryWorkerCredentialStore` is the `@DefaultBean @ApplicationScoped` implementation —
  makes the feature functional out of the box for single-node deployments (design review
  finding: no-op default makes the feature non-functional, contradicting the epic's "initial
  implementation complete" definition of done)
- Persistent implementations (PostgreSQL) are consumer-provided for clustered deployments
  where in-memory state doesn't survive restarts
- **Durability mismatch note:** `AccessControlProvider` grants persist to a database;
  `InMemoryWorkerCredentialStore` credentials live in memory. On restart, grants survive but
  credentials are lost. The case-terminal sweep (`revokeForCase`) is the safety net — it
  revokes all grants for the case regardless of credential state. For long-lived cases, orphaned
  grants persist until case completion. Clustered deployments MUST provide a persistent store

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

**Token delivery (to workers):**

| Worker type | Delivery path |
|-------------|---------------|
| Provisioner-dispatched | `ProvisionContext.workerCredentialToken` (new nullable String field) → provisioner includes in COMMAND payload |
| Quartz-dispatched (external) | `WorkerScheduleEvent.workerCredentialToken` → EventLog metadata → delivered to external worker via channel COMMAND |
| Qhorus channel | Included in COMMAND payload JSON as `credentialToken` field |

**Design review finding:** The credential token is NOT added to `WorkerContext`. Workers
receive the token via their delivery channel (ProvisionContext or COMMAND payload) and store
it themselves for REST callbacks. Mixing a security credential into operational context
creates unnecessary exposure.

**Token threading for revocation (back to engine):**

The token must reach `WorkflowExecutionCompletedHandler` for revocation. Threading path:

```
WorkerScheduleEvent.workerCredentialToken
  → WorkerScheduleEventHandler writes to EventLog metadata as "workerCredentialToken"
  → QuartzWorkerExecutionJob reads from EventLog metadata
  → WorkflowExecutionCompleted.workerCredentialToken (new nullable String field)
  → WorkflowExecutionCompletedHandler calls revokeForWorker(token, ...)
```

`WorkflowExecutionCompleted` gains `workerCredentialToken` (nullable String). This is the
typed contract for credential threading — no untyped metadata handoff at the revocation
boundary.

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

**Shared service account safety (design review finding):** When a service-account identity
has multiple concurrent bindings on the same case, revoking one binding's grants must not
break the other. The orchestrator uses per-credential grant tracking:

1. `credentialStore.revoke(token)` — invalidates this credential immediately
2. Query remaining active credentials for the same `(actorId, caseId)`:
   `credentialStore.findActiveByActorAndCase(actorId, caseId)`
3. Compute grants still needed: union of `actions` across remaining credentials
4. Compute grants to revoke: this credential's grants MINUS still-needed grants
5. `accessControlProvider.revokeBatch(grantDiff)` — only revoke grants no other credential needs

This requires an additional method on `WorkerCredentialStore`:

```java
List<WorkerCredential> findActiveByActorAndCase(String actorId, UUID caseId);
```

For ephemeral identities (unique per provisioning), this check is a no-op — there is only
ever one credential per actorId. The reference-counting path only activates for service
accounts with multiple concurrent bindings.

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

| Type | Field | Type | Nullable | Direction |
|------|-------|------|----------|-----------|
| `Worker` | `serviceAccountId` | String | Yes | Declaration |
| `Binding` | `permissionIntent` | `List<WorkerAction>` | Yes | Declaration |
| `ProvisionContext` | `workerCredentialToken` | String | Yes | Outbound (to worker) |
| `WorkerScheduleEvent` | `workerCredentialToken` | String | Yes | Outbound (to handler) |
| `WorkflowExecutionCompleted` | `workerCredentialToken` | String | Yes | Inbound (for revocation) |

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

- `WorkerAction` enum in `engine-common`
- `AclGrant` record in `engine-common`
- `WorkerCredential` record in `engine-common`
- `WorkerCredentialStore` SPI in `engine-common` + `InMemoryWorkerCredentialStore` as `@DefaultBean`
- `WorkerIdentityResolver` in engine runtime
- `WorkerGrantOrchestrator` in engine runtime
- `WorkerCredentialFilter` JAX-RS filter in engine rest
- `permissionIntent` on `Binding`, `serviceAccountId` on `Worker`
- Threading through `ProvisionContext`, `WorkerScheduleEvent`, `WorkflowExecutionCompleted`
- Grant/revoke wiring in handlers
- YAML support for `permissionIntent` and `serviceAccountId`
- `WorkerRecoveryCoordinator` integration for in-flight credential cleanup

### Not In Scope

- Persistent `WorkerCredentialStore` implementation (PostgreSQL) — consumer-provided
- Offline approval workflow — deferred; the definition IS the approval
- Cross-case worker access — separate design
- Token refresh/rotation — short-lived tokens with case-terminal sweep sufficient for v1
- Qhorus channel-level authorization — orthogonal concern

## 11. Design Review Findings

Light review (coherence + structure + robustness + cross-cutting) surfaced 37 issues across
4 dimensions. After deduplication and cross-cutting synthesis, 5 themes required spec changes:

| # | Theme | Resolution |
|---|-------|------------|
| 1 | Permission granularity mismatch — fine-grained intents vs case-level enforcement | All actions map to CASE resource type. Intent vocabulary retained for audit |
| 2 | Credential store no-op default makes feature non-functional | `InMemoryWorkerCredentialStore` promoted to `@DefaultBean`. SPI moved to engine-common |
| 3 | Shared service account concurrent revocation race | Per-credential grant tracking with differential revocation |
| 4 | Token threading incomplete — doesn't reach completion handler | Token threaded via `WorkflowExecutionCompleted` record. Removed from `WorkerContext` |
| 5 | Fail-open default grants WRITE silently | Default changed to `[READ_CONTEXT]` only. `serviceAccountId` validated against `ActorTypeResolver` |

Review workspaces: `~/reviews/casehub-worktrees/worker-rights-model-{coherence,structure,robustness,crosscutting}-20260803-*`
