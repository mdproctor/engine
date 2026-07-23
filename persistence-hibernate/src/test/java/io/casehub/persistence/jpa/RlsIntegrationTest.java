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

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.platform.api.identity.TenancyConstants;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies RLS wiring end-to-end against real PostgreSQL via Quarkus Dev Services.
 *
 * <p>Requires Docker. Run with:
 *
 * <pre>
 *   TESTCONTAINERS_RYUK_DISABLED=true mvn test -pl persistence-hibernate -Dtest=RlsIntegrationTest
 * </pre>
 *
 * <p>Test strategy:
 *
 * <ul>
 *   <li>{@link RlsPolicyApplicator} runs at startup and applies RLS policies to all tables. Tests
 *       verify the app boots successfully with {@code casehub.rls.enabled=true} — startup failure
 *       would mean DDL errors (wrong table names, missing roles, etc.).
 *   <li>Insert own-tenant row via {@link EventLogRepository#append} — sets {@code
 *       casehub.tenancy_id = DEFAULT_TENANT_ID} in session (via MockCurrentPrincipal).
 *   <li>Insert other-tenant row via raw JDBC (bypasses reactive RLS session variable). No WITH
 *       CHECK on the policy, so JDBC inserts are always allowed.
 *   <li>Tenant-scoped repo correctly applies the explicit JPQL tenancyId filter — other-tenant rows
 *       are excluded by the JPQL WHERE clause (application-layer isolation).
 *   <li>Cross-tenant repo ({@link CrossTenantEventLogRepository}) uses {@code SET LOCAL ROLE
 *       casehub_crosstenancy} (BYPASSRLS) and can see rows across all tenants. This verifies the
 *       role-switch mechanism and GRANT setup in {@link RlsPolicyApplicator}.
 * </ul>
 *
 * <p>Note: Quarkus Dev Services creates a PostgreSQL superuser. In PostgreSQL, superusers bypass
 * RLS even with {@code FORCE ROW LEVEL SECURITY}. Therefore these tests verify the application-
 * layer tenant isolation (JPQL filter) and the cross-tenant BYPASSRLS path, not kernel-level RLS
 * filtering for the app user itself. RLS enforcement for non-superuser app users is verified by the
 * policy DDL being applied correctly at startup (no DDL errors = correct table names and SQL).
 */
@QuarkusTest
@TestProfile(RlsIntegrationTest.RlsProfile.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RlsIntegrationTest {

  public static class RlsProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "casehub.rls.enabled", "true",
          // Use Hibernate DDL (not Flyway) so tables exist before RlsPolicyApplicator fires.
          // Flyway migrations run after @Priority(100) StartupEvent observers in Quarkus
          // test mode, causing "relation does not exist" errors when RLS tries to ALTER TABLE.
          "quarkus.hibernate-orm.schema-management.strategy", "drop-and-create",
          "quarkus.flyway.migrate-at-start", "false");
    }
  }

  @Inject EventLogRepository eventLogRepository;
  @Inject CrossTenantEventLogRepository crossTenantEventLogRepository;
  @Inject AgroalDataSource dataSource;

  /** MockCurrentPrincipal always returns DEFAULT_TENANT_ID — so withTenantTransaction uses this. */
  private static final String OWN_TENANT = TenancyConstants.DEFAULT_TENANT_ID;

  private static final String OTHER_TENANT = "other-tenant-rls-" + UUID.randomUUID();

  /**
   * Verifies application-layer tenant isolation: the JPQL tenancyId filter in JpaEventLogRepository
   * ensures tenant-scoped reads never return other-tenant rows, regardless of RLS.
   */
  @Test
  void tenantScopedRepo_jpqlFilterExcludesOtherTenantRows() throws Exception {
    UUID ownCaseId = UUID.randomUUID();
    UUID otherCaseId = UUID.randomUUID();

    // Insert own-tenant row via tenant-scoped repo
    EventLog ownLog = makeLog(ownCaseId, CaseHubEventType.CASE_STARTED);
    eventLogRepository.append(ownLog, OWN_TENANT);

    // Insert other-tenant row via raw JDBC (RLS INSERT has no WITH CHECK — always allowed)
    insertRawOtherTenant(otherCaseId, OTHER_TENANT);

    // Tenant-scoped read: own-tenant row visible
    List<EventLog> ownFound =
        eventLogRepository.findByCaseAndTypes(
            ownCaseId, List.of(CaseHubEventType.CASE_STARTED), OWN_TENANT);
    assertThat(ownFound).hasSize(1);
    assertThat(ownFound.get(0).getCaseId()).isEqualTo(ownCaseId);

    // Tenant-scoped read with correct caseId but own tenantId: JPQL filter excludes other row
    // (tenancyId = OWN_TENANT excludes the row which has tenancy_id = OTHER_TENANT)
    List<EventLog> otherAttempt =
        eventLogRepository.findByCaseAndTypes(
            otherCaseId, List.of(CaseHubEventType.CASE_STARTED), OWN_TENANT);
    assertThat(otherAttempt).isEmpty();
  }

  /**
   * Verifies the cross-tenant BYPASSRLS path: {@code SET LOCAL ROLE casehub_crosstenancy} switches
   * to a role with BYPASSRLS, and {@link RlsPolicyApplicator} has granted that role table-level DML
   * access. Without the GRANT, the role switch succeeds but queries fail with "permission denied".
   */
  @Test
  void crossTenantRepo_bypassesRlsAndSeesAllTenants() throws Exception {
    UUID ownCaseId = UUID.randomUUID();
    UUID otherCaseId = UUID.randomUUID();

    // Insert own-tenant row
    EventLog ownLog = makeLog(ownCaseId, CaseHubEventType.CASE_COMPLETED);
    eventLogRepository.append(ownLog, OWN_TENANT);

    // Insert other-tenant row via raw JDBC
    insertRawOtherTenant(otherCaseId, OTHER_TENANT);

    // Cross-tenant read must see own-tenant row (BYPASSRLS via SET LOCAL ROLE casehub_crosstenancy)
    List<EventLog> ownResult =
        crossTenantEventLogRepository.findByCaseAndTypes(
            ownCaseId, List.of(CaseHubEventType.CASE_COMPLETED));
    assertThat(ownResult).anyMatch(e -> e.getCaseId().equals(ownCaseId));

    // Cross-tenant read must also see other-tenant row (BYPASSRLS skips tenant_isolation policy)
    List<EventLog> otherResult =
        crossTenantEventLogRepository.findByCaseAndTypes(
            otherCaseId, List.of(CaseHubEventType.CASE_STARTED));
    assertThat(otherResult).anyMatch(e -> e.getCaseId().equals(otherCaseId));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /**
   * Insert a raw event_log row for a different tenant via blocking JDBC. Since RLS only has a USING
   * clause (SELECT/UPDATE/DELETE), not WITH CHECK (INSERT), this insert always succeeds regardless
   * of the current session's casehub.tenancy_id.
   *
   * <p>Uses {@code nextval('event_log_SEQ')} for the id column — the sequence name is determined by
   * Hibernate's schema creation (drop-and-create mode) via {@link
   * io.quarkus.hibernate.reactive.panache.PanacheEntity}.
   */
  private void insertRawOtherTenant(UUID caseId, String tenancyId) throws Exception {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO event_log"
                    + " (id, case_id, event_type, stream_type, timestamp, tenancy_id)"
                    + " VALUES (nextval('event_log_SEQ'), ?::uuid, ?, ?, ?, ?)")) {
      ps.setString(1, caseId.toString());
      ps.setString(2, CaseHubEventType.CASE_STARTED.name());
      ps.setString(3, EventStreamType.CASE.name());
      ps.setObject(4, java.sql.Timestamp.from(Instant.now()));
      ps.setString(5, tenancyId);
      ps.executeUpdate();
    }
  }

  private EventLog makeLog(UUID caseId, CaseHubEventType type) {
    EventLog log = new EventLog();
    log.setCaseId(caseId);
    log.setEventType(type);
    log.setStreamType(EventStreamType.CASE);
    log.setTimestamp(Instant.now());
    return log;
  }

  private <T> T run(Supplier<Uni<T>> supplier) {
    try {
      return VertxContextSupport.subscribeAndAwait(supplier);
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }
}
