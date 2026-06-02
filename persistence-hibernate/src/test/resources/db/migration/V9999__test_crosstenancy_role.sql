-- Test-only: create the casehub_crosstenancy role required by withCrossTenantTransaction().
-- In production this role is created by RlsPolicyApplicator when casehub.rls.enabled=true.
-- In tests RLS is not enabled, so we create only the role here to allow cross-tenant queries to run.
-- We also grant SELECT on all tables since withCrossTenantTransaction uses SET LOCAL ROLE,
-- which switches the effective role and requires explicit privileges.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'casehub_crosstenancy') THEN
    EXECUTE 'CREATE ROLE casehub_crosstenancy BYPASSRLS';
  END IF;
END $$;

GRANT casehub_crosstenancy TO current_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO casehub_crosstenancy;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO casehub_crosstenancy;
