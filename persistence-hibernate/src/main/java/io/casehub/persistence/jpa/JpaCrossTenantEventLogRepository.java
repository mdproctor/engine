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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Blocking cross-tenant JPA {@link CrossTenantEventLogRepository}. Delegates to {@link
 * JpaReactiveCrossTenantEventLogRepository} and awaits.
 */
@ApplicationScoped
public class JpaCrossTenantEventLogRepository implements CrossTenantEventLogRepository {

  @Inject JpaReactiveCrossTenantEventLogRepository delegate;

  @Override
  public List<EventLog> findByTypes(Collection<CaseHubEventType> types) {
    return delegate.findByTypes(types).await().indefinitely();
  }

  @Override
  public List<EventLog> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    return delegate.findByCaseAndTypes(caseId, types).await().indefinitely();
  }

  @Override
  public List<String> findSubmittedWorkWithoutCompletion() {
    return delegate.findSubmittedWorkWithoutCompletion().await().indefinitely();
  }

  @Override
  public List<EventLog> findByWorkerAndTypeAcrossTenants(String workerId, CaseHubEventType type) {
    return delegate.findByWorkerAndTypeAcrossTenants(workerId, type).await().indefinitely();
  }

  @Override
  public EventLog findById(Long id) {
    return delegate.findById(id).await().indefinitely();
  }

  @Override
  public List<EventLog> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    return delegate.findByCaseAndWorkerAndType(caseId, workerId, type).await().indefinitely();
  }
}
