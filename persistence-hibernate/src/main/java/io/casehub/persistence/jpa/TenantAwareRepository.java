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

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Extends AbstractJpaRepository with RLS session-variable injection.
 *
 * <p>withTenantTransaction(): sets SET LOCAL "casehub.tenancy_id" = current tenant before any SQL.
 * Used by all tenant-scoped repositories (EventLog, CaseInstance, CaseMetaModel, etc.). Wraps reads
 * in withTransaction() because SET LOCAL only applies within an explicit transaction.
 *
 * <p>withCrossTenantTransaction(): sets SET LOCAL ROLE casehub_crosstenancy (BYPASSRLS role). Used
 * by cross-tenant repositories. Only issues the role switch when casehub.rls.enabled=true;
 * otherwise runs the work in a plain transaction.
 */
abstract class TenantAwareRepository extends AbstractJpaRepository {

  @ConfigProperty(name = "casehub.rls.enabled", defaultValue = "false")
  boolean rlsEnabled;

  protected <T> Uni<T> withTenantTransaction(String tenancyId, Supplier<Uni<T>> work) {
    if (tenancyId == null || tenancyId.contains("'") || tenancyId.contains("\\")) {
      throw new IllegalStateException("Invalid tenancyId: " + tenancyId);
    }
    String sql = "SET LOCAL \"casehub.tenancy_id\" = '" + tenancyId + "'";
    return withSafeContext(
        () ->
            Panache.withTransaction(
                () ->
                    Panache.getSession()
                        .flatMap(
                            session ->
                                session
                                    .createNativeQuery(sql)
                                    .executeUpdate()
                                    .replaceWith(work.get()))));
  }

  protected <T> Uni<T> withCrossTenantTransaction(Supplier<Uni<T>> work) {
    if (!rlsEnabled) {
      return withSafeContext(() -> Panache.withTransaction(work));
    }
    return withSafeContext(
        () ->
            Panache.withTransaction(
                () ->
                    Panache.getSession()
                        .flatMap(
                            session ->
                                session
                                    .createNativeQuery("SET LOCAL ROLE casehub_crosstenancy")
                                    .executeUpdate()
                                    .replaceWith(work.get()))));
  }
}
