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
package io.casehub.engine.watchdog;

import io.casehub.api.model.StallRecoveryContext;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.recovery.StallRecoveryHandler;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Comparator;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StallRecoveryDispatchHandler {

  private static final Logger LOG = Logger.getLogger(StallRecoveryDispatchHandler.class);

  @Inject StallRecoveryHandler stallRecoveryHandler;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject Instance<PlanItemStore> planItemStore;

  @ConsumeEvent(value = WatchdogAlertObserver.STALL_RECOVERY_ADDRESS, blocking = true)
  public void onStallRecovery(StallRecoveryContext context) {
    CaseInstance instance = caseInstanceCache.get(context.caseId());
    if (instance == null || instance.getState().isTerminal()) {
      LOG.debugf("Stall recovery skipped — case %s not found or terminal", context.caseId());
      return;
    }

    StallRecoveryContext enriched = resolveBinding(context);
    boolean handled = stallRecoveryHandler.handleStall(enriched);
    LOG.infof(
        "Stall recovery for case %s condition %s — handled=%s",
        context.caseId(), context.conditionType(), handled);
  }

  StallRecoveryContext resolveBinding(StallRecoveryContext ctx) {
    if (!planItemStore.isResolvable() || ctx.affectedAgentIds().isEmpty()) return ctx;

    PlanItemStore store = planItemStore.get();
    var match =
        store.findByCaseId(ctx.caseId(), ctx.tenancyId()).stream()
            .filter(r -> r.status() == TaskStatus.RUNNING)
            .filter(r -> ctx.affectedAgentIds().contains(r.executorName()))
            .max(Comparator.comparing(PlanItemRecord::createdAt));

    return match.map(r -> ctx.withBinding(r.bindingName(), r.planItemId())).orElse(ctx);
  }
}
