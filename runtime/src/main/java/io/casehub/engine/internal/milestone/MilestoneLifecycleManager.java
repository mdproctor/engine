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
package io.casehub.engine.internal.milestone;

import com.fasterxml.jackson.databind.JsonNode;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStartFrom;
import io.casehub.api.model.SlaStatus;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.engine.common.internal.event.CaseContextChangedEvent;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.event.MilestoneCompletedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.ExpressionEngineRegistry;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Orchestrates milestone lifecycle state transitions by evaluating criteria on context changes.
 *
 * <p>Subscribes to {@link CaseContextChangedEvent} and for each milestone: - If PENDING: evaluate
 * entryCriteria → publish {@link MilestoneActivatedEvent} if true - If ACTIVE: evaluate
 * completionCriteria → publish {@link MilestoneCompletedEvent} if true
 *
 * <p>State is derived from EventLog (no separate persistence).
 */
@ApplicationScoped
public class MilestoneLifecycleManager {

  private static final Logger LOG = Logger.getLogger(MilestoneLifecycleManager.class);

  private static final EnumSet<CaseHubEventType> MILESTONE_LIFECYCLE_EVENTS =
      EnumSet.of(
          CaseHubEventType.MILESTONE_ACTIVATED,
          CaseHubEventType.MILESTONE_COMPLETED,
          CaseHubEventType.MILESTONE_SLA_VIOLATED);

  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @ConsumeEvent(value = EventBusAddresses.CONTEXT_CHANGED)
  public Uni<Void> onContextChanged(CaseContextChangedEvent event) {
    CaseInstance caseInstance = event.instance();
    CaseMetaModel caseMetaModel = caseInstance.getCaseMetaModel();

    LOG.debugf(
        "CONTEXT_CHANGED received for case %s, state=%s",
        caseInstance.getUuid(), caseInstance.getState());

    // Skip if no metamodel attached
    if (caseMetaModel == null) {
      LOG.debugf(
          "Case %s has no CaseMetaModel, skipping milestone evaluation", caseInstance.getUuid());
      return Uni.createFrom().voidItem();
    }

    // Only evaluate milestones for RUNNING cases
    if (!caseInstance.getState().equals(CaseStatus.RUNNING)) {
      LOG.debugf("Case %s not RUNNING, skipping milestone evaluation", caseInstance.getUuid());
      return Uni.createFrom().voidItem();
    }

    CaseDefinition definition = caseDefinitionRegistry.getCaseDefinition(caseMetaModel);

    if (definition == null
        || definition.getMilestones() == null
        || definition.getMilestones().isEmpty()) {
      LOG.debugf("Case %s has no milestones, skipping evaluation", caseInstance.getUuid());
      return Uni.createFrom().voidItem();
    }

    LOG.debugf(
        "Evaluating %d milestone(s) for case %s",
        definition.getMilestones().size(), caseInstance.getUuid());

    List<Uni<Void>> evaluations =
        definition.getMilestones().stream()
            .map(milestone -> evaluateMilestone(caseInstance, milestone))
            .toList();

    return Uni.combine()
        .all()
        .unis(evaluations)
        .discardItems()
        .onFailure()
        .invoke(
            throwable ->
                LOG.errorf(
                    throwable,
                    "Failed to evaluate milestones for case %s",
                    caseInstance.getUuid()));
  }

  private Uni<Void> evaluateMilestone(CaseInstance caseInstance, Milestone milestone) {
    return getCurrentLifecycleStatus(caseInstance.getUuid(), milestone.getName())
        .chain(
            currentStatus -> {
              LOG.debugf(
                  "Milestone '%s' current status: %s (case %s)",
                  milestone.getName(), currentStatus, caseInstance.getUuid());

              if (currentStatus == MilestoneLifecycleStatus.PENDING) {
                return evaluateEntryCriteria(caseInstance, milestone);
              } else if (currentStatus == MilestoneLifecycleStatus.ACTIVE) {
                return evaluateCompletionCriteria(caseInstance, milestone);
              } else {
                // COMPLETED, FAILED, CANCELLED — no further transitions
                return Uni.createFrom().voidItem();
              }
            });
  }

  private Uni<Void> evaluateEntryCriteria(CaseInstance caseInstance, Milestone milestone) {
    CaseContext context = caseInstance.getCaseContext();
    boolean met = expressionEngineRegistry.evaluate(milestone.getEntryCriteria(), context);

    LOG.debugf(
        "Milestone '%s' entryCriteria '%s' evaluation result: %s (case %s)",
        milestone.getName(), milestone.getEntryCriteria(), met, caseInstance.getUuid());

    if (!met) {
      return Uni.createFrom().voidItem();
    }

    LOG.infof("Milestone '%s' ACTIVATED (entryCriteria met)", milestone.getName());

    Instant activatedAt = Instant.now();

    return calculateSlaDeadline(caseInstance, milestone, activatedAt)
        .chain(
            slaDeadline ->
                Uni.createFrom()
                    .item(
                        new MilestoneActivatedEvent(
                            caseInstance, milestone, activatedAt, slaDeadline))
                    .invoke(e -> eventBus.publish(EventBusAddresses.MILESTONE_ACTIVATED, e))
                    .replaceWithVoid());
  }

  private Uni<Void> evaluateCompletionCriteria(CaseInstance caseInstance, Milestone milestone) {
    CaseContext context = caseInstance.getCaseContext();
    boolean met = expressionEngineRegistry.evaluate(milestone.getCompletionCriteria(), context);

    if (!met) {
      return Uni.createFrom().voidItem();
    }

    LOG.infof("Milestone '%s' COMPLETED (completionCriteria met)", milestone.getName());

    return getCurrentSlaStatus(caseInstance, milestone.getName())
        .chain(
            slaStatus -> {
              Instant completedAt = Instant.now();
              return Uni.createFrom()
                  .item(
                      new MilestoneCompletedEvent(caseInstance, milestone, completedAt, slaStatus))
                  .invoke(e -> eventBus.publish(EventBusAddresses.MILESTONE_COMPLETED, e))
                  .replaceWithVoid();
            });
  }

  private Uni<EventLog> findLastMilestoneEvent(UUID caseId, String milestoneName) {
    return eventLogRepository
        .findByCaseAndTypes(caseId, MILESTONE_LIFECYCLE_EVENTS)
        .map(
            events ->
                events.stream()
                    .filter(e -> milestoneName.equals(e.getPayload().get("milestoneName").asText()))
                    .max(Comparator.comparing(EventLog::getSeq))
                    .orElse(null));
  }

  private Uni<MilestoneLifecycleStatus> getCurrentLifecycleStatus(
      UUID caseId, String milestoneName) {
    return findLastMilestoneEvent(caseId, milestoneName)
        .map(
            lastEvent -> {
              if (lastEvent == null) {
                return MilestoneLifecycleStatus.PENDING;
              }

              return switch (lastEvent.getEventType()) {
                case MILESTONE_ACTIVATED -> MilestoneLifecycleStatus.ACTIVE;
                case MILESTONE_COMPLETED -> MilestoneLifecycleStatus.COMPLETED;
                case MILESTONE_SLA_VIOLATED ->
                    MilestoneLifecycleStatus
                        .ACTIVE; // TODO maybe it must be configurable whether SLA violation
                // deactivates the milestone or not?
                default -> MilestoneLifecycleStatus.PENDING;
              };
            });
  }

  private Uni<SlaStatus> getCurrentSlaStatus(CaseInstance caseInstance, String milestoneName) {
    return findLastMilestoneEvent(caseInstance.getUuid(), milestoneName)
        .map(
            lastEvent -> {
              if (lastEvent == null) {
                return SlaStatus.NOT_STARTED;
              }

              JsonNode payload = lastEvent.getPayload();
              JsonNode slaStatusNode = payload.get("slaStatus");
              if (slaStatusNode == null) {
                return SlaStatus.NOT_STARTED;
              }
              String slaStatusStr = slaStatusNode.asText();
              return SlaStatus.valueOf(slaStatusStr);
            });
  }

  private Uni<Instant> calculateSlaDeadline(
      CaseInstance caseInstance, Milestone milestone, Instant activatedAt) {
    Duration slaDuration = milestone.getSlaDuration();
    if (slaDuration == null) {
      return Uni.createFrom().nullItem();
    }

    SlaStartFrom slaStartFrom = milestone.getSlaStartFrom();
    if (slaStartFrom == SlaStartFrom.MILESTONE_ACTIVATED) {
      return Uni.createFrom().item(activatedAt.plus(slaDuration));
    }

    if (slaStartFrom == SlaStartFrom.CASE_CREATED) {
      // Query EventLog for CASE_STARTED event to get creation timestamp
      return eventLogRepository
          .findByCaseAndTypes(caseInstance.getUuid(), EnumSet.of(CaseHubEventType.CASE_STARTED))
          .map(
              events -> {
                EventLog caseStartedEvent =
                    events.stream()
                        .filter(e -> e.getEventType() == CaseHubEventType.CASE_STARTED)
                        .findFirst()
                        .orElseThrow(
                            () ->
                                new IllegalStateException(
                                    "CASE_STARTED event not found for case: "
                                        + caseInstance.getUuid()));
                return caseStartedEvent.getTimestamp().plus(slaDuration);
              });
    }

    return Uni.createFrom()
        .failure(
            new UnsupportedOperationException(
                "SlaStartFrom." + slaStartFrom + " not yet implemented"));
  }
}
