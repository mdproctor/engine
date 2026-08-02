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
import io.casehub.api.engine.PlanExecutionContext;
import io.casehub.api.model.TaskStatus;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompoundLifecycleEvaluator {

  private static final Logger LOG = Logger.getLogger(CompoundLifecycleEvaluator.class);

  private final io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry;
  private final io.vertx.mutiny.core.eventbus.EventBus eventBus;

  @Inject
  public CompoundLifecycleEvaluator(
      io.casehub.api.engine.ExpressionEngineRegistry expressionEngineRegistry,
      io.vertx.mutiny.core.eventbus.EventBus eventBus) {
    this.expressionEngineRegistry = expressionEngineRegistry;
    this.eventBus = eventBus;
  }

  CompoundLifecycleEvaluator() {
    this.expressionEngineRegistry = null;
    this.eventBus = null;
  }

  public java.util.List<PlanItemDefinition.Compound> evaluate(
      CasePlanModel plan, PlanExecutionContext ctx) {
    java.util.List<PlanItemDefinition.Compound> activated = activatePendingCompounds(plan, ctx);
    terminateRunningCompounds(plan, ctx);
    return activated;
  }

  private java.util.List<PlanItemDefinition.Compound> activatePendingCompounds(
      CasePlanModel plan, PlanExecutionContext ctx) {
    java.util.List<PlanItemDefinition.Compound> activated = new java.util.ArrayList<>();
    for (PlanItemDefinition.Compound compound : plan.getCompoundsByStatus(TaskStatus.PENDING)) {
      var parentOpt = plan.getParentOf(compound.id());
      if (parentOpt.isPresent()) {
        TaskStatus parentStatus = plan.getDefinitionStatus(parentOpt.get());
        if (parentStatus != TaskStatus.RUNNING) {
          continue;
        }
      }

      boolean conditionMet = evaluateCondition(compound.entryCondition(), ctx.caseContext());
      if (conditionMet) {
        if (plan.tryDefinitionTransition(compound.id(), TaskStatus.PENDING, TaskStatus.RUNNING)) {
          LOG.debugf("Compound '%s' activated for case %s", compound.name(), ctx.caseId());
          activated.add(compound);
          if (eventBus != null) {
            eventBus.publish(
                io.casehub.engine.planning.event.BlackboardEventBusAddresses.COMPOUND_ACTIVATED,
                new io.casehub.engine.common.internal.event.CompoundActivatedEvent(
                    ctx.caseId(),
                    ctx.tenancyId(),
                    compound.id(),
                    compound.name(),
                    compound.scopedBindings().keySet()));
          }
        }
      }
    }
    return activated;
  }

  private void terminateRunningCompounds(CasePlanModel plan, PlanExecutionContext ctx) {
    for (PlanItemDefinition.Compound compound : plan.getCompoundsByStatus(TaskStatus.RUNNING)) {
      if (compound.exitCondition() == null) {
        continue;
      }
      if (evaluateCondition(compound.exitCondition(), ctx.caseContext())) {
        if (plan.tryDefinitionTransition(compound.id(), TaskStatus.RUNNING, TaskStatus.COMPLETED)) {
          LOG.debugf(
              "Compound '%s' terminated via exit condition for case %s",
              compound.name(), ctx.caseId());
        }
      }
    }
  }

  private boolean evaluateCondition(ExpressionEvaluator evaluator, CaseContext context) {
    if (evaluator == null) {
      return true;
    }
    if (expressionEngineRegistry == null) {
      if (evaluator instanceof LambdaExpressionEvaluator lambda) {
        return lambda.test(context);
      }
      throw new IllegalStateException(
          "ExpressionEngineRegistry not available for evaluator type: " + evaluator.type());
    }
    return expressionEngineRegistry.evaluate(evaluator, context);
  }
}
