# 0007 — SystemCurrentPrincipal as Interim Engine-Internal Class

Date: 2026-06-02
Status: Accepted

## Context and Problem Statement

`CrossTenantProducer` requires a `CurrentPrincipal` that returns
`isCrossTenantAdmin() = true` to produce the `@CrossTenant`-qualified beans. Recovery
services and Quartz jobs run outside any request context — `@RequestScoped`
implementations of `CurrentPrincipal` would throw `ContextNotActiveException`.
`MockCurrentPrincipal` from `casehub-platform` is `@DefaultBean @ApplicationScoped`
and returns `crossTenantAdmin = false` by default.

Platform does not yet ship a system-actor `CurrentPrincipal` implementation.

## Decision Drivers

* The `@CrossTenant` producer must check `isCrossTenantAdmin()` at production time
* A request-scoped principal is unavailable in system/background contexts
* A second `@DefaultBean @ApplicationScoped CurrentPrincipal` would cause CDI ambiguity
  with `MockCurrentPrincipal` — must not use `@DefaultBean`
* The check is aspirational scaffolding today (always returns true), but establishes the
  wiring for future runtime enforcement

## Decision

Add `SystemCurrentPrincipal` to `runtime/internal/identity/` as a
`@ApplicationScoped @EngineSystem` bean (NOT `@DefaultBean`). It is only accessible
via the `@EngineSystem` qualifier — selected explicitly in `CrossTenantProducer`.

`isCrossTenantAdmin()` returns `true` unconditionally. The guard check in
`CrossTenantProducer` (`if (!systemPrincipal.isCrossTenantAdmin()) throw...`) is a
contract assertion — it would fail noisily at startup if the implementation were ever
changed, preventing accidental regressions when the platform principal replaces this class.

## Deletion Condition

Delete `SystemCurrentPrincipal` when `casehub-platform` ships a
`@ApplicationScoped CurrentPrincipal` with:
- `isCrossTenantAdmin() = true`
- `actorType() = ActorType.SYSTEM` (i.e. `actorId() = "system"`)
- Accessible via a published qualifier or directly injectable in non-request contexts

At that point: update `CrossTenantProducer` to inject the platform principal instead of
`@EngineSystem SystemCurrentPrincipal`, and delete this class.

## Consequences

* No CDI conflict with `MockCurrentPrincipal` — different annotation (`@EngineSystem`)
* `@EngineSystem` qualifier is engine-private — not part of any published SPI
* `SystemCurrentPrincipal` is in `runtime` (not `common`) — not visible to `scheduler-quartz`
  or `resilience` without runtime on the classpath
* Recovery in `resilience` module uses `@CrossTenant` injection — resolved via the producer
  in `runtime`, which is on the classpath when deployed
