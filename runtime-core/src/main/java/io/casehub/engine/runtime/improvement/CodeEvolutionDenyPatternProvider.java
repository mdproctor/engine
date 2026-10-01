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
import io.casehub.api.spi.improvement.DenyPatternProvider;
import io.casehub.engine.common.spi.DenyPatternStore;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CodeEvolutionDenyPatternProvider implements DenyPatternProvider {

  private static final Set<String> STRUCTURAL_DENIED_PATTERNS =
      Set.of(
          "ImprovementBudget",
          "ImprovementBudgetEnforcer",
          "ImprovementConfig",
          "SafetyConfig",
          "improvement-case-template",
          "EvolutionTicker",
          "ImprovementCircuitBreaker",
          "RegressionDetector",
          "ConfidenceScorer",
          "HealthScoreTracker",
          "HealthPolicy",
          "RollbackPolicy",
          "ImprovementCategoryTracker",
          "RollbackHistory",
          "self-improvement-rollback",
          "FilePathConflictStrategy",
          "CodeEvolutionDenyPatternProvider",
          "ConflictStrategyRegistry",
          "DenyPatternProviderRegistry",
          "ImprovementCategoryRegistry",
          "ImprovementProposalSourceRegistry",
          "RegressionEvaluatorRegistry",
          "CodeEvolutionCategoryProvider",
          "CodeEvolutionStages",
          "CodeEvolutionMetadata",
          "EvolutionBootstrap",
          "SignalConsensusProposalSource",
          "HealthScoreDeltaRegressionEvaluator");

  private final DenyPatternStore denyPatternStore;

  public CodeEvolutionDenyPatternProvider(DenyPatternStore denyPatternStore) {
    this.denyPatternStore = denyPatternStore;
  }

  @Override
  public String domainId() {
    return "code-evolution";
  }

  @Override
  public boolean isDenied(
      UUID caseId, String tenancyId, ImprovementRequest request, ImprovementConfig config) {
    List<String> paths = CodeEvolutionMetadata.extractPaths(request);
    String repo = CodeEvolutionMetadata.extractRepo(request);

    for (String path : paths) {
      for (String pattern : STRUCTURAL_DENIED_PATTERNS) {
        if (path.contains(pattern)) return true;
      }
      for (String pattern : denyPatternStore.findAll(caseId, tenancyId)) {
        if (path.contains(pattern)) return true;
      }
    }

    var budget = config.effectiveBudget();
    for (String path : paths) {
      for (String deniedPattern : budget.effectiveDeniedPaths()) {
        if (matchesGlob(path, deniedPattern)) return true;
      }
    }

    if (!budget.effectiveAllowedRepos().isEmpty()
        && repo != null
        && !budget.effectiveAllowedRepos().contains(repo)) {
      return true;
    }

    return false;
  }

  private static boolean matchesGlob(String path, String glob) {
    String regex =
        glob.replace(".", "\\.")
            .replace("**", "@@DOUBLESTAR@@")
            .replace("*", "[^/]*")
            .replace("@@DOUBLESTAR@@", ".*");
    return path.matches(regex);
  }
}
