# ADR-0004: CaseMetaModel Registry Is Global; tenancyId Is a Sentinel

**Status:** Accepted  
**Date:** 2026-06-01  
**Refs:** engine#299, engine#412, engine#410

---

## Context

engine#299 added `tenancyId` to `CaseMetaModel` and `CaseMetaModelRepository.findByKey()`.
This created a tension:

- The **repository** treats CaseMetaModel as per-tenant: `findByKey(ns, name, ver, tenancyId)` scopes
  DB lookups by tenant.
- The **in-memory registry** in `DefaultCaseDefinitionRegistry` treats CaseMetaModel as global:
  `equals()` and `hashCode()` use only `(namespace, name, version)`.

At startup, `registerKnownDefinitions()` calls `currentPrincipal.tenancyId()`, which returns
`TenancyConstants.DEFAULT_TENANT_ID` (the single-tenant sentinel UUID). This means all definitions
are indexed in the repository under the default tenant and in the registry under an object whose
`tenancyId` field is `DEFAULT_TENANT_ID`.

The question: should `CaseMetaModel.equals()`/`hashCode()` include `tenancyId`, making the registry
per-tenant? Or should the registry remain global with `tenancyId` acting as a sentinel?

---

## Decision

**The registry is global. `CaseMetaModel.equals()` and `hashCode()` must not include `tenancyId`.**

The `tenancyId` on `CaseMetaModel` records which tenant _registered_ the definition (for audit and
repository scoping). It is not part of the definition's identity for the purpose of CDI routing or
registry lookup.

The sentinel is `TenancyConstants.DEFAULT_TENANT_ID`. All definitions registered at startup use
this sentinel. The registry `Map<CaseMetaModel, CaseDefinition>` remains keyed on
`(namespace, name, version)` only.

---

## Rationale

**Definitions are code, not data.** Engine case definitions are `CaseHub` subclasses compiled into
the application JAR. No current use case requires tenant A and tenant B to receive _different_
`CaseDefinition` objects for the same type name. Tenant isolation is enforced at the
**case instance level** (`CaseInstance.tenancyId`), not at the definition level.

**Per-tenant registration is operationally unsound.** If `equals()` included `tenancyId`, each
tenant would need a separate registry entry created before that tenant's first case start. There is
no mechanism for lazy per-request registration — `registerKnownDefinitions()` runs once at startup
on a Vert.x context where `currentPrincipal` has no authenticated tenant.

**Global registry is already the implicit invariant.** `DefaultCaseDefinitionRegistry.getCaseMetaModel()`
iterates values using `CaseDefinition.equals()` — which also uses only `(namespace, name, version)`.
The registry was designed to be global; engine#299 added `tenancyId` to the domain object without
revisiting this invariant.

**The repository `tenancyId` column remains correct.** It records which tenant's startup registered
the type. In multi-tenant shared deployments, all tenants share the same definitions registered
under `DEFAULT_TENANT_ID`. This is the right behaviour: definitions are platform vocabulary,
instances are tenant data.

---

## Consequences

- `CaseMetaModel.equals()` and `hashCode()` remain `Objects.hash(namespace, name, version)`. No change.
- `DefaultCaseDefinitionRegistry` remains a single-map global registry. No change.
- Any code that stores a `CaseMetaModel` reference and later uses it as a registry key is
  guaranteed correct as long as `namespace`/`name`/`version` are not mutated after insertion.
- If in future a tenant needs a _custom_ case definition variant, this decision must be revisited.
  The extension point would be a per-tenant `CaseDefinitionRegistry` overlay, not `equals()` mutation.
- engine#410 (registry lookup miss) is **not** caused by `tenancyId` mismatch, since `tenancyId`
  is not part of the key. Root cause investigation continues; the defensive fallback in
  `getCaseDefinition()` guards correctness in the interim.

---

## Rejected Alternatives

**Per-tenant registry (include `tenancyId` in `equals()`):** Requires a per-request or
per-case-start registration mechanism that doesn't exist. Would break `registerKnownDefinitions()`
(startup context has no authenticated tenant). Adds runtime complexity for zero benefit in the
current deployment model.
