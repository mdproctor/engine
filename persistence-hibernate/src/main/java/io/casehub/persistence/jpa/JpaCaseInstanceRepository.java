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

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.query.CaseInstanceQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Blocking JPA {@link CaseInstanceRepository}. Delegates to {@link
 * JpaReactiveCaseInstanceRepository} and awaits.
 */
@ApplicationScoped
public class JpaCaseInstanceRepository implements CaseInstanceRepository {

  @Inject JpaReactiveCaseInstanceRepository delegate;

  @Override
  public CaseInstance save(CaseInstance instance, String tenancyId) {
    return delegate.save(instance, tenancyId).await().indefinitely();
  }

  @Override
  public CaseInstance update(CaseInstance instance, String tenancyId) {
    return delegate.update(instance, tenancyId).await().indefinitely();
  }

  @Override
  public CaseInstance findByUuid(UUID uuid, String tenancyId) {
    return delegate.findByUuid(uuid, tenancyId).await().indefinitely();
  }

  @Override
  public void updateStateAndAppendEvent(
      CaseInstance instance, EventLog eventLog, String tenancyId) {
    delegate.updateStateAndAppendEvent(instance, eventLog, tenancyId).await().indefinitely();
  }

  @Override
  public List<CaseInstance> findByStatus(CaseStatus status, String tenancyId) {
    return delegate.findByStatus(status, tenancyId).await().indefinitely();
  }

  @Override
  public List<CaseInstance> findAll(String tenancyId) {
    return delegate.findAll(tenancyId).await().indefinitely();
  }

  @Override
  public List<CaseInstance> findByNamespaceAndName(
      String namespace, String name, String tenancyId) {
    return delegate.findByNamespaceAndName(namespace, name, tenancyId).await().indefinitely();
  }

  @Override
  public List<CaseInstance> query(CaseInstanceQuery query, String tenancyId) {
    return delegate.query(query, tenancyId).await().indefinitely();
  }

  @Override
  public long count(CaseInstanceQuery query, String tenancyId) {
    return delegate.count(query, tenancyId).await().indefinitely();
  }
}
