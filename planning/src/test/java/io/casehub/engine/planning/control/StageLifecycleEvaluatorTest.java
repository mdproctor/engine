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
package io.casehub.engine.planning.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.planning.plan.DefaultCasePlanModel;
import io.casehub.engine.planning.stage.Stage;
import io.casehub.engine.planning.stage.StageStatus;
import io.casehub.platform.api.identity.TenancyConstants;
import io.vertx.mutiny.core.eventbus.EventBus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for StageLifecycleEvaluator entry/exit evaluation. See casehubio/engine#76. */
class StageLifecycleEvaluatorTest {

  private StageLifecycleEvaluator evaluator;
  private EventBus mockBus;
  private DefaultCasePlanModel plan;
  private PlanExecutionContext ctx;

  @BeforeEach
  void setUp() {
    mockBus = mock(EventBus.class);
    evaluator = new StageLifecycleEvaluator(mockBus);
    UUID caseId = UUID.randomUUID();
    plan = new DefaultCasePlanModel(caseId);
    CaseContext mockCtx = mock(CaseContext.class);
    ctx =
        new PlanExecutionContext(
            caseId,
            mock(CaseDefinition.class),
            mockCtx,
            io.casehub.api.model.CaseStatus.RUNNING,
            TenancyConstants.DEFAULT_TENANT_ID,
            List.of(),
            null,
            null);
  }

  @Test
  void pending_stage_activates_when_entry_condition_met() {
    Stage stage = Stage.builder("intake").entryCondition(c -> true).build();
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.ACTIVE);
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.STAGE_ACTIVATED), any());
  }

  @Test
  void pending_stage_stays_pending_when_entry_condition_not_met() {
    Stage stage = Stage.builder("intake").entryCondition(c -> false).build();
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.PENDING);
    verifyNoInteractions(mockBus);
  }

  @Test
  void pending_stage_with_no_entry_condition_activates() {
    Stage stage = Stage.alwaysActivate("intake"); // no entry condition
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.ACTIVE);
  }

  @Test
  void active_stage_terminates_when_exit_condition_met() {
    Stage stage =
        Stage.builder("intake").entryCondition(c -> true).exitCondition(c -> true).build();
    stage.activate();
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.TERMINATED);
    verify(mockBus).publish(eq(BlackboardEventBusAddresses.STAGE_TERMINATED), any());
  }

  @Test
  void active_stage_stays_active_when_exit_condition_not_met() {
    Stage stage =
        Stage.builder("intake").entryCondition(c -> true).exitCondition(c -> false).build();
    stage.activate();
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.ACTIVE);
    verifyNoInteractions(mockBus);
  }

  @Test
  void manual_activation_stage_stays_pending_even_when_entry_met() {
    Stage stage =
        Stage.builder("intake").entryCondition(c -> true).withManualActivation(true).build();
    plan.addStage(stage);

    evaluator.evaluate(plan, ctx);

    assertThat(stage.getStatus()).isEqualTo(StageStatus.PENDING);
    verifyNoInteractions(mockBus);
  }

  @Test
  void nested_stage_stays_pending_while_parent_is_pending() {
    Stage parent = Stage.builder("parent").entryCondition(c -> false).build(); // never activates
    Stage child = Stage.alwaysActivate("child").withParentStage(parent.getStageId());

    plan.addStage(parent);
    plan.addStage(child);

    evaluator.evaluate(plan, ctx);

    assertThat(parent.getStatus()).isEqualTo(StageStatus.PENDING);
    assertThat(child.getStatus())
        .as("child stage must stay PENDING when parent is not ACTIVE")
        .isEqualTo(StageStatus.PENDING);
  }

  @Test
  void nested_stage_activates_after_parent_becomes_active() {
    Stage parent = Stage.alwaysActivate("parent"); // no entry condition — activates immediately
    Stage child = Stage.alwaysActivate("child").withParentStage(parent.getStageId());

    plan.addStage(parent);
    plan.addStage(child);

    // First cycle: parent activates. Child is still PENDING this cycle
    // because getPendingStages() snapshot was taken before parent activated.
    evaluator.evaluate(plan, ctx);
    assertThat(parent.getStatus()).isEqualTo(StageStatus.ACTIVE);

    // Second cycle: parent is now ACTIVE, child can activate
    evaluator.evaluate(plan, ctx);
    assertThat(child.getStatus())
        .as("child stage must activate once parent is ACTIVE")
        .isEqualTo(StageStatus.ACTIVE);
  }

  @Test
  void root_stage_without_parent_activates_normally() {
    Stage root = Stage.alwaysActivate("root"); // no parentStageId
    plan.addStage(root);

    evaluator.evaluate(plan, ctx);

    assertThat(root.getStatus())
        .as("root stage (no parent) must not be affected by parent-active guard")
        .isEqualTo(StageStatus.ACTIVE);
  }
}
