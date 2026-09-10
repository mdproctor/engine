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
import io.casehub.api.spi.routing.GoalFormationService;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.api.routing.StrategyResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

public class GoalFormationEvaluator {

  private static final Logger LOG = Logger.getLogger(GoalFormationEvaluator.class);
  private static final int MAX_GOALS = 10;
  private final Optional<AgentRegistry> agentRegistry;
  private final Optional<GoalFormationService> goalFormationService;
  private final Optional<CaseMemoryStore> caseMemoryStore;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final StrategyResolver strategyResolver;
  private final EventLogRepository eventLogRepository;
  private final boolean enabled;
  private final boolean autoApprove;
  private final String strategyId;
  private final int maxNewPerReflection;
  private final long cooldownMinutes;
  private final int maxMemories;

  private final ConcurrentHashMap<String, Instant> lastFormationTime = new ConcurrentHashMap<>();

  public GoalFormationEvaluator(
      Optional<AgentRegistry> agentRegistry,
      Optional<GoalFormationService> goalFormationService,
      Optional<CaseMemoryStore> caseMemoryStore,
      CaseDefinitionRegistry caseDefinitionRegistry,
      StrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      boolean enabled,
      boolean autoApprove,
      String strategyId,
      int maxNewPerReflection,
      long cooldownMinutes,
      int maxMemories) {
    this.agentRegistry = agentRegistry;
    this.goalFormationService = goalFormationService;
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
    if (agentRegistry.isEmpty()) {
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

      List<GoalFormationProposal.ProposedGoal> trimmed =
          proposal.goals().size() > maxNewPerReflection
              ? proposal.goals().subList(0, maxNewPerReflection)
              : proposal.goals();
      GoalFormationProposal trimmedProposal =
          new GoalFormationProposal(trimmed, proposal.rationale());

      if (autoApprove) {
        if (goalFormationService.isEmpty()) {
          LOG.debug("GoalFormationService not resolvable, skipping registration");
          return;
        }
        goalFormationService.get().propose(agentId, tenancyId, trimmedProposal);
      } else {
        writeProposedAuditLog(
            agentId, tenancyId, trimmedProposal, insights.size(), memories.size());
      }

    } catch (Exception e) {
      LOG.warnf(e, "Goal formation failed for agent %s", agentId);
    }
  }

  private List<RetrievedMemory> retrieveMemories(String workerName, String tenancyId) {
    if (caseMemoryStore.isEmpty()) {
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

  private void writeProposedAuditLog(
      String agentId,
      String tenancyId,
      GoalFormationProposal proposal,
      int insightCount,
      int memoryCount) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("agentId", agentId);
      metadata.put("insightCount", insightCount);
      metadata.put("memoryCount", memoryCount);
      metadata.put("strategyId", strategyId);
      metadata.put("approved", false);

      List<Map<String, String>> proposedGoals = new ArrayList<>();
      for (var g : proposal.goals()) {
        Map<String, String> detail = new HashMap<>();
        detail.put("name", g.name());
        detail.put("description", g.description());
        detail.put("formationReason", g.formationReason());
        proposedGoals.add(detail);
      }
      metadata.put("proposedGoals", proposedGoals);

      EventLog eventLog = new EventLog();
      eventLog.setEventType(CaseHubEventType.GOAL_PROPOSED);
      eventLog.setPayload(mapper.valueToTree(metadata));
      eventLog.setTimestamp(Instant.now());
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to write GOAL_PROPOSED audit log for agent %s", agentId);
    }
  }
}
