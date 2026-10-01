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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalBasedCompletion;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.SingleGoalExpression;
import io.casehub.api.spi.event.EventDispatcher;
import io.casehub.engine.common.internal.event.CaseStatusChanged;
import io.casehub.engine.common.internal.event.GoalReachedEvent;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.spi.LedgerTraceIdProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoalReachedEventHandlerTest {

  private final List<Object> dispatched = new ArrayList<>();
  private final List<CaseLifecycleEvent> lifecycleEvents = new ArrayList<>();
  private CaseDefinitionRegistry caseDefinitionRegistry;
  private EventLogRepository eventLogRepository;
  private LedgerTraceIdProvider traceIdProvider;
  private GoalReachedEventHandler handler;

  @BeforeEach
  void setUp() {
    dispatched.clear();
    lifecycleEvents.clear();
    caseDefinitionRegistry = mock(CaseDefinitionRegistry.class);
    eventLogRepository = mock(EventLogRepository.class);
    traceIdProvider = mock(LedgerTraceIdProvider.class);
    when(traceIdProvider.currentTraceId()).thenReturn(Optional.empty());

    EventDispatcher dispatcher = dispatched::add;

    handler =
        new GoalReachedEventHandler(
            caseDefinitionRegistry,
            dispatcher,
            eventLogRepository,
            lifecycleEvents::add,
            traceIdProvider);
  }

  @Test
  void shouldRecordEventLogAndFireLifecycleEvent() {
    CaseInstance caseInstance = mock(CaseInstance.class);
    when(caseInstance.getUuid())
        .thenReturn(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
    caseInstance.tenancyId = "tenant-1";

    CaseDefinition definition = mock(CaseDefinition.class);
    when(definition.getCompletion()).thenReturn(null);
    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);

    Goal goal = mock(Goal.class);
    when(goal.getName()).thenReturn("goal-1");
    when(goal.getDescription()).thenReturn("Test goal");
    when(goal.getKind()).thenReturn("SUCCESS");

    handler.handle(new GoalReachedEvent(caseInstance, List.of(goal)));

    verify(eventLogRepository).append(any(), eq("tenant-1"));
    assertEquals(1, lifecycleEvents.size());
    assertTrue(dispatched.isEmpty());
  }

  @Test
  void shouldDispatchCaseStatusChangedOnCompletionGoal() {
    CaseInstance caseInstance = mock(CaseInstance.class);
    when(caseInstance.getUuid())
        .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    caseInstance.tenancyId = "tenant-1";
    when(caseInstance.getState()).thenReturn(CaseStatus.RUNNING);

    GoalBasedCompletion<GoalKind> gbc =
        GoalBasedCompletion.<GoalKind>builder()
            .goal(GoalKind.SUCCESS, new SingleGoalExpression("goal-1"))
            .build();

    CaseDefinition definition = mock(CaseDefinition.class);
    when(definition.getCompletion()).thenReturn(gbc);
    when(caseDefinitionRegistry.getCaseDefinition(any())).thenReturn(definition);

    EventLog existingLog = new EventLog();
    existingLog.setMetadata(
        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("name", "goal-1"));
    when(eventLogRepository.findByCaseAndTypes(any(), any(), any()))
        .thenReturn(List.of(existingLog));

    Goal goal = mock(Goal.class);
    when(goal.getName()).thenReturn("goal-1");
    when(goal.getDescription()).thenReturn("Test goal");
    when(goal.getKind()).thenReturn("SUCCESS");

    handler.handle(new GoalReachedEvent(caseInstance, List.of(goal)));

    assertFalse(dispatched.isEmpty());
    assertInstanceOf(CaseStatusChanged.class, dispatched.get(0));
  }
}
