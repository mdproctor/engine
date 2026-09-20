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
package io.casehub.api.model.stigmergy;

import jakarta.annotation.Nullable;
import java.util.List;

public record ImprovementConfig(
    @Nullable String signalNamespace,
    @Nullable Integer consensusMinSources,
    @Nullable List<String> enabledCategories,
    @Nullable ImprovementBudget budget,
    @Nullable String caseTemplateId) {

  public String effectiveSignalNamespace() {
    return signalNamespace != null ? signalNamespace : "improvement";
  }

  public int effectiveConsensusMinSources() {
    return consensusMinSources != null ? consensusMinSources : 2;
  }

  public List<String> effectiveEnabledCategories() {
    return enabledCategories != null
        ? enabledCategories
        : List.of("dependency-update", "lint-fix", "coverage-gap", "ci-triage", "recipe");
  }

  public ImprovementBudget effectiveBudget() {
    return budget != null
        ? budget
        : new ImprovementBudget(null, null, null, null, null, null, null);
  }

  public String effectiveCaseTemplateId() {
    return caseTemplateId != null ? caseTemplateId : "self-improvement";
  }
}
