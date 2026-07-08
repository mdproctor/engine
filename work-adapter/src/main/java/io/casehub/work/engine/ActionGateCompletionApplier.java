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
package io.casehub.work.engine;

import io.casehub.engine.common.internal.event.ActionGateApprovedEvent;
import io.casehub.engine.common.internal.event.ActionGateExpiredEvent;
import io.casehub.engine.common.internal.event.ActionGateRejectedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Translates terminal WorkItem lifecycle events for gate callerRefs into gate resolution events on
 * the engine event bus.
 *
 * <p>Called by {@link WorkItemLifecycleAdapter} when a gate callerRef ({@code
 * "case:{caseId}/gate:{gateId}"}) is detected. Routes before the blackboard guard — gate WorkItems
 * have no associated PlanItem.
 *
 * <p>Resolution mapping:
 *
 * <ul>
 *   <li>COMPLETED → {@link ActionGateApprovedEvent} on {@link
 *       EventBusAddresses#ACTION_GATE_APPROVED}
 *   <li>REJECTED or CANCELLED → {@link ActionGateRejectedEvent} on {@link
 *       EventBusAddresses#ACTION_GATE_REJECTED}
 *   <li>EXPIRED → {@link ActionGateExpiredEvent} on {@link EventBusAddresses#ACTION_GATE_EXPIRED}
 * </ul>
 *
 * <p>{@code approvedBy}/{@code rejectedBy} is sourced from {@code WorkItem.assigneeId} — the user
 * who claimed and completed the WorkItem. If null (completed without explicit claim), falls back to
 * parsing {@code resolution.completedBy}; otherwise null.
 */
@ApplicationScoped
public class ActionGateCompletionApplier {

  private static final Logger LOG = Logger.getLogger(ActionGateCompletionApplier.class);
  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  @Inject EventBus eventBus;

  public void apply(
      final GateCallerRef gateRef, final WorkItemStatus status, final WorkItemRef ref) {
    switch (status) {
      case COMPLETED -> handleApproved(gateRef, ref);
      case REJECTED, CANCELLED, OBSOLETE -> handleRejected(gateRef, ref);
      case EXPIRED, FAULTED -> handleExpired(gateRef);
      default ->
          LOG.debugf(
              "Gate WorkItem status %s for caseId=%s gateId=%d — no gate event published",
              status, gateRef.caseId(), gateRef.gateId());
    }
  }

  private void handleApproved(final GateCallerRef gateRef, final WorkItemRef ref) {
    final String approvedBy = resolveActorId(ref);
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_APPROVED,
        new ActionGateApprovedEvent(
            gateRef.caseId(), gateRef.gateId(), ref != null ? ref.resolution() : null, approvedBy));
    LOG.infof(
        "Gate approved: caseId=%s gateId=%d approvedBy=%s",
        gateRef.caseId(), gateRef.gateId(), approvedBy);
  }

  private void handleRejected(final GateCallerRef gateRef, final WorkItemRef ref) {
    final String rejectedBy = resolveActorId(ref);
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_REJECTED,
        new ActionGateRejectedEvent(
            gateRef.caseId(), gateRef.gateId(), ref != null ? ref.resolution() : null, rejectedBy));
    LOG.infof(
        "Gate rejected: caseId=%s gateId=%d rejectedBy=%s",
        gateRef.caseId(), gateRef.gateId(), rejectedBy);
  }

  private void handleExpired(final GateCallerRef gateRef) {
    eventBus.publish(
        EventBusAddresses.ACTION_GATE_EXPIRED,
        new ActionGateExpiredEvent(gateRef.caseId(), gateRef.gateId()));
    LOG.infof("Gate expired: caseId=%s gateId=%d", gateRef.caseId(), gateRef.gateId());
  }

  private static String resolveActorId(final WorkItemRef ref) {
    if (ref == null) return null;
    if (ref.assigneeId() != null) return ref.assigneeId();
    if (ref.resolution() != null) {
      try {
        final com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(ref.resolution());
        final com.fasterxml.jackson.databind.JsonNode completedBy = node.get("completedBy");
        if (completedBy != null && !completedBy.isNull()) return completedBy.asText();
      } catch (final Exception e) {
        // Resolution is not JSON or completedBy is absent — return null
      }
    }
    return null;
  }
}
