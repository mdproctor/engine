# 0005 — @CrossTenant Qualifier as Convention-Based Access Control Gate

Date: 2026-06-02
Status: Accepted

## Context and Problem Statement

`CrossTenantEventLogRepository` and `CrossTenantCaseInstanceRepository` exist in
`casehub-engine-common/spi/` and are injectable by any CDI bean. Recovery services
and Quartz jobs legitimately need cross-tenant access; application-layer code must
not accidentally reach it. A structural enforcement mechanism is needed.

## Decision Drivers

* Cross-tenant access bypasses application-level tenancy filtering — unauthorized use
  causes data leakage across tenant boundaries
* The interfaces live in a public package, weakening package-level enforcement
* CDI qualifiers are visible in code review; unqualified injection of a cross-tenant
  SPI is immediately auditable
* A structural restriction (moving interfaces to an internal package) would break
  existing consumers and is out of scope for this batch

## Considered Options

* **Option A** — Convention-based: `@CrossTenant` qualifier + CDI producer, enforced
  by code review. Any class injecting `CrossTenantEventLogRepository` without the
  qualifier is visible as a violation in diff review.
* **Option B** — Structural: move interfaces to `internal.recovery.spi/` so they are
  not on the public classpath. Harder to accidentally inject; breaks consumers that
  import from the current package.
* **Option C** — No change: rely solely on documentation and developer discipline.

## Decision

Option A. The `@CrossTenant` qualifier and `CrossTenantProducer` gate access via a
CDI producer that checks `SystemCurrentPrincipal.isCrossTenantAdmin()`. The check is
aspirational scaffolding (always true today) but the qualifier itself makes every
cross-tenant injection point explicit in code review.

## What This Enforces

The qualifier is a **convention-based marker**, not a structural CDI constraint. CDI
does not prevent `@Inject CrossTenantEventLogRepository` (unqualified) — that resolves
directly to the unguarded bean. Enforcement relies on:
1. Code review: every injection of a cross-tenant SPI must have `@CrossTenant`
2. The qualifier annotation documents intent and is grep-auditable

## What It Does Not Enforce

Callers outside the qualifier contract can still inject the unguarded bean. Structural
enforcement requires moving the interfaces to an internal package — tracked as a
follow-on.

## Consequences

* All current cross-tenant injection sites (6 classes) updated to `@CrossTenant`
* `CrossTenantProducer` in `runtime/internal/identity/` is the single creation point
* `SystemCurrentPrincipal` is an interim engine-internal class; delete when platform
  ships a system-actor `CurrentPrincipal` with `isCrossTenantAdmin() = true`
* When platform ships a real principal, update `CrossTenantProducer` — one change site
