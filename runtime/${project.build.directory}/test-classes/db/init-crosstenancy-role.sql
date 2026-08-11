-- Test-only: create the casehub_crosstenancy role required by withCrossTenantTransaction().
-- In production this role is created by RlsPolicyApplicator when casehub.rls.enabled=true.
-- In runtime tests RLS is not enabled, so we create only the role here to allow cross-tenant
-- queries to run without failing at SET LOCAL ROLE.
--
-- ALTER DEFAULT PRIVILEGES ensures the role can access tables created later by Hibernate
-- drop-and-create (init script runs before schema creation).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'casehub_crosstenancy') THEN
    EXECUTE 'CREATE ROLE casehub_crosstenancy BYPASSRLS';
  END IF;
END $$;

GRANT casehub_crosstenancy TO current_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO casehub_crosstenancy;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO casehub_crosstenancy;
