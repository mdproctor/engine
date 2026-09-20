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
package io.casehub.engine.internal.improvement;

import io.casehub.api.model.stigmergy.ImprovementConfig;
import io.casehub.api.model.stigmergy.ImprovementRequest;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.GoalPriority;
import io.casehub.engine.common.internal.signal.SignalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

@ApplicationScoped
public class ImprovementGoalFormationStrategy implements GoalFormationStrategy {

  private final ImprovementBudgetEnforcer budgetEnforcer;
  private final SignalRegistry signalRegistry;
  private final ImprovementSignalContext signalContext;
  private final ImprovementCategoryTracker categoryTracker;
  private final RollbackHistory rollbackHistory;
  private final ConflictDetector conflictDetector;

  @Inject
  public ImprovementGoalFormationStrategy(
      ImprovementBudgetEnforcer budgetEnforcer,
      SignalRegistry signalRegistry,
      ImprovementSignalContext signalContext,
      ImprovementCategoryTracker categoryTracker,
      RollbackHistory rollbackHistory,
      ConflictDetector conflictDetector) {
    this.budgetEnforcer = budgetEnforcer;
    this.signalRegistry = signalRegistry;
    this.signalContext = signalContext;
    this.categoryTracker = categoryTracker;
    this.rollbackHistory = rollbackHistory;
    this.conflictDetector = conflictDetector;
  }

  public ImprovementGoalFormationStrategy(
      ImprovementBudgetEnforcer budgetEnforcer,
      SignalRegistry signalRegistry,
      ImprovementSignalContext signalContext) {
    this(
        budgetEnforcer,
        signalRegistry,
        signalContext,
        new ImprovementCategoryTracker(),
        new RollbackHistory(),
        new ConflictDetector());
  }

  @Override
  public String id() {
    return "self-improvement";
  }

  @Override
  public GoalFormationProposal propose(GoalFormationContext context) {
    return null;
  }

  public GoalFormationProposal proposeImprovements(UUID caseId, ImprovementConfig config) {
    String namespace = config.effectiveSignalNamespace();
    int minSources = config.effectiveConsensusMinSources();
    var consensus = signalRegistry.consensusSignals(caseId, minSources, 0.01);

    List<GoalFormationProposal.ProposedGoal> goals = new ArrayList<>();
    for (var entry : consensus.entrySet()) {
      String signalName = entry.getKey();
      if (!signalName.startsWith(namespace + ":")) {
        continue;
      }

      Optional<ImprovementRequest> ctxOpt = signalContext.get(caseId, signalName);
      if (ctxOpt.isEmpty()) {
        continue;
      }

      ImprovementRequest request = ctxOpt.get();
      if (!config.effectiveEnabledCategories().contains(request.category())) {
        continue;
      }

      if (categoryTracker.isSuppressed(caseId, request.category())) {
        continue;
      }

      if (rollbackHistory.wasRecentlyRolledBack(
          caseId,
          request.category(),
          request.target(),
          java.time.Duration.ofMinutes(
              config.effectiveRollbackPolicy().effectiveRegressionWindowMinutes()))) {
        continue;
      }

      var budgetCheck = budgetEnforcer.check(caseId, config.effectiveBudget(), request);
      if (budgetCheck instanceof ImprovementBudgetEnforcer.BudgetCheck.Denied) {
        continue;
      }

      var conflictCheck =
          conflictDetector.check(
              request,
              budgetEnforcer.activeImprovementRequests(),
              config.effectiveConflictTrivialThreshold());
      if (conflictCheck instanceof ConflictDetector.ConflictCheck.Conflicting) {
        continue;
      }

      Map<String, String> attributes = new LinkedHashMap<>();
      attributes.put("improvement.type", request.improvementType());
      attributes.put("improvement.category", request.category());
      attributes.put("improvement.target", request.target());
      attributes.put("improvement.targetRepo", request.targetRepo());
      attributes.put("improvement.signalName", signalName);

      goals.add(
          new GoalFormationProposal.ProposedGoal(
              "self_improvement:" + request.category() + ":" + request.target(),
              "Improve " + request.category() + " for " + request.target(),
              GoalPriority.SECONDARY,
              "Signal consensus reached for " + signalName,
              attributes));
    }

    if (goals.isEmpty()) {
      return null;
    }

    return new GoalFormationProposal(
        goals,
        "Improvement signal consensus detected — " + goals.size() + " improvement(s) proposed");
  }
}
