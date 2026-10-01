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

import io.casehub.api.model.stigmergy.CategoryDescriptor;
import io.casehub.api.model.stigmergy.StageDescriptor;
import io.casehub.api.spi.improvement.ImprovementCategoryProvider;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class CodeEvolutionCategoryProvider implements ImprovementCategoryProvider {

  @Override
  public String domainId() {
    return "code-evolution";
  }

  @Override
  public List<CategoryDescriptor> categories() {
    return List.of(
        new CategoryDescriptor(
            "dependency-update",
            "Dependency Update",
            "Bump outdated dependencies",
            "code-evolution"),
        new CategoryDescriptor(
            "lint-fix", "Lint Fix", "Fix linting and style violations", "code-evolution"),
        new CategoryDescriptor(
            "coverage-gap", "Coverage Gap", "Add tests for uncovered code", "code-evolution"),
        new CategoryDescriptor(
            "ci-triage", "CI Triage", "Fix CI pipeline failures", "code-evolution"),
        new CategoryDescriptor(
            "recipe", "Recipe", "Apply automated code transformation recipes", "code-evolution"));
  }

  @Override
  public List<StageDescriptor> stages() {
    return List.of(
        new StageDescriptor("introspect", "Introspect", 0, false, "code-evolution"),
        new StageDescriptor("research-scope", "Research Scope", 1, true, "code-evolution"),
        new StageDescriptor("search", "Search", 2, false, "code-evolution"),
        new StageDescriptor("analyze", "Analyze", 3, false, "code-evolution"),
        new StageDescriptor(
            "hypothesis-approval", "Hypothesis Approval", 4, true, "code-evolution"),
        new StageDescriptor(
            "implementation-plan", "Implementation Plan", 5, true, "code-evolution"),
        new StageDescriptor("implement", "Implement", 6, false, "code-evolution"),
        new StageDescriptor("submit-pr", "Submit PR", 7, false, "code-evolution"),
        new StageDescriptor("pr-review", "PR Review", 8, true, "code-evolution"),
        new StageDescriptor("integrate", "Integrate", 9, false, "code-evolution"),
        new StageDescriptor("outcome-recording", "Outcome Recording", 10, false, "code-evolution"));
  }
}
