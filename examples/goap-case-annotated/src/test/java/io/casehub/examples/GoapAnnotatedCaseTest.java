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
                      GoapAnnotatedCase.RiskAssessment.class));

  @Inject CaseDefinition definition;

  @Test
  void goap_planning_strategy() {
    assertThat(definition.getPlanningStrategy()).isEqualTo("goap");
  }

  @Test
  void three_workers() {
    assertThat(definition.getWorkers()).hasSize(3);
    assertThat(definition.getWorkers())
        .extracting(w -> w.name())
        .containsExactlyInAnyOrder("analyse", "extract", "assess");
  }

  @Test
  void three_capabilities() {
    assertThat(definition.getCapabilities()).hasSize(3);
    assertThat(definition.getCapabilities())
        .extracting(c -> c.name())
        .containsExactlyInAnyOrder("analyse", "extractClauses", "assessRisk");
  }

  @Test
  void auto_generated_bindings() {
    assertThat(definition.getBindings()).hasSize(3);
  }

  @Test
  void goap_actions_inferred() {
    assertThat(definition.getGoapActions()).hasSize(3);

    var analyseAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("analyse")).findFirst();
    assertThat(analyseAction).isPresent();
    assertThat(analyseAction.get().preconditions()).isEmpty();
    assertThat(analyseAction.get().effects()).containsKey("analysisResult");
    assertThat(analyseAction.get().cost()).isEqualTo(0.2);

    var extractAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("extract")).findFirst();
    assertThat(extractAction).isPresent();
    assertThat(extractAction.get().preconditions()).containsKey("analysisResult");
    assertThat(extractAction.get().effects()).containsKey("clauseList");

    var assessAction =
        definition.getGoapActions().stream().filter(a -> a.name().equals("assess")).findFirst();
    assertThat(assessAction).isPresent();
    assertThat(assessAction.get().preconditions()).containsKeys("analysisResult", "clauseList");
    assertThat(assessAction.get().effects()).containsKey("riskAssessment");
  }

  @Test
  void goal_to_effect_keys_populated() {
    assertThat(definition.getGoalToEffectKeys()).isNotEmpty();
    assertThat(definition.getGoalToEffectKeys().get("done")).contains("riskAssessment");
  }

  @Test
  void goal_generated() {
    assertThat(definition.getGoals()).hasSize(1);
    assertThat(definition.getGoals().get(0).getName()).isEqualTo("done");
  }
}
