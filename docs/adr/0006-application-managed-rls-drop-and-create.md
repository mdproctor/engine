# 0006 — Application-Managed RLS with Drop-and-Create Schema Strategy

Date: 2026-06-02
Status: Accepted

## Context and Problem Statement

The engine uses `quarkus.hibernate-orm.schema-management.strategy=drop-and-create`
for its application tables. PostgreSQL RLS policies are attached to tables and are
dropped when the table is dropped. A deployment mechanism is needed that applies RLS
policies after each schema recreation.

Additionally, cross-tenant repository access requires a PostgreSQL role with
`BYPASSRLS` (`casehub_crosstenancy`). This role must exist before any cross-tenant
transaction executes.

## Decision Drivers

* `drop-and-create` invalidates any DBA-managed or Flyway-managed RLS policies on
  restart — they must be reapplied programmatically
* No production deployment exists yet; schema is recreated on every restart
* Application owns the full schema lifecycle; DBA pre-requisites should be minimal

## Considered Options

* **Option A** — Application-managed via `RlsPolicyApplicator` startup bean (Priority 100,
  after Hibernate schema creation at `MIN_VALUE`). Creates the BYPASSRLS role and applies
  policies via blocking JDBC DDL at startup.
* **Option B** — DBA-managed: document policies as SQL scripts; DBA applies them after
  each deployment. Decouples application from DDL but requires DBA coordination on every
  restart in dev/test environments.
* **Option C** — Flyway migrations: add RLS policy SQL to Flyway. Incompatible with
  `drop-and-create` — migrations are designed for incremental schema evolution, not
  idempotent policy re-application.

## Decision

Option A. `RlsPolicyApplicator` applies RLS programmatically at startup, idempotent via
`pg_policies` existence checks. Controlled by `casehub.rls.enabled` (default `false`)
so consumers can opt in independently and the engine itself is not blocked on RLS being
ready.

## RLS Policy

```sql
CREATE POLICY tenant_isolation ON <table>
    USING (tenancy_id = current_setting('casehub.tenancy_id', true));
```

`FORCE ROW LEVEL SECURITY` prevents the table owner from bypassing the policy.
`current_setting(..., true)` returns null (not an error) when the variable is unset —
policy denies all rows (safe default).

## Cross-Tenant Access

`SET LOCAL ROLE casehub_crosstenancy` in `withCrossTenantTransaction()`. The role has
`BYPASSRLS`. `RlsPolicyApplicator` creates it when `casehub.rls.enabled=true`.
Requires `CREATEROLE` on the app DB user; DBA must pre-create otherwise.

## Consequences

* `casehub.rls.enabled` defaults to `false` — opt-in per deployment
* `RlsPolicyApplicator` runs at `@Priority(100)` after Hibernate
* 5 tables covered: `case_instance`, `case_meta_model`, `event_log`, `plan_item`,
  `sub_case_group`
* `work_adapter_plan_item` is in the casehub-work datasource — out of scope; tracked
  as a follow-on for casehub-work
* Kernel-level RLS filtering cannot be integration-tested with the PostgreSQL superuser
  created by Quarkus Dev Services — document this limitation in `RlsIntegrationTest`
