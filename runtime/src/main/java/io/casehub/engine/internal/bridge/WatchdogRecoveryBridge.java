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
package io.casehub.engine.internal.bridge;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.WatchdogResponseAction;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.WorkflowExecutionCompleted;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.PlanItemRecord;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.scheduler.WorkerExecutionManager;
import io.casehub.qhorus.api.watchdog.WatchdogAlertEvent;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WatchdogRecoveryBridge {

  private static final Logger LOG = Logger.getLogger(WatchdogRecoveryBridge.class);

  private static final Set<WatchdogConditionType> WORKER_HUNG_CONDITIONS =
      Set.of(
          WatchdogConditionType.AGENT_STALE,
          WatchdogConditionType.BARRIER_STUCK,
          WatchdogConditionType.LOOP_DETECTED,
          WatchdogConditionType.CONVERSATION_STALL,
          WatchdogConditionType.ECHO_CHAMBER,
          WatchdogConditionType.CIRCULAR_DELEGATION);

  @Inject EventBus eventBus;
  @Inject CaseInstanceCache caseInstanceCache;
  @Inject CaseDefinitionRegistry definitionRegistry;
  @Inject PlanItemStore planItemStore;
  @Inject WorkerExecutionManager executionManager;

  void onWatchdogAlert(@ObservesAsync WatchdogAlertEvent event) {
    List<String> agentIds = event.context().affectedAgentIds();
    if (agentIds.isEmpty()) {
      LOG.debugf("Watchdog %s — no affected agents, skipping recovery", event.conditionType());
      return;
    }

    if (event.caseId() != null) {
      handleForCase(event, event.caseId(), agentIds);
    } else {
      for (String agentId : agentIds) {
        List<UUID> caseIds = executionManager.getActiveCaseIds(agentId);
        for (UUID caseId : caseIds) {
          handleForCase(event, caseId, List.of(agentId));
        }
      }
    }
  }

  private void handleForCase(WatchdogAlertEvent event, UUID caseId, List<String> agentIds) {
    CaseInstance instance = caseInstanceCache.get(caseId);
    if (instance == null) {
      LOG.debugf("Watchdog alert for unknown case %s — skipping", caseId);
      return;
    }

    CaseDefinition definition = definitionRegistry.getCaseDefinition(instance.getCaseMetaModel());
    if (definition == null) return;

    WatchdogResponseAction action = resolveAction(event.conditionType(), definition);
    if (action == WatchdogResponseAction.IGNORE) {
      LOG.debugf("Watchdog %s on case %s — policy IGNORE", event.conditionType(), caseId);
      return;
    }

    List<PlanItemRecord> planItems = planItemStore.findByCaseId(caseId, instance.tenancyId);

    for (String agentId : agentIds) {
      for (PlanItemRecord pi : planItems) {
        if (!isResourceConsuming(pi.status())) continue;
        if (pi.executorName() == null || !pi.executorName().equals(agentId)) continue;

        Worker worker = resolveWorker(definition, agentId);
        WorkerOutcome<?> outcome =
            new WorkerOutcome.Expired<>("Watchdog: " + event.conditionType());

        LOG.infof(
            "Watchdog %s → synthetic Expired for case=%s worker='%s' binding='%s'",
            event.conditionType(), caseId, agentId, pi.bindingName());

        eventBus.publish(
            EventBusAddresses.WORKER_EXECUTION_FINISHED,
            new WorkflowExecutionCompleted(
                instance, worker, null, Map.of(), pi.bindingName(), outcome));
      }
    }
  }

  private WatchdogResponseAction resolveAction(
      WatchdogConditionType conditionType, CaseDefinition definition) {
    var policy = definition.getWatchdogPolicy();
    if (policy != null && policy.containsKey(conditionType)) {
      return policy.get(conditionType);
    }
    return WORKER_HUNG_CONDITIONS.contains(conditionType)
        ? WatchdogResponseAction.CANCEL_AFFECTED
        : WatchdogResponseAction.IGNORE;
  }

  private Worker resolveWorker(CaseDefinition definition, String workerName) {
    if (definition.getWorkers() != null) {
      for (Worker w : definition.getWorkers()) {
        if (w.name().equals(workerName)) return w;
      }
    }
    return Worker.builder().name(workerName).capabilityNames(Set.of()).noFunction().build();
  }

  private static boolean isResourceConsuming(TaskStatus status) {
    return status == TaskStatus.RUNNING
        || status == TaskStatus.DISPATCHING
        || status == TaskStatus.DELEGATED;
  }
}
