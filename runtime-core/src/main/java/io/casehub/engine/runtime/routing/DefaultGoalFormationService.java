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
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationResult;
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
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

public class DefaultGoalFormationService implements GoalFormationService {

  private static final Logger LOG = Logger.getLogger(DefaultGoalFormationService.class);
  private static final int MAX_GOALS = 10;
  private static final int MAX_NAME_LENGTH = 100;
  private static final int MAX_DESCRIPTION_LENGTH = 500;

  private final AgentRegistry agentRegistry;
  private final EventLogRepository eventLogRepository;

  public DefaultGoalFormationService(
      AgentRegistry agentRegistry, EventLogRepository eventLogRepository) {
    this.agentRegistry = agentRegistry;
    this.eventLogRepository = eventLogRepository;
  }

  @Override
  public GoalFormationResult propose(
      String agentId, String tenancyId, GoalFormationProposal proposal) {
    Optional<AgentDescriptor> descriptorOpt = agentRegistry.findById(agentId, tenancyId);
    if (descriptorOpt.isEmpty()) {
      List<GoalFormationResult.RejectedGoal> rejected =
          proposal.goals().stream()
              .map(g -> new GoalFormationResult.RejectedGoal(g.name(), "agent not found"))
              .toList();
      return new GoalFormationResult(List.of(), rejected, 0);
    }

    AgentDescriptor descriptor = descriptorOpt.get();
    int remaining = MAX_GOALS - descriptor.goals().size();

    Set<String> existingNames = new HashSet<>();
    for (AgentGoal g : descriptor.goals()) {
      existingNames.add(g.name());
    }

    List<AgentGoal> registered = new ArrayList<>();
    List<GoalFormationResult.RejectedGoal> rejected = new ArrayList<>();

    for (GoalFormationProposal.ProposedGoal p : proposal.goals()) {
      if (registered.size() >= remaining) {
        rejected.add(new GoalFormationResult.RejectedGoal(p.name(), "capacity exceeded"));
        continue;
      }
      if (p.name() == null || p.name().length() > MAX_NAME_LENGTH) {
        rejected.add(
            new GoalFormationResult.RejectedGoal(
                p.name() != null ? p.name() : "<null>", "name too long or null"));
        continue;
      }
      if (p.description() == null || p.description().length() > MAX_DESCRIPTION_LENGTH) {
        rejected.add(
            new GoalFormationResult.RejectedGoal(p.name(), "description too long or null"));
        continue;
      }
      if (existingNames.contains(p.name())) {
        rejected.add(new GoalFormationResult.RejectedGoal(p.name(), "duplicate name"));
        continue;
      }

      GoalPriority priority =
          p.suggestedPriority() != null ? p.suggestedPriority() : GoalPriority.SECONDARY;
      AgentGoal goal =
          new AgentGoal(
              p.name(), p.description(), priority, Visibility.PUBLIC, List.of(), p.attributes());
      registered.add(goal);
      existingNames.add(p.name());
    }

    if (!registered.isEmpty()) {
      List<AgentGoal> merged = new ArrayList<>(descriptor.goals());
      merged.addAll(registered);
      AgentDescriptor updated = descriptor.toBuilder().goals(merged).build();
      agentRegistry.register(updated);
      writeAuditLog(agentId, tenancyId, registered, descriptor.goals().size(), merged.size());
    }

    return new GoalFormationResult(
        registered, rejected, descriptor.goals().size() + registered.size());
  }

  private void writeAuditLog(
      String agentId,
      String tenancyId,
      List<AgentGoal> formedGoals,
      int previousCount,
      int newCount) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("agentId", agentId);
      metadata.put("previousGoalCount", previousCount);
      metadata.put("newGoalCount", newCount);

      List<Map<String, String>> goalDetails = new ArrayList<>();
      for (AgentGoal g : formedGoals) {
        Map<String, String> detail = new HashMap<>();
        detail.put("name", g.name());
        detail.put("description", g.description());
        detail.put("priority", g.priority().name());
        goalDetails.add(detail);
      }
      metadata.put("formedGoals", goalDetails);

      EventLog eventLog = new EventLog();
      eventLog.setEventType(CaseHubEventType.GOAL_FORMED);
      eventLog.setPayload(mapper.valueToTree(metadata));
      eventLog.setTimestamp(Instant.now());
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to write GOAL_FORMED audit log for agent %s", agentId);
    }
  }
}
