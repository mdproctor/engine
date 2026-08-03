---
title: "Worker Rights: When Your Agents Call Back"
date: 2026-08-03
type: diary
tags: [acl, authorization, workers, agents, security, case-management]
---

An in-process worker is easy. The engine hands it projected data, it returns a result, and it never sees anything else. The sandbox is architectural — `inputSchema` and `outputSchema` are the boundaries, and there's nothing the worker can do about it.

External workers are a different problem entirely. A Claudony agent provisioned to assess loan risk doesn't run inside your JVM. It runs somewhere else, does its work, and calls back — via REST, via Qhorus channels, via whatever path gets the result home. The moment it calls back, it's an API caller. And API callers need identity, permissions, and isolation.

We'd already solved the easy half. [Batch 2](2026-08-03-acl-enforcement-where-it-belongs.md) moved ACL enforcement into the engine's service layer, so every REST endpoint checks `case:<caseId>` grants through `AccessControlProvider`. Human users authenticate via OAuth, get their group memberships, and the grants from `CaseDefinition.authorization` do the rest.

But workers aren't humans. They don't log in via OAuth. They don't have group memberships. And they absolutely should not get blanket access to every case in the system just because they're "the risk assessment agent."

## The three isolation levels

The design landed on three distinct levels, each for a different trust boundary:

**Trusted internal** — in-process workers sandboxed by the data contract. Credit-check lambdas, AI agents running inside the JVM. No ACL needed, no credentials issued.

**Ephemeral external** — workers dispatched without a pre-existing identity. The engine mints a unique `agent:worker-<caseId>-<uuid>` identity at dispatch time, creates ACL grants scoped to that specific case, and issues a credential token. The identity is disposable — revoked when the worker completes.

**Service-account external** — workers with a stable identity declared in the case definition. A pool of risk assessment agents that share `agent:risk-pool@lending.io` across cases. The engine grants case-scoped ACL for this identity at dispatch and revokes on completion. When two bindings share the same service account on the same case, differential revocation ensures revoking one doesn't break the other.

## Permission intent — declaring what workers need

Workers declare their intentions, not their ACL entries:

```yaml
bindings:
  - name: assess-risk
    capability: risk-assessment
    worker: risk-assessment-agent
    permissionIntent:
      - read-context
      - signal-case
      - read-event-log
```

The engine maps these to ACL grants at dispatch time. `read-context` → READ on CASE. `signal-case` → WRITE on CASE. The intent vocabulary is deliberately finer-grained than the current enforcement — `read-event-log` and `read-context` both resolve to READ today, but the semantic distinction matters for audit trails and for future per-resource-type enforcement.

The default is fail-closed. Omit `permissionIntent` on an external binding and the worker gets `[read-context]` — read only. Write access requires explicit declaration.

## Structural isolation — the scoped token

ACL grants control what a worker *is allowed* to do. The scoped token controls what it *can even attempt*. Every credential is bound to a specific `caseId`:

```
Worker calls: GET /cases/{caseB}/context
Token scope:  caseId = caseA
Result:       403 — before ACL is even checked
```

The `WorkerCredentialFilter` sits in front of ACL enforcement. It validates the `X-Worker-Credential` header, checks the token isn't expired, and verifies the request targets the token's scoped case. A worker with valid credentials for case A physically cannot reference case B through the REST API. The ACL check is belt; the scoped token is suspenders.

## The lifecycle

The credential lifecycle is fully managed — no manual grant/revoke:

1. **Dispatch** — `WorkerGrantOrchestrator.grantAndMint()` resolves identity, creates ACL grants with TTL, mints an opaque token, stores it in `WorkerCredentialStore`
2. **Execution** — worker receives the token via COMMAND payload, presents it on REST callbacks
3. **Completion** — `WorkflowExecutionCompletedHandler` calls `revokeForWorker()` on any outcome (success, failure, decline, expiry)
4. **Terminal sweep** — `CaseStatusChangedHandler` calls `revokeForCase()` when the case completes, catching any stragglers

The `InMemoryWorkerCredentialStore` is the default — works out of the box for single-node deployments. Clustered deployments provide a persistent implementation. The grants in `AccessControlProvider` carry the same TTL as the credential, so even if revocation fails, the grants expire.

## What this means for case definition authors

If your workers are in-process lambdas or AI agents — nothing changes. The sandbox works exactly as before.

If your workers are external (Claudony agents, provisioned services, anything that calls back via REST) — you now declare what they need in the case definition. The engine handles identity, credentials, and cleanup automatically.

The full example is in [`schema/examples/worker-rights-example.yaml`](https://github.com/casehubio/engine/blob/main/schema/src/main/resources/examples/worker-rights-example.yaml) — a loan approval case showing all three isolation levels with inline comments explaining every concept.
