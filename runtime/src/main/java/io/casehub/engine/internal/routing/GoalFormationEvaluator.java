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
import io.casehub.api.model.RetrievedMemory;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.eidos.api.Visibility;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GoalFormationEvaluator {

  private static final Logger LOG = Logger.getLogger(GoalFormationEvaluator.class);
  private static final int MAX_GOALS = 10;
  private static final int MAX_NAME_LENGTH = 100;
  private static final int MAX_DESCRIPTION_LENGTH = 500;

  private final Instance<AgentRegistry> agentRegistry;
  private final Instance<CaseMemoryStore> caseMemoryStore;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EngineStrategyResolver strategyResolver;
  private final EventLogRepository eventLogRepository;
  private final boolean enabled;
  private final boolean autoApprove;
  private final String strategyId;
  private final int maxNewPerReflection;
  private final long cooldownMinutes;
  private final int maxMemories;

  private final ConcurrentHashMap<String, Instant> lastFormationTime = new ConcurrentHashMap<>();

  @Inject
  public GoalFormationEvaluator(
      Instance<AgentRegistry> agentRegistry,
      Instance<CaseMemoryStore> caseMemoryStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EngineStrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @ConfigProperty(name = "casehub.engine.goal.formation.enabled", defaultValue = "false")
          boolean enabled,
      @ConfigProperty(name = "casehub.engine.goal.formation.auto-approve", defaultValue = "true")
          boolean autoApprove,
      @ConfigProperty(name = "casehub.engine.goal.formation.strategy", defaultValue = "llm")
          String strategyId,
      @ConfigProperty(
              name = "casehub.engine.goal.formation.max-new-per-reflection",
              defaultValue = "2")
          int maxNewPerReflection,
      @ConfigProperty(name = "casehub.engine.goal.formation.cooldown-minutes", defaultValue = "60")
          long cooldownMinutes,
      @ConfigProperty(name = "casehub.engine.goal.formation.max-memories", defaultValue = "20")
          int maxMemories) {
    this.agentRegistry = agentRegistry;
    this.caseMemoryStore = caseMemoryStore;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.strategyResolver = strategyResolver;
    this.eventLogRepository = eventLogRepository;
    this.enabled = enabled;
    this.autoApprove = autoApprove;
    this.strategyId = strategyId;
    this.maxNewPerReflection = maxNewPerReflection;
    this.cooldownMinutes = cooldownMinutes;
    this.maxMemories = maxMemories;
  }

  public void evaluate(String workerName, CaseInstance caseInstance, List<String> insights) {
    if (!enabled) {
      return;
    }
    if (!agentRegistry.isResolvable()) {
      return;
    }
    if (insights == null || insights.isEmpty()) {
      return;
    }

    CaseDefinition definition;
    try {
      definition = caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
    } catch (Exception e) {
      return;
    }

    Optional<AgentDescriptor> descOpt = definition.agentDescriptorFor(workerName);
    if (descOpt.isEmpty()) {
      return;
    }

    AgentDescriptor descriptor = descOpt.get();
    int remaining = MAX_GOALS - descriptor.goals().size();
    if (remaining <= 0) {
      return;
    }

    String agentId = descriptor.agentId();
    String tenancyId = caseInstance.tenancyId;
    String key = agentId + "|" + tenancyId;

    var shouldForm = new boolean[] {false};
    lastFormationTime.compute(
        key,
        (k, last) -> {
          if (last != null && Duration.between(last, Instant.now()).toMinutes() < cooldownMinutes) {
            return last;
          }
          shouldForm[0] = true;
          return Instant.now();
        });
    if (!shouldForm[0]) {
      return;
    }

    Thread.startVirtualThread(
        () ->
            formGoals(
                agentId, tenancyId, descriptor, insights, remaining, definition, key, workerName));
  }

  private void formGoals(
      String agentId,
      String tenancyId,
      AgentDescriptor descriptor,
      List<String> insights,
      int remaining,
      CaseDefinition definition,
      String key,
      String workerName) {
    try {
      List<RetrievedMemory> memories = retrieveMemories(workerName, tenancyId);

      GoalFormationStrategy strategy;
      try {
        strategy = strategyResolver.resolve(GoalFormationStrategy.class, strategyId);
      } catch (Exception e) {
        LOG.debugf("No GoalFormationStrategy resolved for id %s", strategyId);
        return;
      }

      GoalFormationContext context =
          new GoalFormationContext(
              agentId, tenancyId, insights, descriptor.goals(), memories, remaining);
      GoalFormationProposal proposal = strategy.propose(context);
      if (proposal == null || proposal.goals().isEmpty()) {
        return;
      }

      List<AgentGoal> newGoals = validateAndConvert(proposal.goals(), descriptor, remaining);
      if (newGoals.isEmpty()) {
        return;
      }

      if (autoApprove) {
        List<AgentGoal> merged = new ArrayList<>(descriptor.goals());
        merged.addAll(newGoals);
        AgentDescriptor updated = descriptor.toBuilder().goals(merged).build();
        agentRegistry.get().register(updated);
        writeAuditLog(
            agentId,
            tenancyId,
            newGoals,
            descriptor.goals().size(),
            merged.size(),
            insights.size(),
            memories.size(),
            CaseHubEventType.GOAL_FORMED);
      } else {
        writeAuditLog(
            agentId,
            tenancyId,
            newGoals,
            descriptor.goals().size(),
            descriptor.goals().size(),
            insights.size(),
            memories.size(),
            CaseHubEventType.GOAL_PROPOSED);
      }

    } catch (Exception e) {
      LOG.warnf(e, "Goal formation failed for agent %s", agentId);
    }
  }

  private List<AgentGoal> validateAndConvert(
      List<GoalFormationProposal.ProposedGoal> proposed,
      AgentDescriptor descriptor,
      int remaining) {
    Set<String> existingNames = new HashSet<>();
    for (AgentGoal g : descriptor.goals()) {
      existingNames.add(g.name());
    }

    List<AgentGoal> validated = new ArrayList<>();
    for (GoalFormationProposal.ProposedGoal p : proposed) {
      if (validated.size() >= maxNewPerReflection || validated.size() >= remaining) {
        break;
      }
      try {
        if (p.name() == null || p.name().length() > MAX_NAME_LENGTH) {
          LOG.debugf("Rejected goal: name too long or null: %s", p.name());
          continue;
        }
        if (p.description() == null || p.description().length() > MAX_DESCRIPTION_LENGTH) {
          LOG.debugf("Rejected goal: description too long or null: %s", p.name());
          continue;
        }
        if (existingNames.contains(p.name())) {
          LOG.debugf("Rejected goal: duplicate name: %s", p.name());
          continue;
        }

        GoalPriority priority =
            p.suggestedPriority() != null ? p.suggestedPriority() : GoalPriority.SECONDARY;
        AgentGoal goal =
            new AgentGoal(p.name(), p.description(), priority, Visibility.PUBLIC, List.of());
        validated.add(goal);
        existingNames.add(p.name());
      } catch (Exception e) {
        LOG.warnf("Invalid proposed goal %s, skipping: %s", p.name(), e.getMessage());
      }
    }
    return validated;
  }

  private List<RetrievedMemory> retrieveMemories(String workerName, String tenancyId) {
    if (!caseMemoryStore.isResolvable()) {
      return List.of();
    }
    try {
      CaseMemoryStore store = caseMemoryStore.get();
      List<Memory> memories =
          store.query(
              MemoryQuery.forEntity(workerName, new MemoryDomain("reflection"), tenancyId)
                  .withLimit(maxMemories)
                  .withOrder(MemoryOrder.SALIENCE));
      List<RetrievedMemory> result = new ArrayList<>();
      for (Memory m : memories) {
        result.add(
            new RetrievedMemory(
                m.memoryId(), m.text(), m.domain().name(), m.createdAt(), m.attributes()));
      }
      return result;
    } catch (Exception e) {
      LOG.debugf(e, "Failed to retrieve memories for agent %s", workerName);
      return List.of();
    }
  }

  private void writeAuditLog(
      String agentId,
      String tenancyId,
      List<AgentGoal> formedGoals,
      int previousCount,
      int newCount,
      int insightCount,
      int memoryCount,
      CaseHubEventType eventType) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("agentId", agentId);
      metadata.put("previousGoalCount", previousCount);
      metadata.put("newGoalCount", newCount);
      metadata.put("insightCount", insightCount);
      metadata.put("memoryCount", memoryCount);
      metadata.put("strategyId", strategyId);
      metadata.put("approved", eventType == CaseHubEventType.GOAL_FORMED);

      List<Map<String, String>> formedGoalDetails = new ArrayList<>();
      for (AgentGoal g : formedGoals) {
        Map<String, String> detail = new HashMap<>();
        detail.put("name", g.name());
        detail.put("description", g.description());
        detail.put("priority", g.priority().name());
        formedGoalDetails.add(detail);
      }
      metadata.put("formedGoals", formedGoalDetails);

      EventLog eventLog = new EventLog();
      eventLog.setEventType(eventType);
      eventLog.setPayload(mapper.valueToTree(metadata));
      eventLog.setTimestamp(Instant.now());
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to write %s audit log for agent %s", eventType, agentId);
    }
  }
}
