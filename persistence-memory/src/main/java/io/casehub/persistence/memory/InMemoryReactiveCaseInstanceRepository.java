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
package io.casehub.persistence.memory;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.ReactiveCaseInstanceRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantCaseInstanceRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/**
 * Reactive mirror of {@link InMemoryCaseInstanceRepository}. Delegates all operations to the
 * blocking canonical and wraps results in {@code Uni}. Same tenancyId rules apply.
 *
 * @see InMemoryCaseInstanceRepository
 */
@Alternative
@ApplicationScoped
public class InMemoryReactiveCaseInstanceRepository
    implements ReactiveCaseInstanceRepository, ReactiveCrossTenantCaseInstanceRepository {

  @Inject InMemoryCaseInstanceRepository delegate;

  public void setDelegate(InMemoryCaseInstanceRepository delegate) {
    this.delegate = delegate;
  }

  @Override
  public Uni<CaseInstance> save(CaseInstance instance, String tenancyId) {
    return Uni.createFrom().item(delegate.save(instance, tenancyId));
  }

  @Override
  public Uni<CaseInstance> update(CaseInstance instance, String tenancyId) {
    return Uni.createFrom().item(delegate.update(instance, tenancyId));
  }

  @Override
  public Uni<CaseInstance> findByUuid(UUID uuid, String tenancyId) {
    CaseInstance result = delegate.findByUuid(uuid, tenancyId);
    return result == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(result);
  }

  @Override
  public Uni<CaseInstance> findByUuid(UUID uuid) {
    CaseInstance result = delegate.findByUuid(uuid);
    return result == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(result);
  }

  @Override
  public Uni<Void> updateStateAndAppendEvent(
      CaseInstance instance, EventLog eventLog, String tenancyId) {
    delegate.updateStateAndAppendEvent(instance, eventLog, tenancyId);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<List<CaseInstance>> findByStatus(CaseStatus status, String tenancyId) {
    return Uni.createFrom().item(delegate.findByStatus(status, tenancyId));
  }

  @Override
  public Uni<List<CaseInstance>> findAll(String tenancyId) {
    return Uni.createFrom().item(delegate.findAll(tenancyId));
  }

  @Override
  public Uni<List<CaseInstance>> findByNamespaceAndName(
      String namespace, String name, String tenancyId) {
    return Uni.createFrom().item(delegate.findByNamespaceAndName(namespace, name, tenancyId));
  }
}
