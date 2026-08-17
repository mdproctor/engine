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
package io.casehub.engine.common.goap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.casehub.engine.plan.goap.GoapAction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GoapActionInferrerTest {

  @Test
  void infer_simple_action() {
    GoapAction action =
        GoapActionInferrer.infer(
            "analyse", List.of(String.class), AnalysisResult.class, 0.3, 0.0, Set.of());

    assertThat(action.name()).isEqualTo("analyse");
    assertThat(action.preconditions()).isEmpty();
    assertThat(action.effects()).containsEntry("analysisResult", true);
    assertThat(action.cost()).isEqualTo(0.3);
  }

  @Test
  void infer_with_dependency() {
    GoapAction action =
        GoapActionInferrer.infer(
            "assess",
            List.of(AnalysisResult.class, String.class),
            RiskAssessment.class,
            0.5,
            0.0,
            Set.of());

    assertThat(action.preconditions()).containsEntry("analysisResult", true);
    assertThat(action.preconditions()).doesNotContainKey("string");
    assertThat(action.effects()).containsEntry("riskAssessment", true);
  }

  @Test
  void infer_soft_dependency() {
    GoapAction action =
        GoapActionInferrer.infer(
            "assess",
            List.of(AnalysisResult.class),
            RiskAssessment.class,
            0.5,
            0.0,
            Set.of(AnalysisResult.class));

    assertThat(action.preconditions()).isEmpty();
    assertThat(action.softPreconditions()).containsEntry("analysisResult", true);
  }

  @Test
  void isInputParameter_string() {
    assertThat(GoapActionInferrer.isInputParameter(String.class)).isTrue();
  }

  @Test
  void isInputParameter_int() {
    assertThat(GoapActionInferrer.isInputParameter(int.class)).isTrue();
  }

  @Test
  void isInputParameter_map() {
    assertThat(GoapActionInferrer.isInputParameter(Map.class)).isTrue();
  }

  @Test
  void isInputParameter_domain_type() {
    assertThat(GoapActionInferrer.isInputParameter(AnalysisResult.class)).isFalse();
  }

  @Test
  void infer_with_benefit() {
    GoapAction action =
        GoapActionInferrer.infer("a", List.of(), AnalysisResult.class, 0.5, 0.8, Set.of());
    assertThat(action.benefit()).isEqualTo(0.8);
    assertThat(action.effectiveCost()).isCloseTo(0.1, within(0.001));
  }

  @Test
  void infer_void_output_no_effects() {
    GoapAction action =
        GoapActionInferrer.infer(
            "sideEffect", List.of(String.class), void.class, 0.1, 0.0, Set.of());
    assertThat(action.effects()).isEmpty();
  }

  record AnalysisResult(String summary) {}

  record RiskAssessment(String level) {}
}
