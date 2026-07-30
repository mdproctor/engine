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
package io.casehub.engine.planning.handler;

import io.casehub.engine.planning.event.BlackboardEventBusAddresses;
import io.casehub.engine.common.internal.event.CompoundCompletedEvent;
import io.casehub.engine.planning.plan.CasePlanModel;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CompoundCompletionEvaluator {

  private static final Logger LOG = Logger.getLogger(CompoundCompletionEvaluator.class);

  private final EventBus eventBus;

  @Inject
  public CompoundCompletionEvaluator(EventBus eventBus) {
    this.eventBus = eventBus;
  }

  public void evaluate(UUID caseId, String tenancyId, CasePlanModel plan, String changedItemId) {
    Optional<String> parentOpt = plan.getParentOf(changedItemId);
    while (parentOpt.isPresent()) {
      String parentId = parentOpt.get();
      if (plan.getDefinitionStatus(parentId).isTerminal()) {
        return;
      }
      if (!plan.evaluateCompletion(parentId)) {
        return;
      }
      plan.tryDefinitionTransition(
          parentId, plan.getDefinitionStatus(parentId), io.casehub.api.model.TaskStatus.COMPLETED);

      io.casehub.engine.planning.plan.PlanItemDefinition def = plan.getDefinition(parentId);
      String name = def != null ? def.name() : parentId;
      java.util.Set<String> scopedBindings = (def instanceof io.casehub.engine.planning.plan.PlanItemDefinition.Compound c)
          ? c.scopedBindings().keySet() : java.util.Set.of();

      eventBus.publish(
          BlackboardEventBusAddresses.COMPOUND_COMPLETED,
          new CompoundCompletedEvent(caseId, tenancyId, parentId, name, scopedBindings));

      LOG.debugf("Compound '%s' completed for case %s", parentId, caseId);
      parentOpt = plan.getParentOf(parentId);
    }
  }
}
