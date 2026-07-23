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
package io.casehub.engine.internal.engine.handler;

import static io.casehub.api.model.event.CaseHubEventType.GOAL_REACHED;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseCompletion;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/** Records a GOAL_REACHED event and evaluates whether the case has reached a terminal state. */
@ApplicationScoped
public class GoalReachedEventHandler {

  private static final Logger LOG = Logger.getLogger(GoalReachedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  @Inject CaseDefinitionRegistry caseDefinitionRegistry;
  @Inject EventBus eventBus;
  @Inject EventLogRepository eventLogRepository;
  @Inject Event<CaseLifecycleEvent> lifecycleEvents;
  @Inject LedgerTraceIdProvider traceIdProvider;

  @ConsumeEvent(value = EventBusAddresses.GOAL_REACHED)
  @RunOnVirtualThread
  void onGoalReachedEventHandler(GoalReachedEvent event) {
    try {
      final String traceId = traceIdProvider.currentTraceId().orElse(null);
      final CaseInstance caseInstance = event.caseInstance();
      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
      final Goal goal = event.goal();

      EventLog eventLog = new EventLog();
      eventLog.setCaseId(caseInstance.getUuid());
      eventLog.setEventType(GOAL_REACHED);
      eventLog.setStreamType(EventStreamType.CASE);
      eventLog.setTimestamp(Instant.now());
      eventLog.setMetadata(
          OBJECT_MAPPER
              .createObjectNode()
              .put("name", goal.getName())
              .put("description", goal.getDescription())
              .put("kind", goal.getKind()));

      eventLogRepository.append(eventLog, caseInstance.tenancyId);

      // Fire-and-forget — evaluateCompletion (case status change) must not be
      // gated on optional audit observer completion. Refs casehubio/engine#491.
      lifecycleEvents
          .fireAsync(
              CaseLifecycleEvent.of(
                  caseInstance, "ReachGoal", "GoalReached", null, "System", traceId))
          .whenComplete(
              (v, t) -> {
                if (t != null) {
                  LOG.warnf(
                      t,
                      "CaseLifecycleEvent observer failed for caseId=%s event=GoalReached",
                      caseInstance.getUuid());
                }
              });

      evaluateCompletion(caseInstance, definition.getCompletion());
    } catch (Exception e) {
      LOG.errorf(e, "Failed to process GOAL_REACHED for caseId=%s", event.caseInstance().getUuid());
    }
  }

  private void evaluateCompletion(CaseInstance caseInstance, CaseCompletion completion) {
    if (!(completion instanceof GoalBasedCompletion<?> gbc)) {
      return;
    }

    CaseStatus currentState = caseInstance.getState();
    if (currentState == CaseStatus.COMPLETED
        || currentState == CaseStatus.FAULTED
        || currentState == CaseStatus.CANCELLED) {
      LOG.debugf(
          "Skipping completion evaluation — caseId=%s is already %s",
          caseInstance.getUuid(), currentState);
      return;
    }

    List<EventLog> eventLogs =
        eventLogRepository.findByCaseAndTypes(
            caseInstance.getUuid(), Set.of(GOAL_REACHED), caseInstance.tenancyId);

    Set<String> reachedGoals =
        eventLogs.stream()
            .map(el -> el.getMetadata().get("name").asText())
            .collect(Collectors.toSet());

    LOG.infof(
        "Evaluating completion for caseId=%s, reachedGoals=%s",
        caseInstance.getUuid(), reachedGoals);

    String oldStatus = caseInstance.getState().name();

    for (var entry : gbc.getGoals().entrySet()) {
      GoalKind kind = entry.getKey();
      GoalExpression expr = entry.getValue();
      String satisfiedName = expr.satisfiedGoalName(reachedGoals);
      if (satisfiedName != null) {
        LOG.infof(
            "Goal kind '%s' satisfied (goal '%s'): caseId=%s",
            kind.value(), satisfiedName, caseInstance.getUuid());
        eventBus.publish(
            EventBusAddresses.CASE_STATUS_CHANGED,
            new CaseStatusChanged(
                caseInstance,
                oldStatus,
                kind.terminalStatus().name(),
                satisfiedName,
                kind.value()));
        return;
      }
    }
  }
}
