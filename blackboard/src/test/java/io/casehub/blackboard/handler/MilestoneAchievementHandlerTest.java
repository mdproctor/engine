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
package io.casehub.blackboard.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.casehub.api.model.Milestone;
import io.casehub.api.model.MilestoneLifecycleStatus;
import io.casehub.api.model.SlaStatus;
import io.casehub.blackboard.plan.DefaultCasePlanModel;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.event.MilestoneActivatedEvent;
import io.casehub.engine.common.internal.event.MilestoneCompletedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for MilestoneAchievementHandler. See casehubio/engine#76. Milestone/Goal/Stage
 * alignment: casehubio/engine#84.
 */
class MilestoneAchievementHandlerTest {

  @Test
  void activated_event_transitions_milestone_to_active() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    DefaultCasePlanModel plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
    plan.trackMilestone("docs-received");

    MilestoneAchievementHandler handler = new MilestoneAchievementHandler(registry);

    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    Milestone milestone = mock(Milestone.class);
    when(milestone.getName()).thenReturn("docs-received");

    var event = new MilestoneActivatedEvent(instance, milestone, Instant.now(), null);
    handler.onMilestoneActivated(event).await().indefinitely();

    assertThat(plan.getMilestoneStatus("docs-received")).hasValue(MilestoneLifecycleStatus.ACTIVE);
  }

  @Test
  void completed_event_transitions_milestone_to_completed() {
    BlackboardRegistry registry = new BlackboardRegistry();
    UUID caseId = UUID.randomUUID();
    DefaultCasePlanModel plan = (DefaultCasePlanModel) registry.getOrCreate(caseId, "test-tenant");
    plan.trackMilestone("docs-received");
    plan.activateMilestone("docs-received");

    MilestoneAchievementHandler handler = new MilestoneAchievementHandler(registry);

    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(caseId);
    Milestone milestone = mock(Milestone.class);
    when(milestone.getName()).thenReturn("docs-received");

    var event = new MilestoneCompletedEvent(instance, milestone, Instant.now(), SlaStatus.ON_TRACK);
    handler.onMilestoneCompleted(event).await().indefinitely();

    assertThat(plan.getMilestoneStatus("docs-received"))
        .hasValue(MilestoneLifecycleStatus.COMPLETED);
    assertThat(plan.isMilestoneAchieved("docs-received")).isTrue();
  }

  @Test
  void no_plan_model_does_not_throw() {
    BlackboardRegistry registry = new BlackboardRegistry();
    MilestoneAchievementHandler handler = new MilestoneAchievementHandler(registry);

    CaseInstance instance = mock(CaseInstance.class);
    when(instance.getUuid()).thenReturn(UUID.randomUUID());
    Milestone milestone = mock(Milestone.class);
    when(milestone.getName()).thenReturn("docs-received");

    // No plan model exists for this caseId — should not throw
    var activatedEvent = new MilestoneActivatedEvent(instance, milestone, Instant.now(), null);
    handler.onMilestoneActivated(activatedEvent).await().indefinitely();

    var completedEvent =
        new MilestoneCompletedEvent(instance, milestone, Instant.now(), SlaStatus.ON_TRACK);
    handler.onMilestoneCompleted(completedEvent).await().indefinitely();
  }
}
