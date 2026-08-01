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

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngineRegistry;
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
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Orchestrates milestone lifecycle state transitions by evaluating criteria on context changes.
 *
 * <p>Subscribes to {@link CaseContextChangedEvent} and for each milestone: - If PENDING: evaluate
 * entryCriteria → publish {@link MilestoneActivatedEvent} if true - If ACTIVE: evaluate
 * completionCriteria → publish {@link MilestoneCompletedEvent} if true
 *
 * <p>State is read from CaseContext ({@code milestones.<name>.lifecycleStatus}).
 */
@ApplicationScoped
public class MilestoneLifecycleManager {

  private static final Logger LOG = Logger.getLogger(MilestoneLifecycleManager.class);

  @Inject EventLogRepository eventLogRepository;
  @Inject EventBus eventBus;
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject ExpressionEngineRegistry expressionEngineRegistry;

  @ConsumeEvent(value = EventBusAddresses.CONTEXT_CHANGED)
  @RunOnVirtualThread
  void onContextChanged(CaseContextChangedEvent event) {
    try {
      CaseInstance caseInstance = event.instance();
      CaseMetaModel caseMetaModel = caseInstance.getCaseMetaModel();

      LOG.debugf(
          "CONTEXT_CHANGED received for case %s, state=%s",
          caseInstance.getUuid(), caseInstance.getState());

      // Skip if no metamodel attached
      if (caseMetaModel == null) {
        LOG.debugf(
            "Case %s has no CaseMetaModel, skipping milestone evaluation", caseInstance.getUuid());
        return;
      }

      // Only evaluate milestones for RUNNING cases
      if (!caseInstance.getState().equals(CaseStatus.RUNNING)) {
        LOG.debugf("Case %s not RUNNING, skipping milestone evaluation", caseInstance.getUuid());
        return;
      }

      CaseDefinition definition = caseDefinitionRegistry.getCaseDefinition(caseMetaModel);

      if (definition == null
          || definition.getMilestones() == null
          || definition.getMilestones().isEmpty()) {
        LOG.debugf("Case %s has no milestones, skipping evaluation", caseInstance.getUuid());
        return;
      }

      LOG.debugf(
          "Evaluating %d milestone(s) for case %s",
          definition.getMilestones().size(), caseInstance.getUuid());

      for (Milestone milestone : definition.getMilestones()) {
        evaluateMilestone(caseInstance, milestone);
      }
    } catch (Exception e) {
      LOG.errorf(e, "Failed to evaluate milestones for case %s", event.instance().getUuid());
    }
  }

  private void evaluateMilestone(CaseInstance caseInstance, Milestone milestone) {
    MilestoneLifecycleStatus currentStatus =
        getCurrentLifecycleStatus(caseInstance, milestone.getName());

    LOG.debugf(
        "Milestone '%s' current status: %s (case %s)",
        milestone.getName(), currentStatus, caseInstance.getUuid());

    if (currentStatus == MilestoneLifecycleStatus.PENDING) {
      evaluateEntryCriteria(caseInstance, milestone);
    } else if (currentStatus == MilestoneLifecycleStatus.ACTIVE) {
      evaluateCompletionCriteria(caseInstance, milestone);
    }
    // COMPLETED — no further transitions
  }

  private void evaluateEntryCriteria(CaseInstance caseInstance, Milestone milestone) {
    CaseContext context = caseInstance.getCaseContext();
    boolean met = expressionEngineRegistry.evaluate(milestone.getEntryCriteria(), context);

    LOG.debugf(
        "Milestone '%s' entryCriteria '%s' evaluation result: %s (case %s)",
        milestone.getName(), milestone.getEntryCriteria(), met, caseInstance.getUuid());

    if (!met) {
      return;
    }

    LOG.infof("Milestone '%s' ACTIVATED (entryCriteria met)", milestone.getName());

    Instant activatedAt = Instant.now();
    Instant slaDeadline = calculateSlaDeadline(caseInstance, milestone, activatedAt);

    eventBus.publish(
        EventBusAddresses.MILESTONE_ACTIVATED,
        new MilestoneActivatedEvent(caseInstance, milestone, activatedAt, slaDeadline));
  }

  private void evaluateCompletionCriteria(CaseInstance caseInstance, Milestone milestone) {
    CaseContext context = caseInstance.getCaseContext();
    boolean met = expressionEngineRegistry.evaluate(milestone.getCompletionCriteria(), context);

    if (!met) {
      return;
    }

    LOG.infof("Milestone '%s' COMPLETED (completionCriteria met)", milestone.getName());

    SlaStatus slaStatus = getCurrentSlaStatus(caseInstance, milestone.getName());
    Instant completedAt = Instant.now();

    eventBus.publish(
        EventBusAddresses.MILESTONE_COMPLETED,
        new MilestoneCompletedEvent(caseInstance, milestone, completedAt, slaStatus));
  }

  @SuppressWarnings("unchecked")
  private MilestoneLifecycleStatus getCurrentLifecycleStatus(
      CaseInstance caseInstance, String milestoneName) {
    Map<String, Object> milestones =
        (Map<String, Object>) caseInstance.getCaseContext().get("milestones");
    if (milestones == null) {
      return MilestoneLifecycleStatus.PENDING;
    }
    Map<String, Object> milestone = (Map<String, Object>) milestones.get(milestoneName);
    if (milestone == null) {
      return MilestoneLifecycleStatus.PENDING;
    }
    Object statusObj = milestone.get("lifecycleStatus");
    if (statusObj == null) {
      return MilestoneLifecycleStatus.PENDING;
    }
    try {
      return MilestoneLifecycleStatus.valueOf(statusObj.toString());
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Unknown milestone lifecycle status '%s' for milestone '%s', treating as PENDING",
          statusObj, milestoneName);
      return MilestoneLifecycleStatus.PENDING;
    }
  }

  @SuppressWarnings("unchecked")
  private SlaStatus getCurrentSlaStatus(CaseInstance caseInstance, String milestoneName) {
    Map<String, Object> milestones =
        (Map<String, Object>) caseInstance.getCaseContext().get("milestones");
    if (milestones == null) {
      return SlaStatus.NOT_STARTED;
    }
    Map<String, Object> milestone = (Map<String, Object>) milestones.get(milestoneName);
    if (milestone == null) {
      return SlaStatus.NOT_STARTED;
    }
    Object statusObj = milestone.get("slaStatus");
    if (statusObj == null) {
      return SlaStatus.NOT_STARTED;
    }
    try {
      return SlaStatus.valueOf(statusObj.toString());
    } catch (IllegalArgumentException e) {
      LOG.warnf(
          "Unknown SLA status '%s' for milestone '%s', treating as NOT_STARTED",
          statusObj, milestoneName);
      return SlaStatus.NOT_STARTED;
    }
  }

  private Instant calculateSlaDeadline(
      CaseInstance caseInstance, Milestone milestone, Instant activatedAt) {
    Duration slaDuration = milestone.getSlaDuration();
    if (slaDuration == null) {
      return null;
    }

    SlaStartFrom slaStartFrom = milestone.getSlaStartFrom();
    if (slaStartFrom == SlaStartFrom.MILESTONE_ACTIVATED) {
      return activatedAt.plus(slaDuration);
    }

    if (slaStartFrom == SlaStartFrom.CASE_CREATED) {
      // Query EventLog for CASE_STARTED event to get creation timestamp
      List<EventLog> events =
          eventLogRepository.findByCaseAndTypes(
              caseInstance.getUuid(),
              EnumSet.of(CaseHubEventType.CASE_STARTED),
              caseInstance.tenancyId);
      EventLog caseStartedEvent =
          events.stream()
              .filter(e -> e.getEventType() == CaseHubEventType.CASE_STARTED)
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "CASE_STARTED event not found for case: " + caseInstance.getUuid()));
      return caseStartedEvent.getTimestamp().plus(slaDuration);
    }

    throw new UnsupportedOperationException(
        "SlaStartFrom." + slaStartFrom + " not yet implemented");
  }
}
