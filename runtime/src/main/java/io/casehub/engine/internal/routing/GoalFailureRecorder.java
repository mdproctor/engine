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
import io.casehub.eidos.api.BehavioralSignal;
import io.casehub.eidos.api.BehavioralSignalStore;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GoalFailureRecorder {

  private static final Logger LOG = Logger.getLogger(GoalFailureRecorder.class);

  private final Instance<BehavioralSignalStore> signalStore;
  private final CaseDefinitionRegistry registry;

  @Inject
  public GoalFailureRecorder(
      Instance<BehavioralSignalStore> signalStore, CaseDefinitionRegistry registry) {
    this.signalStore = signalStore;
    this.registry = registry;
  }

  public void record(CaseInstance caseInstance, String workerName, WorkerOutcome<?> outcome) {
    if (!signalStore.isResolvable()) return;
    if (outcome instanceof WorkerOutcome.Success) return;

    CaseDefinition definition;
    try {
      definition = registry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      return;
    }

    Optional<AgentDescriptor> descriptorOpt = definition.agentDescriptorFor(workerName);
    if (descriptorOpt.isEmpty()) return;

    AgentDescriptor descriptor = descriptorOpt.get();
    if (descriptor.goals().isEmpty()) return;

    String agentId = descriptor.agentId();
    String tenancyId = caseInstance.tenancyId;

    for (var goal : descriptor.goals()) {
      signalStore
          .get()
          .record(
              agentId,
              tenancyId,
              GoalAbandonmentEvaluator.GOAL_CAPABILITY_SENTINEL,
              goal.name(),
              BehavioralSignal.DECLINE);
      LOG.debugf("Goal failure recorded: agent=%s goal=%s", agentId, goal.name());
    }
  }
}
