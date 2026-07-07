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
package io.casehub.engine.internal.routing;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Evicts CBR retrieval cache entries when a case reaches a terminal state. Prevents unbounded
 * memory growth for {@link io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming#CASE_LIFETIME}
 * caching.
 *
 * <p>Follows the same pattern as {@code CaseEvictionHandler} in the blackboard module.
 *
 * @see CbrRetrievalService#evict(java.util.UUID)
 */
@ApplicationScoped
public class CbrCacheEvictionHandler {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of(CaseStatus.COMPLETED.name(), CaseStatus.FAULTED.name(), CaseStatus.CANCELLED.name());

  private final CbrRetrievalService cbrRetrievalService;

  @Inject
  public CbrCacheEvictionHandler(CbrRetrievalService cbrRetrievalService) {
    this.cbrRetrievalService = cbrRetrievalService;
  }

  @ConsumeEvent(value = EventBusAddresses.CASE_STATUS_CHANGED, blocking = true)
  public Uni<Void> onCaseStatusChanged(CaseStatusChanged event) {
    if (TERMINAL_STATUSES.contains(event.newStatus())) {
      cbrRetrievalService.evict(event.instance().getUuid());
    }
    return Uni.createFrom().voidItem();
  }
}
