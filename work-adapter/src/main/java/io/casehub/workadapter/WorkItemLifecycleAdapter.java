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
package io.casehub.workadapter;

import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.plan.PlanItem;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.work.api.GroupStatus;
import io.casehub.work.api.WorkItemGroupLifecycleEvent;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Translates terminal quarkus-work {@link WorkItemLifecycleEvent}s and M-of-N {@link
 * WorkItemGroupLifecycleEvent}s into CaseHub PlanItem transitions and fires {@code CONTEXT_CHANGED}
 * to trigger engine re-evaluation.
 *
 * <p>Choreography path: the engine's binding evaluator picks up the next step automatically once
 * the PlanItem status changes and the context-changed signal arrives. Refs casehubio/work#136.
 *
 * <p>Only processes events whose {@code callerRef} matches the CaseHub format {@code
 * case:{caseId}/pi:{planItemId}} — other WorkItems are ignored.
 *
 * <p>ESCALATED is not terminal: the WorkItem re-enters PENDING with new candidate groups; the
 * PlanItem stays in its current state. The adapter writes a {@code workItemEscalated} signal to the
 * case context, allowing definitions to react via {@code contextChange(".workItemEscalated")}. Refs
 * engine#338, engine#400.
 */
@ApplicationScoped
public class WorkItemLifecycleAdapter {

  private static final Logger LOG = Logger.getLogger(WorkItemLifecycleAdapter.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Inject BlackboardRegistry registry;

  @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

  @Inject EventBus eventBus;

  @Inject PlanItemCompletionApplier applier;

  @Inject ActionGateCompletionApplier gateApplier;

  public void onWorkItemLifecycle(@ObservesAsync WorkItemLifecycleEvent event) {
    WorkItemStatus status = event.status();

    if (status == WorkItemStatus.ESCALATED) {
      handleEscalation(event);
      return;
    }

    if (status != WorkItemStatus.COMPLETED
        && status != WorkItemStatus.REJECTED
        && status != WorkItemStatus.CANCELLED
        && status != WorkItemStatus.EXPIRED) return;

    if (!(event.source() instanceof WorkItem workItem)) return;

    CallerRef ref = CallerRef.parse(workItem.callerRef);
    if (ref == null) return;

    // Gate WorkItems bypass the blackboard guard — they have no PlanItem. Gate routing
    // is handled by ActionGateCompletionApplier (wired in task #13 / engine#402).
    if (ref instanceof GateCallerRef gateRef) {
      routeGate(gateRef, status, workItem);
      return;
    }

    if (!(ref instanceof PlanItemCallerRef piRef)) return;

    if (registry.get(piRef.caseId()).isEmpty()) {
      LOG.debugf(
          "No CasePlanModel for caseId=%s — case may have completed or not use blackboard",
          piRef.caseId());
      return;
    }

    applier.apply(piRef.caseId(), piRef.planItemId(), status, workItem);
  }

  public void onWorkItemGroupLifecycle(@ObservesAsync WorkItemGroupLifecycleEvent event) {
    GroupStatus status = event.groupStatus();
    if (status != GroupStatus.COMPLETED && status != GroupStatus.REJECTED) return;

    CallerRef ref = CallerRef.parse(event.callerRef());
    if (!(ref instanceof PlanItemCallerRef piRef)) return;

    CasePlanModel plan = registry.get(piRef.caseId()).orElse(null);
    if (plan == null) {
      LOG.debugf("No CasePlanModel for caseId=%s — group outcome ignored", piRef.caseId());
      return;
    }

    PlanItem item = plan.getPlanItem(piRef.planItemId()).orElse(null);
    if (item == null) {
      LOG.warnf(
          "PlanItem %s not found in case %s for group outcome", piRef.planItemId(), piRef.caseId());
      return;
    }

    if (!applyGroupStatus(item, status)) return;

    CaseInstance instance =
        caseInstanceRepository.findByUuid(piRef.caseId()).await().atMost(TIMEOUT);
    if (instance == null) {
      LOG.warnf(
          "CaseInstance not found for caseId=%s — cannot fire CONTEXT_CHANGED", piRef.caseId());
      return;
    }

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));
  }

  private boolean applyGroupStatus(PlanItem item, GroupStatus status) {
    try {
      switch (status) {
        case COMPLETED -> item.markCompleted();
        case REJECTED -> item.markRejected();
        default -> {
          return false;
        }
      }
      return true;
    } catch (IllegalStateException e) {
      LOG.warnf(
          "Cannot transition PlanItem %s (current=%s) for GroupStatus %s: %s",
          item.getPlanItemId(), item.getStatus(), status, e.getMessage());
      return false;
    }
  }

  /**
   * Writes a {@code workItemEscalated} signal to the case context when a WorkItem escalates.
   *
   * <p>ESCALATED is non-terminal: the WorkItem re-enters PENDING with new candidate groups; the
   * PlanItem status does not change. Case definitions that need to react to escalation (e.g. notify
   * a supervisor, adjust scope) bind on {@code contextChange(".workItemEscalated")}.
   *
   * <p>Follows the same pattern as {@code QhorusMessageSignalBridge}: external events write to a
   * named context path; definitions bind on it. Refs engine#400.
   */
  private void handleEscalation(WorkItemLifecycleEvent event) {
    if (!(event.source() instanceof WorkItem workItem)) return;

    CallerRef ref = CallerRef.parse(workItem.callerRef);
    if (!(ref instanceof PlanItemCallerRef piRef)) return;

    CasePlanModel plan = registry.get(piRef.caseId()).orElse(null);
    if (plan == null) {
      LOG.debugf("No CasePlanModel for caseId=%s — escalation signal skipped", piRef.caseId());
      return;
    }

    PlanItem item = plan.getPlanItem(piRef.planItemId()).orElse(null);
    if (item == null) {
      LOG.warnf(
          "PlanItem %s not found in case %s — escalation signal skipped",
          piRef.planItemId(), piRef.caseId());
      return;
    }

    CaseInstance instance =
        caseInstanceRepository.findByUuid(piRef.caseId()).await().atMost(TIMEOUT);
    if (instance == null) {
      LOG.warnf("CaseInstance not found for caseId=%s — escalation signal skipped", piRef.caseId());
      return;
    }

    List<String> newGroups =
        workItem.candidateGroups != null
            ? List.of(workItem.candidateGroups.split("\\s*,\\s*"))
            : List.of();

    // Overwrites any previous escalation signal for this case — last escalation wins.
    // Same semantics as QhorusMessageSignalBridge (single fixed key, not a queue).
    instance
        .getCaseContext()
        .set(
            "workItemEscalated",
            Map.of(
                "workItemId", workItem.id.toString(),
                "newGroups", newGroups,
                "bindingName", item.getBindingName()));

    eventBus.publish(
        EventBusAddresses.CONTEXT_CHANGED,
        new CaseContextChangedEvent(instance, instance.getCaseContext().asJsonNode()));

    LOG.infof(
        "WorkItem escalation signal: caseId=%s planItemId=%s bindingName=%s newGroups=%s",
        piRef.caseId(), piRef.planItemId(), item.getBindingName(), newGroups);
  }

  private void routeGate(
      final GateCallerRef gateRef, final WorkItemStatus status, final WorkItem workItem) {
    gateApplier.apply(gateRef, status, workItem);
  }
}
