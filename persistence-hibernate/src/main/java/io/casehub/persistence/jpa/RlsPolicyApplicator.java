/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.persistence.jpa;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Applies PostgreSQL Row Level Security policies to engine tables at startup.
 *
 * <p>Priority 100 runs after Hibernate schema creation (MIN_VALUE) and
 * DefaultCaseDefinitionRegistry (priority 10). Uses blocking JDBC (Agroal) for DDL — correct for
 * schema setup; not the reactive query path.
 *
 * <p>Prerequisites when casehub.rls.enabled=true:
 *
 * <ul>
 *   <li>PostgreSQL (not H2 — H2 does not support RLS or SET LOCAL ROLE)
 *   <li>App DB user must have CREATEROLE privilege, OR a DBA must pre-create casehub_crosstenancy
 *       with BYPASSRLS before enabling RLS.
 * </ul>
 */
@ApplicationScoped
public class RlsPolicyApplicator {

  private static final Logger LOG = Logger.getLogger(RlsPolicyApplicator.class);

  private static final List<String> TABLES =
      List.of("case_instance", "case_meta_model", "event_log", "plan_item", "subcase_group");

  @Inject AgroalDataSource dataSource;

  @ConfigProperty(name = "casehub.rls.enabled", defaultValue = "false")
  boolean rlsEnabled;

  void onStart(@Observes @Priority(100) StartupEvent ev) {
    if (!rlsEnabled) {
      LOG.debug("RLS disabled (casehub.rls.enabled=false) — skipping policy application");
      return;
    }
    LOG.info("Applying PostgreSQL Row Level Security policies");
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      createBypassRole(stmt);
      for (String table : TABLES) {
        applyRls(stmt, table);
      }
      LOG.infof("RLS applied to %d tables", TABLES.size());
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to apply RLS policies", e);
    }
  }

  private void createBypassRole(Statement stmt) throws SQLException {
    stmt.execute(
        "DO $$ BEGIN "
            + "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'casehub_crosstenancy') THEN"
            + "    EXECUTE 'CREATE ROLE casehub_crosstenancy BYPASSRLS'; "
            + "  END IF; "
            + "END $$");
    stmt.execute("GRANT casehub_crosstenancy TO current_user");
    // Grant DML access on engine tables only so the cross-tenant role can query them.
    // Without this, SET LOCAL ROLE casehub_crosstenancy succeeds (role switch works) but
    // any subsequent SELECT/INSERT fails with "permission denied for table".
    // Scoped to TABLES (not ALL TABLES IN SCHEMA) to avoid granting DML on casehub-work,
    // casehub-ledger, and other tables that may share the same schema in shared deployments.
    // Table names come from the hardcoded TABLES constant above — not user input
    for (String table : TABLES) {
      stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO casehub_crosstenancy");
    }
    stmt.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO casehub_crosstenancy");
  }

  private void applyRls(Statement stmt, String table)
      throws SQLException { // NOSONAR — table is from the hardcoded TABLES constant, not user input
    stmt.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
    stmt.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
    stmt.execute(
        "DO $$ BEGIN "
            + "  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = '"
            + table
            + "' AND policyname = 'tenant_isolation') THEN "
            + "    EXECUTE 'CREATE POLICY tenant_isolation ON "
            + table
            + " USING (tenancy_id = current_setting(''casehub.tenancy_id'', true))'; "
            + "  END IF; "
            + "END $$");
  }
}
