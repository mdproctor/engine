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
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Marks agent goal completion in the case context when workers complete successfully.
 *
 * <p>Writes {@code _agentGoals.<agentId>.<goalName>.met = true} to enable GoalExpression evaluation
 * against agent standing goals. Refs engine#785.
 */
@ApplicationScoped
public class AgentGoalCompletionMarker {

  private static final Logger LOG = Logger.getLogger(AgentGoalCompletionMarker.class);

  private final CaseDefinitionRegistry registry;

  @Inject
  public AgentGoalCompletionMarker(CaseDefinitionRegistry registry) {
    this.registry = registry;
  }

  /**
   * Marks agent goal completion on successful worker outcome.
   *
   * @param caseInstance the case instance
   * @param workerName the name of the worker that completed
   */
  public void markGoalsCompleted(CaseInstance caseInstance, String workerName) {
    CaseDefinition definition;
    try {
      definition = registry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      LOG.debugf("Cannot resolve definition for case %s", caseInstance.getUuid());
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

    String agentId = descriptor.agentId();
    Map<String, Object> agentGoals = getOrCreateAgentGoalsMap(caseInstance);

    @SuppressWarnings("unchecked")
    Map<String, Object> agentMap =
        (Map<String, Object>) agentGoals.computeIfAbsent(agentId, k -> new HashMap<>());

    for (var goal : descriptor.goals()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> goalMap =
          (Map<String, Object>) agentMap.computeIfAbsent(goal.name(), k -> new HashMap<>());

      goalMap.put("met", true);
      goalMap.put("timestamp", Instant.now().toString());

      LOG.debugf("Agent goal marked completed: agent=%s goal=%s", agentId, goal.name());
    }

    caseInstance.getCaseContext().set("_agentGoals", agentGoals);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getOrCreateAgentGoalsMap(CaseInstance caseInstance) {
    Object existing = caseInstance.getCaseContext().get("_agentGoals");
    if (existing instanceof Map) {
      return new HashMap<>((Map<String, Object>) existing);
    }
    return new HashMap<>();
  }
}
