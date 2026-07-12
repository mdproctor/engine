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

import io.casehub.blackboard.event.BlackboardEventBusAddresses;
import io.casehub.blackboard.event.StageCompletedEvent;
import io.casehub.blackboard.plan.CasePlanModel;
import io.casehub.blackboard.stage.Stage;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Evaluates stage autocomplete after a PlanItem reaches a terminal state.
 *
 * <p>A stage autocompletes when all its required items have settled — reached any terminal state
 * (COMPLETED, REJECTED, FAULTED, CANCELLED). The outcome is the business concern of the case
 * definition; the engine's job is to propagate the stage-concluded signal.
 *
 * <p>Shared by {@link PlanItemCompletionHandler} and {@link WorkerRetryExhaustionHandler}.
 * Extracted to avoid duplicating the isTerminal() logic and to keep both handlers in sync on future
 * changes. See ADR-0002.
 */
@ApplicationScoped
public class StageAutocompleteEvaluator {

  private static final Logger LOG = Logger.getLogger(StageAutocompleteEvaluator.class);

  private final EventBus eventBus;

  @Inject
  public StageAutocompleteEvaluator(final EventBus eventBus) {
    this.eventBus = eventBus;
  }

  public void evaluate(
      final UUID caseId,
      final String tenancyId,
      final CasePlanModel plan,
      final String changedItemId) {
    for (final Stage stage : plan.getActiveStages()) {
      if (!stage.isAutocomplete()) {
        continue;
      }
      if (!stage.getRequiredItemIds().contains(changedItemId)) {
        continue;
      }

      final boolean allTerminal =
          stage.getRequiredItemIds().stream()
              .allMatch(
                  itemId ->
                      plan.getPlanItem(itemId)
                          .map(pi -> pi.getStatus().isTerminal())
                          .orElse(false));

      if (allTerminal) {
        int completingIndex = stage.getInstanceIndex();
        stage.complete();
        eventBus.publish(
            BlackboardEventBusAddresses.STAGE_COMPLETED,
            new StageCompletedEvent(caseId, tenancyId, stage, completingIndex));
        if (stage.isRepeatable()) {
          if (!stage.getContainedStageIds().isEmpty()
              || !stage.getContainedMilestoneIds().isEmpty()) {
            LOG.warnf(
                "Repeatable stage '%s' contains nested stages or milestones — skipping reset",
                stage.getName());
          } else {
            stage.resetForRepetition();
          }
        }
        LOG.debugf(
            "Stage '%s' autocompleted for case %s (instance %d)",
            stage.getName(), caseId, completingIndex);
      }
    }
  }
}
