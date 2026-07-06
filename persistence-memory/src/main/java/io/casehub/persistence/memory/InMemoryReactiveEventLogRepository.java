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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.CrossTenantEventLogRepository;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.ReactiveCrossTenantEventLogRepository;
import io.casehub.engine.common.spi.ReactiveEventLogRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reactive mirror of {@link InMemoryEventLogRepository}. Delegates all operations to the blocking
 * canonical and wraps results in {@code Uni}. Same tenancyId rules apply.
 *
 * <p>Delegates are injected by SPI interface (not concrete class) to avoid Quarkus ARC
 * {@code @Alternative} resolution issues where concrete-class injection resolves to a different
 * bean instance than the active alternative.
 *
 * @see InMemoryEventLogRepository
 */
@Alternative
@ApplicationScoped
public class InMemoryReactiveEventLogRepository
    implements ReactiveEventLogRepository, ReactiveCrossTenantEventLogRepository {

  @Inject EventLogRepository delegate;
  @Inject CrossTenantEventLogRepository crossTenantDelegate;

  public void setDelegate(InMemoryEventLogRepository delegate) {
    this.delegate = delegate;
    this.crossTenantDelegate = delegate;
  }

  // ── Tenant-scoped methods ────────────────────────────────────────────────

  @Override
  public Uni<Void> append(EventLog eventLog, String tenancyId) {
    delegate.append(eventLog, tenancyId);
    return Uni.createFrom().voidItem();
  }

  @Override
  public Uni<Long> appendAndReturnId(EventLog eventLog, String tenancyId) {
    return Uni.createFrom().item(delegate.appendAndReturnId(eventLog, tenancyId));
  }

  @Override
  public Uni<EventLog> findById(Long id, String tenancyId) {
    EventLog result = delegate.findById(id, tenancyId);
    return result == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(result);
  }

  @Override
  public Uni<List<EventLog>> findSchedulingEvents(
      UUID caseId, String workerId, Instant after, String tenancyId) {
    return Uni.createFrom().item(delegate.findSchedulingEvents(caseId, workerId, after, tenancyId));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndTypes(
      UUID caseId, Collection<CaseHubEventType> types, String tenancyId) {
    return Uni.createFrom().item(delegate.findByCaseAndTypes(caseId, types, tenancyId));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type, String tenancyId) {
    return Uni.createFrom()
        .item(delegate.findByCaseAndWorkerAndType(caseId, workerId, type, tenancyId));
  }

  @Override
  public Uni<List<EventLog>> findByWorkerAndType(
      String workerId, CaseHubEventType type, String tenancyId) {
    return Uni.createFrom().item(delegate.findByWorkerAndType(workerId, type, tenancyId));
  }

  @Override
  public Uni<List<EventLog>> findByCaseWithFilters(
      UUID caseId,
      Collection<CaseHubEventType> eventTypes,
      Collection<EventStreamType> streamTypes,
      String tenancyId) {
    return Uni.createFrom()
        .item(delegate.findByCaseWithFilters(caseId, eventTypes, streamTypes, tenancyId));
  }

  // ── CrossTenantEventLogRepository methods ────────────────────────────────

  @Override
  public Uni<List<EventLog>> findByTypes(Collection<CaseHubEventType> types) {
    return Uni.createFrom().item(crossTenantDelegate.findByTypes(types));
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndTypes(UUID caseId, Collection<CaseHubEventType> types) {
    return Uni.createFrom().item(crossTenantDelegate.findByCaseAndTypes(caseId, types));
  }

  @Override
  public Uni<List<String>> findSubmittedWorkWithoutCompletion() {
    return Uni.createFrom().item(crossTenantDelegate.findSubmittedWorkWithoutCompletion());
  }

  @Override
  public Uni<EventLog> findById(Long id) {
    EventLog result = crossTenantDelegate.findById(id);
    return result == null ? Uni.createFrom().nullItem() : Uni.createFrom().item(result);
  }

  @Override
  public Uni<List<EventLog>> findByCaseAndWorkerAndType(
      UUID caseId, String workerId, CaseHubEventType type) {
    return Uni.createFrom()
        .item(crossTenantDelegate.findByCaseAndWorkerAndType(caseId, workerId, type));
  }

  @Override
  public Uni<List<EventLog>> findByWorkerAndTypeAcrossTenants(
      String workerId, CaseHubEventType type) {
    return Uni.createFrom()
        .item(crossTenantDelegate.findByWorkerAndTypeAcrossTenants(workerId, type));
  }
}
