# Design: Move TrustRoutingPolicy + TrustRoutingPolicyProvider to casehub-engine-api

**Issue:** casehubio/engine#382  
**Branch:** issue-382-sxs-batch  
**Date:** 2026-05-29  

---

## Problem

`TrustRoutingPolicy` (record) and `TrustRoutingPolicyProvider` (interface) live in
`casehub-engine-ledger` under package `io.casehub.ledger.routing`. Both are
deployment-facing SPIs — application code overrides `TrustRoutingPolicyProvider` with
`@ApplicationScoped @Alternative @Priority(1)` to supply per-capability trust policies.

To implement the SPI, applications must add `casehub-engine-ledger` as a compile
dependency. This pulls in `TrustWeightedAgentStrategy`, `TrustScoreCache`, and the full
`casehub-ledger` transitive graph. A consumer that only wants to configure routing policy
should not require the ledger runtime.

Discovered while implementing casehub-aml Layer 6: aml needs
`AmlTrustRoutingPolicyProvider` but should not depend on `casehub-engine-ledger`.

---

## Design

### Target placement

Both types move to `casehub-engine-api`, package `io.casehub.api.spi.routing` — the
existing package for all routing SPI types (`AgentRoutingStrategy`, `AgentCandidate`,
`AgentRoutingContext`, `AgentHealth`, `AgentAssignment`).

| Type | From | To |
|------|------|----|
| `TrustRoutingPolicy` | `io.casehub.ledger.routing` | `io.casehub.api.spi.routing` |
| `TrustRoutingPolicyProvider` | `io.casehub.ledger.routing` | `io.casehub.api.spi.routing` |

### What stays in `casehub-engine-ledger`

| Type | Reason |
|------|--------|
| `DefaultTrustRoutingPolicyProvider` | CDI: `@DefaultBean @ApplicationScoped` — requires Quarkus ARC |
| `TrustWeightedAgentStrategy` | CDI + ledger runtime (`TrustScoreCache`, `casehub-ledger`) |
| `TrustScoreCache` | ledger runtime |
| `TrustCandidateClassifier` | CDI: `@ApplicationScoped` |

### Dependency impact

- `casehub-engine-api/pom.xml`: no new dependencies — `TrustRoutingPolicy` uses only
  `java.util.Map`; `TrustRoutingPolicyProvider` references only `TrustRoutingPolicy`.
- `casehub-engine-ledger/pom.xml`: no change — already depends on `casehub-engine-api`.
- No circular dependency risk: `casehub-engine-api` has no ledger deps.

### Files to update (imports + package declarations)

1. `ledger/.../DefaultTrustRoutingPolicyProvider.java` — update import
2. `ledger/.../TrustCandidateClassifier.java` — update import
3. `ledger/.../TrustWeightedAgentStrategy.java` — update import
4. `ledger/src/test/.../TrustRoutingPolicyTest.java` — update import
5. `ledger/src/test/.../TrustCandidateClassifierTest.java` — update import
6. `engine-ai/.../SemanticAgentRoutingStrategy.java` — update import

### Consumer migration

Applications implementing `TrustRoutingPolicyProvider` update their import from
`io.casehub.ledger.routing.TrustRoutingPolicyProvider` to
`io.casehub.api.spi.routing.TrustRoutingPolicyProvider`. They drop `casehub-engine-ledger`
from their compile dependencies if it was only there for the SPI.

---

## Protocols satisfied

- **Three-tier module structure**: SPI interfaces and their supporting data types belong in
  the pure-Java API tier (`casehub-engine-api`). CDI implementations stay in the runtime
  tier (`casehub-engine-ledger`). ✅
- **platform-api-scope**: `TrustRoutingPolicy` is an engine-domain type, not
  platform-primitive — correct to place in `casehub-engine-api`, not `casehub-platform-api`. ✅
- **engine-spi-noops-defaultbean**: `DefaultTrustRoutingPolicyProvider` keeps
  `@DefaultBean @ApplicationScoped` in `casehub-engine-ledger`. ✅

---

## Testing

- All existing ledger unit tests continue to pass (no logic changes — package rename only).
- `TrustRoutingPolicyTest` moves with the type to remain co-located.
- No new tests needed: the move is mechanical; the behaviour is unchanged.
