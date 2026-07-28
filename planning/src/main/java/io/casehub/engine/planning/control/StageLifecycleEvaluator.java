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

import io.casehub.api.context.CaseContext;
import io.casehub.api.engine.ExpressionEngine;
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.planning.event.StageActivatedEvent;
import io.casehub.engine.planning.event.StageTerminatedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.stage.Stage;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Evaluates Stage entry and exit conditions on each CONTEXT_CHANGED cycle. Called from {@link
 * io.casehub.engine.planning.control.PlanningStrategyLoopControl} within the Uni chain. Publishes
 * {@link StageActivatedEvent} and {@link StageTerminatedEvent}.
 *
 * <p>Entry condition evaluation: PENDING stages with no entry condition, or whose entry condition
 * evaluates {@code true}, are activated — unless {@link Stage#isManualActivation()} is set.
 *
 * <p>Exit condition evaluation: ACTIVE stages whose exit condition evaluates {@code true} are
 * terminated. Stages with no exit condition are never auto-terminated by this evaluator.
 *
 * <p>Stage event types are registered with local-only Vert.x codecs by {@link
 * io.casehub.engine.planning.event.BlackboardEventCodecRegistrar} at startup, enabling in-VM
 * delivery without serialisation. See casehubio/engine#76.
 */
@ApplicationScoped
public class StageLifecycleEvaluator {

  private final EventBus eventBus;
  private final Instance<ExpressionEngine> expressionEngines;

  @Inject
  public StageLifecycleEvaluator(EventBus eventBus, Instance<ExpressionEngine> expressionEngines) {
    this.eventBus = eventBus;
    this.expressionEngines = expressionEngines;
  }

  /**
   * Constructor for unit testing — provides a no-CDI path for lambda-only conditions. Lambda
   * evaluators are dispatched directly without requiring any {@link ExpressionEngine} instances.
   */
  StageLifecycleEvaluator(EventBus eventBus) {
    this.eventBus = eventBus;
    this.expressionEngines = null;
  }

  /**
   * Evaluates all PENDING and ACTIVE stages in {@code plan} and transitions them as needed.
   *
   * @param plan the current case plan model
   * @param ctx the current plan execution context
   */
  public void evaluate(CasePlanModel plan, PlanExecutionContext ctx) {
    activatePendingStages(plan, ctx);
    terminateActiveStages(plan, ctx);
  }

  private void activatePendingStages(CasePlanModel plan, PlanExecutionContext ctx) {
    for (Stage stage : plan.getPendingStages()) {
      if (stage.isManualActivation()) continue;

      // Nested stage: only evaluate if parent is ACTIVE
      if (stage.getParentStageId().isPresent()) {
        String parentId = stage.getParentStageId().get();
        boolean parentActive = plan.getStage(parentId).map(Stage::isActive).orElse(false);
        if (!parentActive) continue;
      }

      boolean conditionMet = evaluateCondition(stage.getEntryCondition(), ctx.caseContext());
      if (conditionMet) {
        stage.activate();
        eventBus.publish(
            BlackboardEventBusAddresses.STAGE_ACTIVATED,
            new StageActivatedEvent(
                ctx.caseId(), ctx.tenancyId(), stage, stage.getInstanceIndex()));
      }
    }
  }

  private void terminateActiveStages(CasePlanModel plan, PlanExecutionContext ctx) {
    for (Stage stage : plan.getActiveStages()) {
      if (stage.getExitCondition() == null) continue;
      if (evaluateCondition(stage.getExitCondition(), ctx.caseContext())) {
        stage.terminate();
        eventBus.publish(
            BlackboardEventBusAddresses.STAGE_TERMINATED,
            new StageTerminatedEvent(ctx.caseId(), ctx.tenancyId(), stage));
      }
    }
  }

  /**
   * Evaluates {@code evaluator} against {@code context}. Returns {@code true} if {@code evaluator}
   * is {@code null} (no condition = always met). Dispatches {@link LambdaExpressionEvaluator}
   * directly for testability; all other types iterate the {@link ExpressionEngine} instances to
   * find one that handles the evaluator's {@link ExpressionEvaluator#type()}.
   */
  private boolean evaluateCondition(ExpressionEvaluator evaluator, CaseContext context) {
    if (evaluator == null) {
      return true;
    }
    if (evaluator instanceof LambdaExpressionEvaluator lambda) {
      return lambda.test(context);
    }
    if (expressionEngines == null) {
      throw new IllegalStateException(
          "ExpressionEngine instances not available for evaluator type: " + evaluator.type());
    }
    final String type = evaluator.type();
    for (ExpressionEngine engine : expressionEngines) {
      if (engine.type().equals(type)) {
        return engine.evaluate(evaluator, context);
      }
    }
    throw new IllegalArgumentException("No ExpressionEngine registered for type '" + type + "'");
  }
}
