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
package io.casehub.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JudgmentTargetTest {

  @Test
  void builder_staticPrompt_builds() {
    JudgmentTarget target =
        JudgmentTarget.builder()
            .prompt("Assess the risk level")
            .inputMapping(".")
            .outputMapping(".riskAssessment")
            .expiresIn(Duration.ofHours(1))
            .evidenceRequirements(List.of("riskScore", "rationale"))
            .build();
    assertThat(target.prompt()).isEqualTo("Assess the risk level");
    assertThat(target.expiresIn()).isEqualTo(Duration.ofHours(1));
    assertThat(target.evidenceRequirements()).containsExactly("riskScore", "rationale");
    assertThat(target).isInstanceOf(BindingTarget.class);
  }

  @Test
  void builder_rejectsNullPromptAndExpression() {
    assertThatThrownBy(() -> JudgmentTarget.builder().build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void builder_rejectsBothPromptAndExpression() {
    assertThatThrownBy(
            () -> JudgmentTarget.builder().prompt("static").promptExpression(".dynamic").build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void builder_rejectsBothExpiresInAndExpression() {
    assertThatThrownBy(
            () ->
                JudgmentTarget.builder()
                    .prompt("question")
                    .expiresIn(Duration.ofHours(1))
                    .expiresInExpression(".deadline")
                    .build())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void evidenceRequirements_isImmutable() {
    var target =
        JudgmentTarget.builder().prompt("question").evidenceRequirements(List.of("a")).build();
    assertThatThrownBy(() -> target.evidenceRequirements().add("b"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void builder_dynamicPrompt_builds() {
    JudgmentTarget target = JudgmentTarget.builder().promptExpression(".context.question").build();
    assertThat(target.prompt()).isNull();
    assertThat(target.promptExpression()).isNotNull();
  }

  @Test
  void builder_noEvidenceRequirements_defaultsToEmpty() {
    var target = JudgmentTarget.builder().prompt("question").build();
    assertThat(target.evidenceRequirements()).isEmpty();
  }

  @Test
  void maxEscalationAttempts_defaultsTo3() {
    var target = JudgmentTarget.builder().prompt("test").build();
    assertThat(target.maxEscalationAttempts()).isEqualTo(3);
  }

  @Test
  void maxEscalationAttempts_custom() {
    var target = JudgmentTarget.builder().prompt("test").maxEscalationAttempts(10).build();
    assertThat(target.maxEscalationAttempts()).isEqualTo(10);
  }
}
