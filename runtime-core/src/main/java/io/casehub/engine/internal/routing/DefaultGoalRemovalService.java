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

import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.spi.routing.GoalRemovalResult;
import io.casehub.api.spi.routing.GoalRemovalService;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.spi.EventLogRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

public class DefaultGoalRemovalService implements GoalRemovalService {

  private static final Logger LOG = Logger.getLogger(DefaultGoalRemovalService.class);

  private final AgentRegistry agentRegistry;
  private final EventLogRepository eventLogRepository;

  public DefaultGoalRemovalService(
      AgentRegistry agentRegistry, EventLogRepository eventLogRepository) {
    this.agentRegistry = agentRegistry;
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public GoalRemovalResult removeGoals(
      String agentId, String tenancyId, List<String> goalNames, String reason) {
    Optional<AgentDescriptor> descriptorOpt = agentRegistry.findById(agentId, tenancyId);
    if (descriptorOpt.isEmpty()) {
      return new GoalRemovalResult(List.of(), 0);
    }

    AgentDescriptor descriptor = descriptorOpt.get();
    Set<String> namesToRemove = new HashSet<>(goalNames);

    List<AgentGoal> remaining = new ArrayList<>();
    List<String> removed = new ArrayList<>();

    for (AgentGoal goal : descriptor.goals()) {
      if (namesToRemove.contains(goal.name())) {
        removed.add(goal.name());
      } else {
        remaining.add(goal);
      }
    }

    if (!removed.isEmpty()) {
      AgentDescriptor updated = descriptor.toBuilder().goals(remaining).build();
      agentRegistry.register(updated);
      writeAuditLog(agentId, tenancyId, removed, reason, remaining.size());
    }

    return new GoalRemovalResult(removed, remaining.size());
  }

  private void writeAuditLog(
      String agentId,
      String tenancyId,
      List<String> removedGoals,
      String reason,
      int remainingCount) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("agentId", agentId);
      metadata.put("removedGoals", removedGoals);
      metadata.put("reason", reason);
      metadata.put("remainingGoalCount", remainingCount);

      EventLog eventLog = new EventLog();
      eventLog.setEventType(CaseHubEventType.GOAL_REMOVED);
      eventLog.setPayload(mapper.valueToTree(metadata));
      eventLog.setTimestamp(Instant.now());
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to write GOAL_REMOVED audit log for agent %s", agentId);
    }
  }
}
