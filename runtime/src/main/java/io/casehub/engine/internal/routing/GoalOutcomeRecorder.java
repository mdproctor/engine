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
package io.casehub.engine.internal.routing;

import io.casehub.api.model.CaseDefinition;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.GoalOutcome;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GoalOutcomeRecorder {

  private static final Logger LOG = Logger.getLogger(GoalOutcomeRecorder.class);

  private final Instance<GoalSignalStore> signalStore;
  private final CaseDefinitionRegistry registry;

  @Inject
  public GoalOutcomeRecorder(
      Instance<GoalSignalStore> signalStore, CaseDefinitionRegistry registry) {
    this.signalStore = signalStore;
    this.registry = registry;
  }

  private static GoalOutcome mapOutcome(WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Success<?> s -> GoalOutcome.SUCCESS;
      case WorkerOutcome.Completed<?> c -> GoalOutcome.SUCCESS;
      case WorkerOutcome.Declined<?> d -> GoalOutcome.FAILURE;
      case WorkerOutcome.Failed<?> f -> GoalOutcome.FAILURE;
      case WorkerOutcome.Expired<?> e -> GoalOutcome.FAILURE;
    };
  }

  public void record(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome) {
    if (!signalStore.isResolvable()) {
      return;
    }
    if (capabilityName == null) {
      return;
    }

    CaseDefinition definition;
    try {
      definition = registry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      return;
    }

    Optional<AgentDescriptor> descriptorOpt = definition.agentDescriptorFor(workerName);
    if (descriptorOpt.isEmpty()) {
      return;
    }

    AgentDescriptor descriptor = descriptorOpt.get();
    if (descriptor.goals().isEmpty()) {
      return;
    }

    GoalOutcome goalOutcome = mapOutcome(outcome);
    String agentId = descriptor.agentId();
    String tenancyId = caseInstance.tenancyId;

    for (var goal : descriptor.goals()) {
      if (!goal.capabilities().isEmpty() && !goal.capabilities().contains(capabilityName)) {
        continue;
      }
      signalStore.get().recordOutcome(agentId, tenancyId, goal.name(), goalOutcome);
      LOG.debugf(
          "Goal outcome recorded: agent=%s goal=%s outcome=%s capability=%s",
          agentId, goal.name(), goalOutcome, capabilityName);
    }
  }
}
