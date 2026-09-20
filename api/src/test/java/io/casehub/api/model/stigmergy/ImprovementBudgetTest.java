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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ImprovementBudgetTest {

  @Test
  void defaultsApplyWhenFieldsNull() {
    var budget = new ImprovementBudget(null, null, null, null, null, null, null);
    assertThat(budget.effectiveMaxConcurrent()).isEqualTo(3);
    assertThat(budget.effectiveMaxPerDay()).isEqualTo(10);
    assertThat(budget.effectiveCooldownMinutes()).isEqualTo(30);
    assertThat(budget.effectiveAllowedRepos()).isEmpty();
    assertThat(budget.effectiveDeniedPaths()).isEmpty();
    assertThat(budget.effectiveRequireReview()).isTrue();
    assertThat(budget.effectiveMaxPRSize()).isEqualTo(500);
  }

  @Test
  void explicitValuesOverrideDefaults() {
    var budget =
        new ImprovementBudget(
            5, 20, 60, List.of("casehubio/engine"), List.of("**/test/**"), false, 1000);
    assertThat(budget.effectiveMaxConcurrent()).isEqualTo(5);
    assertThat(budget.effectiveMaxPerDay()).isEqualTo(20);
    assertThat(budget.effectiveCooldownMinutes()).isEqualTo(60);
    assertThat(budget.effectiveAllowedRepos()).containsExactly("casehubio/engine");
    assertThat(budget.effectiveDeniedPaths()).containsExactly("**/test/**");
    assertThat(budget.effectiveRequireReview()).isFalse();
    assertThat(budget.effectiveMaxPRSize()).isEqualTo(1000);
  }

  @Test
  void improvementConfigDefaults() {
    var config = new ImprovementConfig(null, null, null, null, null);
    assertThat(config.effectiveSignalNamespace()).isEqualTo("improvement");
    assertThat(config.effectiveConsensusMinSources()).isEqualTo(2);
    assertThat(config.effectiveEnabledCategories())
        .containsExactly("dependency-update", "lint-fix", "coverage-gap", "ci-triage", "recipe");
    assertThat(config.effectiveBudget()).isNotNull();
    assertThat(config.effectiveCaseTemplateId()).isEqualTo("self-improvement");
  }

  @Test
  void stigmergyConfigBackwardCompatible() {
    var config = new StigmergyConfig(null, null, null);
    assertThat(config.improvement()).isNull();
    assertThat(config.swarm()).isNull();
  }

  @Test
  void stigmergyConfigWithImprovement() {
    var improvement = new ImprovementConfig(null, null, null, null, null);
    var config = new StigmergyConfig(null, null, null, improvement);
    assertThat(config.improvement()).isNotNull();
    assertThat(config.improvement().effectiveSignalNamespace()).isEqualTo("improvement");
  }
}
