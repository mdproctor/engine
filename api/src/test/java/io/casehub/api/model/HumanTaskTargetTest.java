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

import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import io.casehub.api.spi.routing.CandidateSetSpec;
import io.casehub.api.spi.routing.JqCandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.platform.api.expression.ExpressionEvaluator;
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
  void candidateGroups_staticSet_wrapsInInlineStaticStrategy() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .candidateGroups(Set.of("ethics-committee"))
            .expiresIn(Duration.ofHours(72))
            .build();

    assertThat(target.candidateGroups()).isInstanceOf(CandidateSetSpec.Inline.class);
    CandidateSetSpec.Inline inline = (CandidateSetSpec.Inline) target.candidateGroups();
    assertThat(inline.strategy()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) inline.strategy()).values())
        .containsExactlyInAnyOrder("ethics-committee");
    assertThat(target.expiresIn()).isEqualTo(Duration.ofHours(72));
  }

  @Test
  void candidateGroups_jqExpression_wrapsInInlineJqStrategy() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").candidateGroupsExpression(".irb.groups").build();

    assertThat(target.candidateGroups()).isInstanceOf(CandidateSetSpec.Inline.class);
    CandidateSetSpec.Inline inline = (CandidateSetSpec.Inline) target.candidateGroups();
    assertThat(inline.strategy()).isInstanceOf(JqCandidateSetStrategy.class);
    assertThat(((JqCandidateSetStrategy) inline.strategy()).expression()).isEqualTo(".irb.groups");
  }

  @Test
  void candidateGroups_absent_returnsNull() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();

    assertThat(target.candidateGroups()).isNull();
  }

  @Test
  void candidateUsers_staticSet_wrapsInInlineStaticStrategy() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").candidateUsers(Set.of("user-a")).build();

    assertThat(target.candidateUsers()).isInstanceOf(CandidateSetSpec.Inline.class);
    CandidateSetSpec.Inline inline = (CandidateSetSpec.Inline) target.candidateUsers();
    assertThat(inline.strategy()).isInstanceOf(StaticSetStrategy.class);
    assertThat(((StaticSetStrategy) inline.strategy()).values())
        .containsExactlyInAnyOrder("user-a");
  }

  @Test
  void candidateUsers_jqExpression_wrapsInInlineJqStrategy() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .candidateUsersExpression(".approver.id | [.]")
            .build();

    assertThat(target.candidateUsers()).isInstanceOf(CandidateSetSpec.Inline.class);
    CandidateSetSpec.Inline inline = (CandidateSetSpec.Inline) target.candidateUsers();
    assertThat(inline.strategy()).isInstanceOf(JqCandidateSetStrategy.class);
    assertThat(((JqCandidateSetStrategy) inline.strategy()).expression())
        .isEqualTo(".approver.id | [.]");
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

  @Test
  void outcomes_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Gate Review")
            .outcomes(Set.of("APPROVED", "REJECTED", "BLOCKED"))
            .build();

    assertThat(target.outcomes()).containsExactlyInAnyOrder("APPROVED", "REJECTED", "BLOCKED");
  }

  @Test
  void outcomes_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.outcomes()).isNull();
  }

  @Test
  void outcomes_templateMode_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.template("gate-template").outcomes(Set.of("APPROVED", "REJECTED")).build();

    assertThat(target.outcomes()).containsExactlyInAnyOrder("APPROVED", "REJECTED");
    assertThat(target.isTemplateMode()).isTrue();
  }

  @Test
  void titleExpression_stringBecomesJQEvaluator() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("fallback")
            .titleExpression("\"IRB Review — \" + .protocol.id")
            .build();

    assertThat(target.titleExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.titleExpression()).expression())
        .isEqualTo("\"IRB Review — \" + .protocol.id");
  }

  @Test
  void titleExpression_evaluatorInstanceStoredDirectly() {
    ExpressionEvaluator evaluator = new JQExpressionEvaluator(".title");
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("fallback").titleExpression(evaluator).build();

    assertThat(target.titleExpression()).isSameAs(evaluator);
  }

  @Test
  void titleExpression_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.titleExpression()).isNull();
  }

  @Test
  void inlineMode_withTitleExpression_noStaticTitle_builds() {
    HumanTaskTarget target = HumanTaskTarget.inline().titleExpression(".dynamicTitle").build();

    assertThat(target.title()).isNull();
    assertThat(target.titleExpression()).isNotNull();
  }

  @Test
  void scopeExpression_stringBecomesJQEvaluator() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").scopeExpression(".trial.site.code").build();

    assertThat(target.scopeExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.scopeExpression()).expression())
        .isEqualTo(".trial.site.code");
  }

  @Test
  void scopeExpression_evaluatorInstanceStoredDirectly() {
    ExpressionEvaluator evaluator = new JQExpressionEvaluator(".scope");
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").scopeExpression(evaluator).build();

    assertThat(target.scopeExpression()).isSameAs(evaluator);
  }

  @Test
  void scopeExpression_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.scopeExpression()).isNull();
  }

  @Test
  void expiresInExpression_stringBecomesJQEvaluator() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .expiresInExpression(".trial.regulatoryDeadlineDuration")
            .build();

    assertThat(target.expiresInExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.expiresInExpression()).expression())
        .isEqualTo(".trial.regulatoryDeadlineDuration");
  }

  @Test
  void expiresInExpression_evaluatorInstanceStoredDirectly() {
    ExpressionEvaluator evaluator = new JQExpressionEvaluator(".deadline");
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").expiresInExpression(evaluator).build();

    assertThat(target.expiresInExpression()).isSameAs(evaluator);
  }

  @Test
  void expiresInExpression_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.template("t1").build();

    assertThat(target.expiresInExpression()).isNull();
  }

  @Test
  void expiresInExpression_templateMode_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.template("irb-template").expiresInExpression(".sla.reviewWindow").build();

    assertThat(target.isTemplateMode()).isTrue();
    assertThat(target.expiresInExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.expiresInExpression()).expression())
        .isEqualTo(".sla.reviewWindow");
  }

  @Test
  void expiresInExpression_inlineMode_storedAndReturned() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Urgent Review")
            .expiresInExpression(".urgency.deadline")
            .build();

    assertThat(target.isTemplateMode()).isFalse();
    assertThat(target.expiresInExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.expiresInExpression()).expression())
        .isEqualTo(".urgency.deadline");
  }

  @Test
  void expiresIn_andExpiresInExpression_mutuallyExclusive() {
    assertThatThrownBy(
            () ->
                HumanTaskTarget.inline()
                    .title("Review")
                    .expiresIn(Duration.ofHours(24))
                    .expiresInExpression(".sla.window")
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot specify both")
        .hasMessageContaining("expiresIn")
        .hasMessageContaining("expiresInExpression");
  }

  @Test
  void expiresInExpression_withoutExpiresIn_builds() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").expiresInExpression(".sla.window").build();

    assertThat(target.expiresIn()).isNull();
    assertThat(target.expiresInExpression()).isNotNull();
  }

  @Test
  void expiresIn_withoutExpiresInExpression_builds() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").expiresIn(Duration.ofHours(72)).build();

    assertThat(target.expiresIn()).isEqualTo(Duration.ofHours(72));
    assertThat(target.expiresInExpression()).isNull();
  }

  @Test
  void expiresInExpression_coexistsWithExpiresAtExpression() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .expiresInExpression(".sla.window")
            .expiresAtExpression(".regulatory.absoluteDeadline")
            .build();

    assertThat(target.expiresInExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(target.expiresAtExpression()).isInstanceOf(JQExpressionEvaluator.class);
  }

  @Test
  void expiresInExpression_thenExpiresIn_mutuallyExclusive_reversedOrder() {
    assertThatThrownBy(
            () ->
                HumanTaskTarget.inline()
                    .title("Review")
                    .expiresInExpression(".sla.window")
                    .expiresIn(Duration.ofHours(24))
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot specify both");
  }

  @Test
  void expiresIn_expiresInExpression_expiresAtExpression_allThree_throwsOnFirst() {
    assertThatThrownBy(
            () ->
                HumanTaskTarget.inline()
                    .title("Review")
                    .expiresIn(Duration.ofHours(24))
                    .expiresInExpression(".sla.window")
                    .expiresAtExpression(".regulatory.deadline")
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot specify both");
  }

  @Test
  void expiresInExpression_nullEvaluator_thenExpiresIn_buildsOk() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Review")
            .expiresInExpression((ExpressionEvaluator) null)
            .expiresIn(Duration.ofHours(24))
            .build();

    assertThat(target.expiresIn()).isEqualTo(Duration.ofHours(24));
    assertThat(target.expiresInExpression()).isNull();
  }

  @Test
  void expiresInExpression_coexistsWithCandidateGroupsAndPriority() {
    HumanTaskTarget target =
        HumanTaskTarget.inline()
            .title("Full Review")
            .expiresInExpression(".sla.reviewWindow")
            .candidateGroups(Set.of("ethics-committee"))
            .priority("HIGH")
            .scope("casehubio/clinical/adverse-event")
            .build();

    assertThat(target.expiresInExpression()).isInstanceOf(JQExpressionEvaluator.class);
    assertThat(((JQExpressionEvaluator) target.expiresInExpression()).expression())
        .isEqualTo(".sla.reviewWindow");
    assertThat(target.candidateGroups()).isNotNull();
    assertThat(target.priority()).isEqualTo("HIGH");
    assertThat(target.scope()).isEqualTo("casehubio/clinical/adverse-event");
  }

  @Test
  void payloadType_storedOnBuilder() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").payloadType(java.util.Map.class).build();
    assertThat(target.payloadType()).isEqualTo(java.util.Map.class);
  }

  @Test
  void resolutionType_storedOnBuilder() {
    HumanTaskTarget target =
        HumanTaskTarget.inline().title("Review").resolutionType(String.class).build();
    assertThat(target.resolutionType()).isEqualTo(String.class);
  }

  @Test
  void payloadType_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();
    assertThat(target.payloadType()).isNull();
  }

  @Test
  void resolutionType_nullByDefault() {
    HumanTaskTarget target = HumanTaskTarget.inline().title("Review").build();
    assertThat(target.resolutionType()).isNull();
  }

  @Test
  void templateMode_acceptsPayloadAndResolutionTypes() {
    HumanTaskTarget target =
        HumanTaskTarget.template("tmpl-1")
            .payloadType(java.util.Map.class)
            .resolutionType(String.class)
            .build();
    assertThat(target.payloadType()).isEqualTo(java.util.Map.class);
    assertThat(target.resolutionType()).isEqualTo(String.class);
  }
}
