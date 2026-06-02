# Tenancy Enforcement Stack — Design Spec

**Branch:** `issue-405-tenancy-enforcement`
**Issues:** engine#411 · engine#405 · engine#406 · engine#410
**Date:** 2026-06-02 (revised after spec review)

---

## Context

Four sequential tenancy enforcement layers, done together because they are logically related and
build on the same foundation (engine#299 multi-tenancy). All live in a single branch. Each issue
is committed separately with its own `Refs #NNN`.

---

## Issue #411 — NOT NULL enforcement for tenancy_id (V2005 migration)

### Problem

V2002 and V2003 added `tenancy_id` to `worker_decision_entry` and `case_ledger_entry` as nullable
(`VARCHAR(64)`, no `NOT NULL`). The JPA entities declare `@Column(nullable = false)`. Under
Hibernate `validate` strategy this mismatch causes a startup error; under `drop-and-create` it is
silent but wrong.

Sequence context: V2004 (trust routing fields on `worker_decision_entry`) already exists on main.
This migration is V2005.

### Fix

Single migration `V2005__tenancy_id_not_null.sql` in `ledger/src/main/resources/db/engine-ledger/migration/`:

```sql
-- V2005: enforce NOT NULL on tenancy_id added by V2002/V2003.
-- Pre-migration rows existed in a single-tenant deployment — they belong to the
-- default tenant (TenancyConstants.DEFAULT_TENANT_ID). Backfill makes them
-- visible to that tenant after migration. Using the literal UUID because SQL
-- cannot reference Java constants.
UPDATE worker_decision_entry
   SET tenancy_id = '278776f9-e1b0-46fb-9032-8bddebdcf9ce'
 WHERE tenancy_id IS NULL;
ALTER TABLE worker_decision_entry ALTER COLUMN tenancy_id SET NOT NULL;

UPDATE case_ledger_entry
   SET tenancy_id = '278776f9-e1b0-46fb-9032-8bddebdcf9ce'
 WHERE tenancy_id IS NULL;
ALTER TABLE case_ledger_entry ALTER COLUMN tenancy_id SET NOT NULL;
```

`ALTER COLUMN SET NOT NULL` is a no-op in PostgreSQL if the column is already NOT NULL — safe to
run on a schema where a consumer pre-added the constraint.

Note: engine application tables (case_instance etc.) use `__system__` as their sentinel (per
ADR-0004). Ledger tables use `DEFAULT_TENANT_ID` because they are consumer-facing rows that should
surface to the owning tenant after migration.

---

## Issue #405 — @CrossTenant CDI producer pattern

### Problem

Six classes inject `CrossTenantEventLogRepository` or `CrossTenantCaseInstanceRepository`
directly by type. Protocol PP-20260520-e6a5f0 requires cross-tenant data access to be guarded by
a CDI producer that checks `isCrossTenantAdmin()`.

### Callers requiring update

| Class | Module | Cross-tenant repo(s) injected |
|-------|--------|-------------------------------|
| `DefaultWorkerExecutionRecoveryService` | `runtime` | `CrossTenantEventLogRepository`, `CrossTenantCaseInstanceRepository` |
| `PendingWorkRegistry` | `runtime` | `CrossTenantEventLogRepository` |
| `QuartzWorkerExecutionJob` | `scheduler-quartz` | `CrossTenantEventLogRepository` |
| `QuartzWorkerExecutionManager` | `scheduler-quartz` | `CrossTenantEventLogRepository` |
| `MilestoneSLATimeoutJob` | `scheduler-quartz` | `CrossTenantEventLogRepository` |
| `DeadLetterReplayService` | `casehub-resilience` (constructor-injected) | `CrossTenantEventLogRepository` |

### New CDI qualifiers

In `common/src/main/java/io/casehub/engine/common/qualifier/`:

```java
@Qualifier @Retention(RUNTIME) @Target({FIELD, METHOD, TYPE, PARAMETER})
public @interface CrossTenant {}

@Qualifier @Retention(RUNTIME) @Target({FIELD, METHOD, TYPE, PARAMETER})
public @interface EngineSystem {}   // @EngineSystem to avoid collision with platform/Quarkus namespaces
```

### SystemCurrentPrincipal

In `runtime/src/main/java/io/casehub/engine/internal/identity/SystemCurrentPrincipal.java`:

```java
/**
 * Engine-internal system-actor CurrentPrincipal. Always cross-tenant admin.
 * Not @DefaultBean — accessed only via @EngineSystem qualifier, never replaces MockCurrentPrincipal.
 *
 * Interim: delete when casehub-platform ships a platform-level system-actor principal.
 * Deletion condition: platform provides @ApplicationScoped SystemCurrentPrincipal with
 * isCrossTenantAdmin() = true and actorType() = ActorType.SYSTEM.
 */
@ApplicationScoped
public class SystemCurrentPrincipal implements CurrentPrincipal {
    @Override public String actorId()             { return "system"; }
    @Override public Set<String> groups()         { return Set.of(); }
    @Override public String tenancyId()           { return TenancyConstants.DEFAULT_TENANT_ID; }
    @Override public boolean isCrossTenantAdmin() { return true; }
    // isSystem() inherits via default: actorType() resolves "system" → ActorType.SYSTEM → true
}
```

### CrossTenantProducer

In `runtime/src/main/java/io/casehub/engine/internal/identity/CrossTenantProducer.java`:

```java
@ApplicationScoped
public class CrossTenantProducer {

    @Inject @EngineSystem SystemCurrentPrincipal systemPrincipal;
    @Inject CrossTenantEventLogRepository eventLogRepo;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepo;

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantEventLogRepository produceEventLog() {
        // Contract assertion: SystemCurrentPrincipal.isCrossTenantAdmin() is hardcoded true.
        // This check is aspirational scaffolding — it can never throw today. Its purpose is
        // to make the invariant explicit: if SystemCurrentPrincipal is ever changed to return
        // false (e.g. to test the guard), this producer fails noisily at startup rather than
        // silently granting access. Update this producer when platform provides a real
        // system-actor principal with runtime-evaluated isCrossTenantAdmin().
        if (!systemPrincipal.isCrossTenantAdmin()) {
            throw new IllegalStateException(
                "SystemCurrentPrincipal.isCrossTenantAdmin() must return true — engine#405");
        }
        return eventLogRepo;
    }

    @Produces @CrossTenant @ApplicationScoped
    public CrossTenantCaseInstanceRepository produceCaseInstance() {
        if (!systemPrincipal.isCrossTenantAdmin()) {
            throw new IllegalStateException(
                "SystemCurrentPrincipal.isCrossTenantAdmin() must return true — engine#405");
        }
        return caseInstanceRepo;
    }
}
```

Both produced beans are `@ApplicationScoped` — the check executes once at startup.

### Access control model

`@CrossTenant` is a **convention-based marker**, not a structural enforcement. CDI qualifiers do not
prevent unqualified injection — any class can still write `@Inject CrossTenantEventLogRepository`
and CDI resolves it to the unguarded bean directly.

The actual gates are:
1. The `@CrossTenant` qualifier makes unauthorized access visible in code review
2. Package placement signals intent (`CrossTenantEventLogRepository` is in `engine-common/spi/`
   rather than the originally planned `internal.recovery.spi/` — the package defence is weakened;
   this is a known gap from the foundation PR)
3. ADR documents what the qualifier enforces and what it does not

Real structural enforcement requires moving the cross-tenant interfaces to a package that is
genuinely inaccessible to non-recovery callers — tracked as a follow-on.

### Protocol compliance

- PP-20260520-e6a5f0: tenancy filtering in data access layer; cross-tenant access via producer ✅
- PP-20260514-engine-spi-noops-defaultbean: `SystemCurrentPrincipal` not `@DefaultBean` ✅
- PP-20260522-359dfc: `isSystem()` delegates to `actorType()` ✅

### Testing

- `CrossTenantProducerTest` — unit test: mock `@EngineSystem SystemCurrentPrincipal`, verify
  producer returns repo when `isCrossTenantAdmin() = true`; verify it throws when overridden to
  return false
- `EngineDecouplingIT` — update `@Inject CrossTenantEventLogRepository` to `@CrossTenant`
- All six injection sites updated to `@CrossTenant`; `DeadLetterReplayService` constructor
  parameter gets `@CrossTenant` annotation

---

## Issue #406 — DB-level Row Level Security

### Overview

Two distinct concerns: schema setup (applying RLS policies after `drop-and-create`) and runtime
variable injection (setting `casehub.tenancy_id` per transaction in the reactive pipeline).

### Critical constraint: reactive stack

All JPA repositories in `persistence-hibernate` use Hibernate Reactive / Mutiny Panache.
`SET LOCAL` must be executed inside the reactive transaction pipeline using the reactive session.
Blocking JDBC approaches (`Session.doWork()`, `EntityManager.unwrap().createStatement()`) operate
on the Agroal JDBC pool, which is a completely separate connection pool from the Vert.x reactive
PostgreSQL client. Blocking `doWork()` has zero effect on reactive queries. CDI `@AroundInvoke`
interceptors complete before the reactive `Uni` pipeline starts executing — there is no transaction
open when the interceptor runs. `@TenantBound` / `TenantBoundInterceptor` from the initial spec
are removed entirely.

### Prerequisite refactor: split JpaEventLogRepository

`JpaEventLogRepository` currently implements both `EventLogRepository` (tenant-scoped) and
`CrossTenantEventLogRepository` (cross-tenant). These require different RLS session variables —
they cannot share one class without applying the wrong variable to one of them.

`JpaCrosstenantCaseInstanceRepository` already exists as a separate class (consistent with this
split). `JpaEventLogRepository` needs the same treatment:

- `JpaEventLogRepository` — implements `EventLogRepository` only; uses `withTenantTransaction()`
- `JpaCrosstenantEventLogRepository` (new) — implements `CrossTenantEventLogRepository`; uses
  `withCrossTenantTransaction()`

The split is small: cross-tenant methods don't filter by `tenancy_id`, so they are already
distinguishable at the SPI level. The new class is not registered as `@DefaultBean` — it is only
accessible via the `@CrossTenant` producer.

### Abstract base class: TenantAwareRepository

In `persistence-hibernate/src/main/java/.../internal/rls/TenantAwareRepository.java`:

```java
public abstract class TenantAwareRepository {

    @Inject CurrentPrincipal currentPrincipal;

    /**
     * Wraps the work in a reactive transaction that sets casehub.tenancy_id = current tenant
     * before any SQL executes. SET LOCAL resets automatically at transaction end.
     */
    protected <T> Uni<T> withTenantTransaction(Supplier<Uni<T>> work) {
        return Panache.withTransaction(() ->
            Panache.getSession().flatMap(session ->
                session.createNativeQuery(
                        "SET LOCAL \"casehub.tenancy_id\" = :tid")
                    .setParameter("tid", currentPrincipal.tenancyId())
                    .executeUpdate()
                    .replaceWith(work.get())));
    }

    /**
     * Wraps the work in a reactive transaction using the casehub_crosstenancy role
     * (BYPASSRLS). SET LOCAL ROLE reverts automatically at transaction end, preventing
     * role leakage to subsequent transactions on the same pooled connection.
     *
     * Prerequisite: the casehub_crosstenancy role must exist with BYPASSRLS attribute
     * and the app user must be a member. RlsPolicyApplicator creates it when
     * casehub.rls.enabled=true. Fails loudly if the role is absent — no silent bypass.
     */
    protected <T> Uni<T> withCrossTenantTransaction(Supplier<Uni<T>> work) {
        return Panache.withTransaction(() ->
            Panache.getSession().flatMap(session ->
                session.createNativeQuery("SET LOCAL ROLE casehub_crosstenancy")
                    .executeUpdate()
                    .replaceWith(work.get())));
    }
}
```

`JpaEventLogRepository` and `JpaCrosstenantEventLogRepository` both extend `TenantAwareRepository`.
Every repository method that currently calls `Panache.withTransaction(() -> ...)` calls
`withTenantTransaction(() -> ...)` or `withCrossTenantTransaction(() -> ...)` instead.

### RLS policy design

Single-branch policy — the BYPASSRLS role handles cross-tenant access at the PostgreSQL level,
so the policy itself only needs to express tenant-scoped filtering:

```sql
CREATE POLICY tenant_isolation ON <table>
    USING (tenancy_id = current_setting('casehub.tenancy_id', true));
```

`current_setting('casehub.tenancy_id', true)` — the `true` flag returns null rather than throwing
if the variable is not set. `tenancy_id = null` evaluates to false → safe default (deny, not bypass)
when no tenancy variable is set.

Cross-tenant access uses `SET LOCAL ROLE casehub_crosstenancy` (a PostgreSQL role with
`BYPASSRLS`). RLS policies are bypassed entirely for that role — the `tenant_isolation` policy
does not run for cross-tenant transactions. This is cleaner than a two-branch policy and auditable
via `pg_roles` (visible as a role assignment, not buried in a session variable).

### Schema setup: RlsPolicyApplicator

In `persistence-hibernate/src/main/java/.../internal/rls/RlsPolicyApplicator.java`:

- `@ApplicationScoped` startup bean observing `StartupEvent` at `@Priority(100)` — after Hibernate
  schema creation (priority `Integer.MIN_VALUE`) and after `DefaultCaseDefinitionRegistry`
  (priority 10)
- Uses `AgroalDataSource` (blocking JDBC) for DDL — DDL at startup is correct to run on the
  blocking pool; this is not subject to the reactive incompatibility (which only affects
  per-transaction DML variable injection)
- Creates the `casehub_crosstenancy` role with `BYPASSRLS` if it does not exist, then grants
  membership to the current session user. Requires `CREATEROLE` privilege on the app DB user.
  If the app user lacks `CREATEROLE`, this step must be performed by a DBA before enabling RLS
  (`casehub.rls.enabled=true`). The `withCrossTenantTransaction()` helper fails loudly at runtime
  if the role is absent — no silent bypass.
- For each engine table: `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY`, then create
  the `tenant_isolation` policy guarded by a `pg_policies` existence check (idempotent)
- `FORCE ROW LEVEL SECURITY` prevents table-owner bypass — without it, the DB user running the
  app silently bypasses all policies. With BYPASSRLS for the cross-tenant role, `FORCE RLS`
  is still correct: it prevents the app's default session role from bypassing policies; the
  cross-tenant role's bypass is explicit and auditable, not a side-effect of table ownership

Tables: `case_instance`, `case_meta_model`, `event_log`, `plan_item`, `sub_case_group`

Scope note: `work_adapter_plan_item` lives in the casehub-work datasource (not the engine
datasource) and is out of scope for this issue. A follow-on issue should apply RLS to the
casehub-work datasource.

### Activation flag

```java
@ConfigProperty(name = "casehub.rls.enabled", defaultValue = "false")
boolean rlsEnabled;
```

Default is `false`. `RlsPolicyApplicator.onStart()` returns immediately if false.

**The `casehub.rls.enabled=true` default must not be set in the engine's own
`application.properties` until the reactive `withTenantTransaction()` approach is validated
end-to-end against real PostgreSQL.** Until then, enabling RLS in production with the reactive
path untested risks silently returning empty result sets on all queries (if `FORCE RLS` is active
and `casehub.tenancy_id` is never set). The schema setup (DDL) is ready; the runtime injection
(DML) needs integration test coverage first.

Consumer deployments that want to opt in before full validation: set
`casehub.rls.enabled=true` explicitly and run the integration test suite.

### RLS + cross-tenant coherence

`@CrossTenant` (issue #405) and RLS (issue #406) are two layers of the same enforcement model:

- `@CrossTenant` is the call-site marker — only code that explicitly requests cross-tenant access
  can reach `CrossTenantEventLogRepository` / `CrossTenantCaseInstanceRepository`
- RLS is the DB-level enforcement — even direct JDBC access cannot bypass the policy

The two interact via complementary PostgreSQL mechanisms:
- Tenant-scoped code: `withTenantTransaction()` sets `casehub.tenancy_id`; RLS policy filters rows
- Cross-tenant code: `withCrossTenantTransaction()` sets `SET LOCAL ROLE casehub_crosstenancy`;
  BYPASSRLS means the RLS policy does not run at all for that transaction

`SET LOCAL` is correct for both — it resets at transaction end, preventing any leakage across
transactions on pooled connections. Both helpers re-apply the variable/role at the start of every
transaction they wrap. This is intentional symmetric design, not a limitation.

Neither mechanism alone is sufficient; together they form a defence-in-depth model.

### Testing

`RlsIntegrationTest` in `persistence-hibernate/src/test/` — requires Testcontainers PostgreSQL:

1. Insert rows for two tenants via `JpaEventLogRepository` (reactive, goes through
   `withTenantTransaction()`)
2. Assert `JpaEventLogRepository.findByCaseAndTypes()` returns only the requesting tenant's rows
3. Assert `JpaCrosstenantEventLogRepository` (via `withCrossTenantTransaction()`, using the
   `casehub_crosstenancy` BYPASSRLS role) returns all rows across tenants
4. Assert that with no variable set and no role switch (raw reactive session, no wrapper), queries
   return nothing — confirming `FORCE ROW LEVEL SECURITY` is active and the default deny holds

The test must use the actual reactive repository methods. Raw JDBC assertions do not exercise the
reactive path and will produce false positives if the reactive variable injection is broken.

---

## Issue #410 — Registry lookup bug

### Root cause

`DefaultCaseDefinitionRegistry` uses `ConcurrentHashMap<CaseMetaModel, CaseDefinition>`.
`CaseMetaModel` is a mutable POJO. Its `hashCode()` uses `namespace`, `name`, `version` — mutable
fields with public setters. If any of these fields is mutated after insertion, the key is in the
wrong hash bucket: `Map.get()` returns null (looks in the new-hash bucket) while `entrySet()`
iteration still finds it (visits every entry regardless of bucket). The defensive log-and-scan
guard in `getCaseDefinition()` was added exactly for this scenario.

### Fix: immutable CaseKey record + single RegistryEntry map

**`CaseKey`** — new immutable record in
`common/src/main/java/io/casehub/engine/common/internal/model/CaseKey.java`:

```java
/** Immutable (namespace, name, version) identity key for case definitions. */
public record CaseKey(String namespace, String name, String version) {

    public static CaseKey of(CaseMetaModel m) {
        return new CaseKey(m.getNamespace(), m.getName(), m.getVersion());
    }

    public static CaseKey of(CaseDefinition d) {
        return new CaseKey(d.getNamespace(), d.getName(), d.getVersion());
    }
}
```

Records give `equals()` and `hashCode()` automatically from all components; all components are
`final`. Mutation is structurally impossible.

**`RegistryEntry`** — co-locate definition and metaModel to eliminate consistency window:

```java
record RegistryEntry(CaseDefinition definition, CaseMetaModel metaModel) {}
```

Using two separate `ConcurrentHashMap.put()` calls creates a window where `getCaseMetaModel(d)`
throws `RuntimeException("not found")` between the first and second put. A single put with
`RegistryEntry` is atomic at the map level.

**`DefaultCaseDefinitionRegistry` changes:**

```java
// Single map — O(1) lookup in both directions, no consistency window
private final Map<CaseKey, RegistryEntry> registry = new ConcurrentHashMap<>();
```

`registerCaseDefinition()`:
- Early-exit: `registry.containsKey(CaseKey.of(definition))` replaces the linear keySet scan
- Early-exit return value: `Uni.createFrom().item(registry.get(CaseKey.of(definition)).metaModel())`
  — returns the already-registered `CaseMetaModel`, matching the original behaviour
- On registration: `registry.put(CaseKey.of(saved), new RegistryEntry(model, saved))`

`getCaseDefinition(CaseMetaModel m)`:
```java
RegistryEntry entry = registry.get(CaseKey.of(m));
return entry != null ? entry.definition() : null;  // O(1), no linear scan
```

`getCaseMetaModel(CaseDefinition d)`:
```java
RegistryEntry entry = registry.get(CaseKey.of(d));
if (entry == null) throw new RuntimeException(
    "CaseMetaModel not found: " + d.getNamespace() + "." + d.getName() + ":" + d.getVersion());
return entry.metaModel();
```

The defensive `LOG.warnf("getCaseDefinition: Map.get() missed...")` linear scan is removed — it
was compensating for the bug. With an immutable key it cannot fire.

### SPI impact

`CaseDefinitionRegistry` interface signatures are unchanged. `CaseKey` and `RegistryEntry` are
implementation details. No SPI break.

### Known gap: startup tenancy assumption (pre-existing, out of scope for #410)

`registerCaseDefinition()` still calls `currentPrincipal.tenancyId()` at startup. All
classpath-defined `CaseHub` definitions are registered under whatever `tenancyId()` returns then
(always `DEFAULT_TENANT_ID` from `MockCurrentPrincipal`). In a deployment with a real
`@RequestScoped` `CurrentPrincipal`, this call outside a request context throws
`ContextNotActiveException`. In a multi-tenant deployment, non-default-tenant cases would reference
`CaseMetaModel` records owned by the default tenant.

This is a pre-existing design issue, not introduced by #410. Tracked separately.

### Testing

`DefaultCaseDefinitionRegistryTest` (new unit test, no Quarkus):
- Register a definition; mutate the returned `CaseMetaModel`'s namespace/name/version fields
- Assert `getCaseDefinition()` still returns the correct value (mutation safety)
- Assert early-exit path returns the correct existing `CaseMetaModel`

`CaseKeyTest` — record equality and hashCode contract (explicit, not trivial: verifies that two
`CaseKey` instances with equal fields have equal hashCode regardless of object identity).

---

## Implementation order

1. **#411** — migration only, independent, do first
2. **#405** — qualifiers + producer + injection site updates; self-contained
3. **#406** — split JpaEventLogRepository (prerequisite refactor), then base class + RLS schema
   setup; depends on nothing from #405
4. **#410** — registry refactor; self-contained, touches runtime only

---

## ADR stubs (write during implementation)

Three decisions warrant ADRs:

**ADR: @CrossTenant as convention-based access control**
Scope: what `@CrossTenant` enforces (call-site visibility in code review), what it does not
enforce (CDI cannot prevent unqualified injection), why not stronger (structural package placement
would require moving interfaces to internal packages breaking current consumers), deletion condition
(platform provides package-private enforcement or internal package move completes).

**ADR: Application-managed RLS with drop-and-create schema**
Scope: why RLS policies are applied programmatically at startup rather than by DBA or Flyway; the
trade-off (policies lost on restart, re-applied by `RlsPolicyApplicator`); why `casehub.rls.enabled`
defaults false; path to enabling in production; scope boundary (engine datasource only).

**ADR: SystemCurrentPrincipal as interim engine-internal class**
Scope: why it lives in the engine rather than the platform; what it covers; deletion condition
(platform ships `@ApplicationScoped SystemCurrentPrincipal` with `isCrossTenantAdmin() = true`
and `actorType() = SYSTEM`); what happens to `CrossTenantProducer` when it is deleted.
