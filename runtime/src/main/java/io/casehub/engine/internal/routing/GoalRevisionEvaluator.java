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
import io.casehub.api.model.ReflectionTriggerConfig;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.spi.routing.GoalRevisionContext;
import io.casehub.api.spi.routing.GoalRevisionProposal;
import io.casehub.api.spi.routing.GoalRevisionStrategy;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentGoal;
import io.casehub.eidos.api.AgentRegistry;
import io.casehub.eidos.api.GoalEvolution;
import io.casehub.eidos.api.GoalEvolutionResult;
import io.casehub.eidos.api.GoalOutcomeCounts;
import io.casehub.eidos.api.GoalSignalStore;
import io.casehub.engine.common.internal.history.EventLog;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.EventLogRepository;
import io.casehub.worker.api.WorkerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GoalRevisionEvaluator {

  private static final Logger LOG = Logger.getLogger(GoalRevisionEvaluator.class);

  private final Instance<GoalSignalStore> goalSignalStore;
  private final Instance<GoalEvolution> goalEvolution;
  private final Instance<AgentRegistry> agentRegistry;
  private final CaseDefinitionRegistry caseDefinitionRegistry;
  private final EngineStrategyResolver strategyResolver;
  private final EventLogRepository eventLogRepository;
  private final boolean enabled;
  private final String strategyId;
  private final int minOutcomes;
  private final double importanceThreshold;

  private final ConcurrentHashMap<String, RevisionState> states = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  @Inject
  public GoalRevisionEvaluator(
      Instance<GoalSignalStore> goalSignalStore,
      Instance<GoalEvolution> goalEvolution,
      Instance<AgentRegistry> agentRegistry,
      CaseDefinitionRegistry caseDefinitionRegistry,
      EngineStrategyResolver strategyResolver,
      EventLogRepository eventLogRepository,
      @ConfigProperty(name = "casehub.engine.goal.revision.enabled", defaultValue = "false")
          boolean enabled,
      @ConfigProperty(name = "casehub.engine.goal.revision.strategy", defaultValue = "llm")
          String strategyId,
      @ConfigProperty(name = "casehub.engine.goal.revision.min-outcomes", defaultValue = "10")
          int minOutcomes,
      @ConfigProperty(
              name = "casehub.engine.goal.revision.importance-threshold",
              defaultValue = "3.0")
          double importanceThreshold) {
    this.goalSignalStore = goalSignalStore;
    this.goalEvolution = goalEvolution;
    this.agentRegistry = agentRegistry;
    this.caseDefinitionRegistry = caseDefinitionRegistry;
    this.strategyResolver = strategyResolver;
    this.eventLogRepository = eventLogRepository;
    this.enabled = enabled;
    this.strategyId = strategyId;
    this.minOutcomes = minOutcomes;
    this.importanceThreshold = importanceThreshold;
  }

  private static double resolveImportance(WorkerOutcome<?> outcome) {
    Map<String, Double> weights = ReflectionTriggerConfig.DEFAULT_IMPORTANCE_WEIGHTS;
    String kind = outcomeKindName(outcome);
    return weights.getOrDefault(kind, 0.3);
  }

  private static String outcomeKindName(WorkerOutcome<?> outcome) {
    return switch (outcome) {
      case WorkerOutcome.Success<?> s -> "SUCCESS";
      case WorkerOutcome.Completed<?> c -> "COMPLETED";
      case WorkerOutcome.Declined<?> d -> "DECLINED";
      case WorkerOutcome.Failed<?> f -> "FAILED";
      case WorkerOutcome.Expired<?> e -> "EXPIRED";
    };
  }

  public void record(
      CaseInstance caseInstance,
      String workerName,
      String capabilityName,
      WorkerOutcome<?> outcome) {
    if (!enabled) {
      return;
    }
    if (!goalSignalStore.isResolvable()) {
      return;
    }
    if (!goalEvolution.isResolvable()) {
      return;
    }
    if (!agentRegistry.isResolvable()) {
      return;
    }

    CaseDefinition definition;
    try {
      definition = caseDefinitionRegistry.getCaseDefinition(caseInstance.getCaseMetaModel());
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

    String agentId = descriptor.agentId();
    String tenancyId = caseInstance.tenancyId;
    String key = agentId + "|" + tenancyId;
    double importance = resolveImportance(outcome);

    boolean[] shouldTrigger = {false};
    states.compute(
        key,
        (k, state) -> {
          if (state == null) {
            state = new RevisionState();
          }
          state.outcomeCount++;
          state.cumulativeImportance += importance;
          if (state.outcomeCount >= minOutcomes
              || state.cumulativeImportance >= importanceThreshold) {
            state.outcomeCount = 0;
            state.cumulativeImportance = 0.0;
            state.lastRevisionTime = Instant.now();
            shouldTrigger[0] = true;
          }
          return state;
        });

    if (shouldTrigger[0]) {
      Thread.startVirtualThread(() -> evaluateRevision(agentId, tenancyId, definition));
    }
  }

  private void evaluateRevision(String agentId, String tenancyId, CaseDefinition definition) {
    ReentrantLock lock = locks.computeIfAbsent(agentId + "|" + tenancyId, k -> new ReentrantLock());
    lock.lock();
    try {
      Map<String, GoalOutcomeCounts> counts =
          goalSignalStore.get().outcomeCounts(agentId, tenancyId);
      Optional<AgentDescriptor> descriptorOpt = agentRegistry.get().findById(agentId, tenancyId);
      if (descriptorOpt.isEmpty()) {
        return;
      }

      AgentDescriptor descriptor = descriptorOpt.get();
      GoalEvolutionResult result = goalEvolution.get().evaluate(descriptor, counts);

      switch (result) {
        case GoalEvolutionResult.Evolved evolved ->
            handleEvolved(agentId, tenancyId, descriptor, evolved, counts, definition);
        case GoalEvolutionResult.Dampened dampened ->
            goalSignalStore.get().decay(agentId, tenancyId, dampened.decayFactor());
        case GoalEvolutionResult.Unchanged ignored -> {}
      }
    } catch (Exception e) {
      LOG.warnf(e, "Goal revision failed for agent %s", agentId);
    } finally {
      lock.unlock();
    }
  }

  private void handleEvolved(
      String agentId,
      String tenancyId,
      AgentDescriptor descriptor,
      GoalEvolutionResult.Evolved evolved,
      Map<String, GoalOutcomeCounts> counts,
      CaseDefinition definition) {

    List<AgentGoal> finalGoals = evolved.newGoals();

    GoalRevisionStrategy strategy = resolveStrategy();
    if (strategy != null) {
      try {
        GoalRevisionContext context =
            new GoalRevisionContext(agentId, tenancyId, finalGoals, counts, definition);
        GoalRevisionProposal proposal = strategy.revise(context);
        if (proposal != null && !proposal.revisions().isEmpty()) {
          finalGoals = mergeDescriptions(finalGoals, proposal);
        }
      } catch (Exception e) {
        LOG.warnf(e, "GoalRevisionStrategy failed for agent %s, applying priority-only", agentId);
      }
    }

    AgentDescriptor updated = descriptor.toBuilder().goals(finalGoals).build();
    agentRegistry.get().register(updated);
    goalSignalStore.get().clear(agentId, tenancyId);

    writeAuditLog(agentId, tenancyId, evolved, counts);
  }

  private List<AgentGoal> mergeDescriptions(List<AgentGoal> goals, GoalRevisionProposal proposal) {
    Map<String, String> descriptionsByGoal = new HashMap<>();
    for (var revision : proposal.revisions()) {
      if (revision.revisedDescription() != null) {
        descriptionsByGoal.put(revision.goalName(), revision.revisedDescription());
      }
    }
    if (descriptionsByGoal.isEmpty()) {
      return goals;
    }

    List<AgentGoal> merged = new ArrayList<>();
    for (AgentGoal goal : goals) {
      String newDesc = descriptionsByGoal.get(goal.name());
      if (newDesc != null) {
        try {
          merged.add(goal.toBuilder().description(newDesc).build());
        } catch (Exception e) {
          LOG.warnf(
              "Invalid description for goal %s, keeping original: %s", goal.name(), e.getMessage());
          merged.add(goal);
        }
      } else {
        merged.add(goal);
      }
    }
    return merged;
  }

  private GoalRevisionStrategy resolveStrategy() {
    try {
      return strategyResolver.resolve(GoalRevisionStrategy.class, strategyId);
    } catch (Exception e) {
      return null;
    }
  }

  private void writeAuditLog(
      String agentId,
      String tenancyId,
      GoalEvolutionResult.Evolved evolved,
      Map<String, GoalOutcomeCounts> counts) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put("agentId", agentId);
      metadata.put("evolutionResult", "EVOLVED");
      metadata.put("promotedGoals", evolved.promotedGoals());
      metadata.put("demotedGoals", evolved.demotedGoals());
      metadata.put(
          "totalGoalsRevised", evolved.promotedGoals().size() + evolved.demotedGoals().size());
      metadata.put("totalGoalsEvaluated", evolved.newGoals().size());

      EventLog eventLog = new EventLog();
      eventLog.setEventType(CaseHubEventType.GOAL_REVISED);
      eventLog.setPayload(mapper.valueToTree(metadata));
      eventLog.setTimestamp(Instant.now());
      eventLogRepository.append(eventLog, tenancyId);
    } catch (Exception e) {
      LOG.warnf(e, "Failed to write GOAL_REVISED audit log for agent %s", agentId);
    }
  }

  private static class RevisionState {
    int outcomeCount;
    double cumulativeImportance;
    Instant lastRevisionTime;
  }
}
