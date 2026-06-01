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
package io.casehub.actorstate;

import io.casehub.platform.api.actor.ActorStateAccumulator;
import io.casehub.platform.api.actor.ActorStateContributor;
import io.casehub.work.api.WorkItemCallerRef;
import io.casehub.work.runtime.model.WorkItemStatus;
import io.casehub.work.runtime.repository.WorkItemQuery;
import io.casehub.work.runtime.repository.WorkItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Contributes active WorkItems from casehub-work.
 *
 * <p>Filters to ASSIGNED, IN_PROGRESS, SUSPENDED: PENDING excluded (actor is a candidate but hasn't
 * claimed — not active work). SUSPENDED included (actor is still obligated to complete — paused,
 * not released).
 */
@ApplicationScoped
public class WorkActorStateContributor implements ActorStateContributor {

  @Inject WorkItemStore workItemStore;

  @Override
  public String sourceName() {
    return "work";
  }

  @Override
  public void contribute(final String actorId, final ActorStateAccumulator acc) {
    // Atomic: scan() returns eager List<WorkItem> — collect fully before calling acc.
    // inbox(actorId, null, null): first null = candidateGroups, second = candidateUserId.
    final var items =
        workItemStore.scan(
            WorkItemQuery.inbox(actorId, null, null).toBuilder()
                .statusIn(
                    List.of(
                        WorkItemStatus.ASSIGNED,
                        WorkItemStatus.IN_PROGRESS,
                        WorkItemStatus.SUSPENDED))
                .build());
    items.forEach(
        wi ->
            acc.workItem(
                wi.id,
                wi.title,
                wi.status != null ? wi.status.name() : null,
                wi.category,
                WorkItemCallerRef.parseCaseId(wi.callerRef)));
  }
}
