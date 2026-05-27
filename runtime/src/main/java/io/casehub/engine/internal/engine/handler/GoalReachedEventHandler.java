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
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/** Records a GOAL_REACHED event and evaluates whether the case has reached a terminal state. */
@ApplicationScoped
public class GoalReachedEventHandler {

  private static final Logger LOG = Logger.getLogger(GoalReachedEventHandler.class);

  @Inject CaseDefinitionRegistry caseDefinitionRegistry;

  @Inject EventBus eventBus;

  @Inject EventLogRepository eventLogRepository;

  @Inject Event<CaseLifecycleEvent> lifecycleEvents;

  @Inject LedgerTraceIdProvider traceIdProvider;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @ConsumeEvent(value = EventBusAddresses.GOAL_REACHED)
  public Uni<Void> onGoalReachedEventHandler(GoalReachedEvent event) {
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
            .put("kind", goal.getKind().value())
            .put("isTerminal", goal.getTerminal()));

    return eventLogRepository
        .append(eventLog)
        .invoke(
            () ->
                lifecycleEvents.fireAsync(
                    new CaseLifecycleEvent(
                        caseInstance.getUuid(),
                        "ReachGoal",
                        "GoalReached",
                        caseInstance.getState().name(),
                        null,
                        "System",
                        traceId)))
        .chain(() -> evaluateCompletion(caseInstance, definition.getCompletion()));
  }

  private Uni<Void> evaluateCompletion(CaseInstance caseInstance, CaseCompletion completion) {
    if (completion == null || !(completion instanceof GoalBasedCompletion goalBasedCompletion)) {
      return Uni.createFrom().voidItem();
    }

    return eventLogRepository
        .findByCaseAndTypes(caseInstance.getUuid(), Set.of(GOAL_REACHED))
        .chain(
            eventLogs -> {
              Set<String> reachedGoals =
                  eventLogs.stream()
                      .map(el -> el.getMetadata().get("name").asText())
                      .collect(Collectors.toSet());

              LOG.infof(
                  "Evaluating completion for caseId=%s, reachedGoals=%s",
                  caseInstance.getUuid(), reachedGoals);

              String oldStatus = caseInstance.getState().name();

              if (goalBasedCompletion.getFailure() != null
                  && isGoalExpressionSatisfied(goalBasedCompletion.getFailure(), reachedGoals)) {
                LOG.infof("Case FAILED: caseId=%s", caseInstance.getUuid());
                eventBus.publish(
                    EventBusAddresses.CASE_STATUS_CHANGED,
                    new CaseStatusChanged(caseInstance, oldStatus, CaseStatus.FAULTED.name()));
                return Uni.createFrom().voidItem();
              }

              if (goalBasedCompletion.getSuccess() != null
                  && isGoalExpressionSatisfied(goalBasedCompletion.getSuccess(), reachedGoals)) {
                LOG.infof("Case COMPLETED: caseId=%s", caseInstance.getUuid());
                eventBus.publish(
                    EventBusAddresses.CASE_STATUS_CHANGED,
                    new CaseStatusChanged(caseInstance, oldStatus, CaseStatus.COMPLETED.name()));
                return Uni.createFrom().voidItem();
              }

              return Uni.createFrom().voidItem();
            });
  }

  private boolean isGoalExpressionSatisfied(GoalExpression expression, Set<String> reachedGoals) {
    if (expression == null || expression.getGoals() == null || expression.getGoals().isEmpty()) {
      return false;
    }
    Set<String> expressionGoalNames =
        expression.getGoals().stream().map(Goal::getName).collect(Collectors.toSet());
    if (expression instanceof io.casehub.api.model.AllOfGoalExpression) {
      return reachedGoals.containsAll(expressionGoalNames);
    }
    if (expression instanceof io.casehub.api.model.AnyOfGoalExpression) {
      return expressionGoalNames.stream().anyMatch(reachedGoals::contains);
    }
    return false;
  }
}
