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

import io.casehub.api.model.improvement.ImprovementConfig;
import io.casehub.api.model.improvement.ImprovementRequest;
import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.spi.improvement.ConflictStrategy;
import io.casehub.api.spi.routing.GoalFormationContext;
import io.casehub.api.spi.routing.GoalFormationProposal;
import io.casehub.api.spi.routing.GoalFormationStrategy;
import io.casehub.eidos.api.GoalPriority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImprovementGoalFormationStrategy implements GoalFormationStrategy {

  private final ImprovementBudgetEnforcer budgetEnforcer;
  private final ImprovementProposalSourceRegistry proposalSourceRegistry;
  private final ImprovementCategoryRegistry categoryRegistry;
  private final ImprovementCategoryTracker categoryTracker;
  private final RollbackHistory rollbackHistory;
  private final ConflictStrategyRegistry conflictStrategyRegistry;
  private final DenyPatternProviderRegistry denyPatternProviderRegistry;

  @Inject
  public ImprovementGoalFormationStrategy(
      ImprovementBudgetEnforcer budgetEnforcer,
      ImprovementProposalSourceRegistry proposalSourceRegistry,
      ImprovementCategoryRegistry categoryRegistry,
      ImprovementCategoryTracker categoryTracker,
      RollbackHistory rollbackHistory,
      ConflictStrategyRegistry conflictStrategyRegistry,
      DenyPatternProviderRegistry denyPatternProviderRegistry) {
    this.budgetEnforcer = budgetEnforcer;
    this.proposalSourceRegistry = proposalSourceRegistry;
    this.categoryRegistry = categoryRegistry;
    this.categoryTracker = categoryTracker;
    this.rollbackHistory = rollbackHistory;
    this.conflictStrategyRegistry = conflictStrategyRegistry;
    this.denyPatternProviderRegistry = denyPatternProviderRegistry;
  }

  @Override
  public String id() {
    return "self-improvement";
  }

  @Override
  public GoalFormationProposal propose(GoalFormationContext context) {
    return null;
  }

  public GoalFormationProposal proposeImprovements(
      UUID caseId, String tenancyId, ImprovementConfig config) {
    List<ImprovementRequest> allProposals = new ArrayList<>();
    Map<String, Integer> proposalsBySource = new LinkedHashMap<>();
    for (var source : proposalSourceRegistry.all()) {
      var sourceProposals = source.propose(caseId, tenancyId, config);
      proposalsBySource.put(source.sourceId(), sourceProposals.size());
      allProposals.addAll(sourceProposals);
    }

    var enabledCategories = effectiveEnabledCategories(config);

    List<ImprovementRequest> afterCategory =
        allProposals.stream().filter(r -> enabledCategories.contains(r.category())).toList();

    List<ImprovementRequest> afterSuppression = new ArrayList<>();
    for (var request : afterCategory) {
      if (!categoryTracker.isSuppressed(caseId, request.category())) {
        afterSuppression.add(request);
      }
    }

    List<ImprovementRequest> afterAntiOscillation = new ArrayList<>();
    for (var request : afterSuppression) {
      if (!rollbackHistory.wasRecentlyRolledBack(
          caseId,
          request.category(),
          request.target(),
          java.time.Duration.ofMinutes(
              config.effectiveRollbackPolicy().effectiveRegressionWindowMinutes()))) {
        afterAntiOscillation.add(request);
      }
    }

    List<ImprovementRequest> afterDeny = new ArrayList<>();
    for (var request : afterAntiOscillation) {
      String domainId = request.domainId();
      if (domainId != null) {
        var provider = denyPatternProviderRegistry.forDomain(domainId);
        if (provider.isPresent() && provider.get().isDenied(caseId, tenancyId, request, config)) {
          continue;
        }
      }
      afterDeny.add(request);
    }

    List<ImprovementRequest> afterBudget = new ArrayList<>();
    for (var request : afterDeny) {
      var budgetCheck = budgetEnforcer.check(caseId, config.effectiveBudget(), request, tenancyId);
      if (budgetCheck instanceof ImprovementBudgetEnforcer.BudgetCheck.Denied) {
        continue;
      }
      afterBudget.add(request);
    }

    List<ImprovementRequest> afterConflict = new ArrayList<>();
    for (var request : afterBudget) {
      String domainId = request.domainId();
      if (domainId != null) {
        var strategyOpt = conflictStrategyRegistry.forDomain(domainId);
        if (strategyOpt.isPresent()) {
          var check =
              strategyOpt
                  .get()
                  .check(
                      request,
                      budgetEnforcer.activeImprovementRequests(),
                      config.effectiveConflictTrivialThreshold());
          if (check instanceof ConflictStrategy.ConflictResult.Conflicting) {
            continue;
          }
        }
      }
      afterConflict.add(request);
    }

    List<GoalFormationProposal.ProposedGoal> goals = new ArrayList<>();
    for (var request : afterConflict) {
      Map<String, String> attributes = new LinkedHashMap<>();
      attributes.put("improvement.type", request.improvementType());
      attributes.put("improvement.category", request.category());
      attributes.put("improvement.target", request.target());

      goals.add(
          new GoalFormationProposal.ProposedGoal(
              "self_improvement:" + request.category() + ":" + request.target(),
              "Improve " + request.category() + " for " + request.target(),
              GoalPriority.SECONDARY,
              "Proposal from source",
              attributes));
    }

    if (goals.isEmpty()) {
      return null;
    }

    return new GoalFormationProposal(
        goals, "Improvement proposals detected — " + goals.size() + " improvement(s) proposed");
  }

  private Set<String> effectiveEnabledCategories(ImprovementConfig config) {
    if (config.enabledCategories() != null) {
      return Set.copyOf(config.enabledCategories());
    }
    return categoryRegistry.allCategories().stream()
        .map(CategoryDescriptor::id)
        .collect(Collectors.toSet());
  }
}
