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
package io.casehub.engine.planning.handler;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.planning.registry.BlackboardRegistry;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Evicts {@link io.casehub.engine.planning.plan.CasePlanModel} from {@link BlackboardRegistry} when
 * a case reaches a terminal state. Prevents unbounded memory growth. See casehubio/engine#76.
 */
@ApplicationScoped
public class CaseEvictionHandler {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of(CaseStatus.COMPLETED.name(), CaseStatus.FAULTED.name(), CaseStatus.CANCELLED.name());

  private final BlackboardRegistry registry;

  @Inject
  public CaseEvictionHandler(BlackboardRegistry registry) {
    this.registry = registry;
  }

  @ConsumeEvent(EventBusAddresses.CASE_STATUS_CHANGED)
  @RunOnVirtualThread
  public void onCaseStatusChanged(CaseStatusChanged event) {
    if (TERMINAL_STATUSES.contains(event.newStatus())) {
      registry.evict(event.instance().getUuid());
    }
  }
}
