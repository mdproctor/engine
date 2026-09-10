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
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

public class GoalReachedEventHandler {

  private static final Logger LOG = Logger.getLogger(GoalReachedEventHandler.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EventDispatcher eventDispatcher;
  private final EventLogRepository eventLogRepository;
  private final Consumer<CaseLifecycleEvent> lifecycleEventConsumer;
  private final LedgerTraceIdProvider traceIdProvider;

  public GoalReachedEventHandler(
      CaseDefinitionRegistry caseDefinitionRegistry,
      EventDispatcher eventDispatcher,
      EventLogRepository eventLogRepository,
      Consumer<CaseLifecycleEvent> lifecycleEventConsumer,
      LedgerTraceIdProvider traceIdProvider) {
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.eventDispatcher = eventDispatcher;
    this.eventLogRepository = eventLogRepository;
    this.lifecycleEventConsumer = lifecycleEventConsumer;
    this.traceIdProvider = traceIdProvider;
  }

  public void handle(GoalReachedEvent event) {
    try {
      final String traceId = traceIdProvider.currentTraceId().orElse(null);
      final CaseInstance caseInstance = event.caseInstance();
      CaseDefinition definition =
          caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());

      for (final Goal goal : event.goals()) {
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

        lifecycleEventConsumer.accept(
            CaseLifecycleEvent.of(
                caseInstance, "ReachGoal", "GoalReached", null, "System", traceId));
      }

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
    if (currentState.isTerminal()) {
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
        eventDispatcher.dispatch(
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
