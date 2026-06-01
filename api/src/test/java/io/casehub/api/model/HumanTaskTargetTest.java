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

import io.casehub.api.model.evaluator.ExpressionEvaluator;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HumanTaskTargetTest {

  @Test
  void templateMode_storedTemplateRef() {
    HumanTaskTarget target = HumanTaskTarget.template("irb-72h-review").build();

    assertThat(target.templateRef()).isEqualTo("irb-72h-review");
    assertThat(target.isTemplateMode()).isTrue();
    assertThat(target.title()).isNull();
  }

  @Test
  void inlineMode_storedTitle() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("IRB Ethics Review").build();

    assertThat(target.isTemplateMode()).isFalse();
    assertThat(target.title()).isEqualTo("IRB Ethics Review");
    assertThat(target.templateRef()).isNull();
  }

  @Test
  void inputMapping_stringBecomesJQEvaluator() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").inputMapping(".trialId").build();

    assertThat(target.inputMapping()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.inputMapping()).expression()).isEqualTo(".trialId");
  }

  @Test
  void inputMapping_evaluatorInstanceStoredDirectly() {
    ExpressionEvaluator evaluator = new JQExpressionEvaluator(".x");
    HumanTaskTarget target = HumanTaskTarget.template("t1").inputMapping(evaluator).build();

    assertThat(target.inputMapping()).isSameAs(evaluator);
  }

  @Test
  void outputMapping_stringBecomesJQEvaluator() {
    HumanTaskTarget target =
        HumanTaskTarget.template("t1").outputMapping("{ outcome: .decision }").build();

    assertThat(target.outputMapping()).isInstanceOf(JQExpressionEvaluator.class);
  }

  @Test
  void outputMapping_evaluatorInstanceStoredDirectly() {
    ExpressionEvaluator evaluator = new JQExpressionEvaluator(".y");
    HumanTaskTarget target = HumanTaskTarget.template("t1").outputMapping(evaluator).build();

    assertThat(target.outputMapping()).isSameAs(evaluator);
  }

  @Test
  void priority_storedAsString() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").priority("HIGH").build();

    assertThat(target.priority()).isEqualTo("HIGH");
  }

  @Test
  void inlineMode_candidateGroups_andExpiresIn() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .candidateGroups(Set.of("ethics-committee"))
            .expiresIn(Duration.ofHours(72))
            .build();

    assertThat(target.candidateGroups()).containsExactlyInAnyOrder("ethics-committee");
    assertThat(target.expiresIn()).isEqualTo(Duration.ofHours(72));
  }

  @Test
  void inlineMode_withoutTitle_throws() {
    assertThatThrownBy(() -> HumanTaskTarget.inline().build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("title");
  }

  @Test
  void templateMode_withoutTemplateRef_notPossible() {
    // template(String) requires a non-null, non-blank ref at factory entry — no separate build
    // validation
    assertThatThrownBy(() -> HumanTaskTarget.template(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> HumanTaskTarget.template(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void inputAndOutputMapping_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.inputMapping()).isNull();
    assertThat(target.outputMapping()).isNull();
    assertThat(target.priority()).isNull();
  }

  @Test
  void isBindingTarget() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();
    assertThat(target).isInstanceOf(BindingTarget.class);
  }

  @Test
  void scope_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").scope("casehubio/devtown/pr-review").build();

    assertThat(target.scope()).isEqualTo("casehubio/devtown/pr-review");
  }

  @Test
  void scope_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.scope()).isNull();
  }

  @Test
  void scope_templateMode_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.template("irb-template").scope("casehubio/clinical/adverse-event").build();

    assertThat(target.scope()).isEqualTo("casehubio/clinical/adverse-event");
    assertThat(target.isTemplateMode()).isTrue();
  }

  @Test
  void claimDeadlineHours_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Urgent Review").claimDeadlineHours(4).build();

    assertThat(target.claimDeadlineHours()).isEqualTo(4);
  }

  @Test
  void claimDeadlineHours_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.claimDeadlineHours()).isNull();
  }
}
