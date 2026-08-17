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
package io.casehub.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class GoapAnnotatedCaseTest {

  @RegisterExtension
  static final QuarkusUnitTest test =
      new QuarkusUnitTest()
          .withApplicationRoot(
              root ->
                  root.addClasses(
                      GoapAnnotatedCase.class,
                      GoapAnnotatedCase.AnalysisResult.class,
                      GoapAnnotatedCase.ClauseList.class,
                      GoapAnnotatedCase.RiskReport.class,
                      GoapAnnotatedCase.PriorReview.class));

  @Inject CaseDefinition definition;

  @Test
  void goap_planning_strategy() {
    assertThat(definition.getPlanningStrategy()).isEqualTo("goap");
  }

  @Test
  void three_workers_with_legal_domain() {
    assertThat(definition.getWorkers()).hasSize(3);
    assertThat(definition.getWorkers())
        .extracting(w -> w.name())
        .containsExactlyInAnyOrder("analyse", "extractClauses", "assessRisk");
  }

  @Test
  void worker_descriptions() {
    var analyse =
        definition.getWorkers().stream().filter(w -> w.name().equals("analyse")).findFirst();
    assertThat(analyse).isPresent();
    assertThat(analyse.get().description()).contains("contract structure");
  }

  @Test
  void goap_actions_with_cost_and_benefit() {
    assertThat(definition.getGoapActions()).hasSize(3);

    var analyseAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("analyse")).findFirst();
    assertThat(analyseAction).isPresent();
    assertThat(analyseAction.get().preconditions()).isEmpty();
    assertThat(analyseAction.get().effects()).containsKey("analysisResult");
    assertThat(analyseAction.get().cost()).isEqualTo(0.2);
    assertThat(analyseAction.get().benefit()).isEqualTo(0.1);
  }

  @Test
  void effect_annotation_overrides_inferred_key() {
    var assessAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("assessRisk")).findFirst();
    assertThat(assessAction).isPresent();
    assertThat(assessAction.get().effects()).containsKey("riskAssessment");
    assertThat(assessAction.get().effects()).doesNotContainKey("riskReport");
  }

  @Test
  void soft_dependency_excluded_from_hard_preconditions() {
    var assessAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("assessRisk")).findFirst();
    assertThat(assessAction).isPresent();
    assertThat(assessAction.get().preconditions()).containsKeys("analysisResult", "clauseList");
    assertThat(assessAction.get().preconditions()).doesNotContainKey("priorReview");
    assertThat(assessAction.get().softPreconditions()).containsKey("priorReview");
  }

  @Test
  void param_excluded_from_goap_inference() {
    var assessAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("assessRisk")).findFirst();
    assertThat(assessAction).isPresent();
    assertThat(assessAction.get().preconditions()).doesNotContainKey("jurisdiction");
    assertThat(assessAction.get().softPreconditions()).doesNotContainKey("jurisdiction");
  }

  @Test
  void goal_to_effect_keys_from_condition() {
    assertThat(definition.getGoalToEffectKeys()).isNotEmpty();
    assertThat(definition.getGoalToEffectKeys().get("reviewComplete")).contains("riskAssessment");
  }

  @Test
  void completion_wired_from_default_method() {
    assertThat(definition.getCompletion()).isNotNull();
  }
}
