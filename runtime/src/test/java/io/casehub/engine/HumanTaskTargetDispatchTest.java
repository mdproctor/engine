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
package io.casehub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.engine.common.internal.event.EventBusAddresses;
import io.casehub.engine.common.internal.event.HumanTaskScheduleEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that CaseContextChangedEventHandler publishes HumanTaskScheduleEvent when a binding with
 * HumanTaskTarget is eligible and evaluates inputMapping before publishing. Refs engine#245.
 */
@QuarkusTest
class HumanTaskTargetDispatchTest {

  @Inject HumanTaskCaseBean humanTaskCaseBean;
  @Inject DynamicGroupsCaseBean dynamicGroupsCaseBean;
  @Inject BadGroupsCaseBean badGroupsCaseBean;
  @Inject ConjunctionFailCaseBean conjunctionFailCaseBean;
  @Inject DynamicFieldsCaseBean dynamicFieldsCaseBean;

  @Test
  void humanTaskBinding_dynamicTitle_resolvesFromContext() {
    CompletionStage<UUID> future =
        dynamicFieldsCaseBean.startCase(
            Map.of(
                "stage", "review",
                "protocol", Map.of("id", "PROTO-42"),
                "trial",
                    Map.of(
                        "site",
                        Map.of("code", "site-london"),
                        "regulatoryDeadlineDuration",
                        "PT72H")));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(HumanTaskEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = HumanTaskEventRecorder.events.get(0);
    assertThat(event.resolvedTitle()).isEqualTo("IRB Review — PROTO-42");
    assertThat(event.resolvedScope()).isEqualTo("site-london");
    assertThat(event.resolvedExpiresIn()).isEqualTo(java.time.Duration.ofHours(72));
  }

  @BeforeEach
  void reset() {
    HumanTaskEventRecorder.events.clear();
  }

  @Test
  void humanTaskBinding_publishesScheduleEvent_withPreEvaluatedInputData() {
    CompletionStage<UUID> future =
        humanTaskCaseBean.startCase(Map.of("stage", "review", "applicantId", "A-42"));
    UUID caseId = future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(HumanTaskEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = HumanTaskEventRecorder.events.get(0);
    assertThat(event.caseId()).isEqualTo(caseId);
    assertThat(event.bindingName()).isEqualTo("review-binding");
    assertThat(event.target()).isInstanceOf(HumanTaskTarget.class);
    assertThat(event.target().templateRef()).isEqualTo("irb-review-template");
    // inputMapping "{ applicantId: .applicantId }" evaluated: applicantId should be "A-42"
    assertThat(event.inputData()).containsEntry("applicantId", "A-42");
  }

  // ── Dynamic candidateGroups ─────────────────────────────────────────────────

  @Test
  void humanTaskBinding_dynamicCandidateGroups_resolvesFromContext() {
    CompletionStage<UUID> future =
        dynamicGroupsCaseBean.startCase(
            Map.of("stage", "review", "irb", Map.of("candidateGroups", List.of("irb-committee"))));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(HumanTaskEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = HumanTaskEventRecorder.events.get(0);
    assertThat(event.resolvedCandidateGroups()).containsExactlyInAnyOrder("irb-committee");
    assertThat(event.resolvedCandidateUsers()).isNull();
  }

  @Test
  void humanTaskBinding_dynamicCandidateGroups_jqReturnsSingleString_treatedAsValidSet() {
    // With CandidateSetStrategy, JQ expressions that return a single string are valid —
    // the strategy treats them as a single-element set. This is more permissive than the old
    // ListExpressionResolver which required array results.
    CompletionStage<UUID> future =
        badGroupsCaseBean.startCase(Map.of("stage", "review", "routing", "not-an-array"));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(HumanTaskEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = HumanTaskEventRecorder.events.get(0);
    assertThat(event.resolvedCandidateGroups()).containsExactly("not-an-array");
  }

  @Test
  void humanTaskBinding_dynamicCandidateGroups_conjunctionBothResolve_eventPublished() {
    // With CandidateSetStrategy, JQ expressions that return strings are valid —
    // both groups and users resolve successfully.
    CompletionStage<UUID> future =
        conjunctionFailCaseBean.startCase(
            Map.of(
                "stage", "review",
                "groups", "wrong",
                "users", List.of("user-1")));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(HumanTaskEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = HumanTaskEventRecorder.events.get(0);
    assertThat(event.resolvedCandidateGroups()).containsExactly("wrong");
    assertThat(event.resolvedCandidateUsers()).containsExactly("user-1");
  }

  /** Records HumanTaskScheduleEvent arrivals for test assertions. */
  @ApplicationScoped
  static class HumanTaskEventRecorder {
    static final CopyOnWriteArrayList<HumanTaskScheduleEvent> events = new CopyOnWriteArrayList<>();

    @ConsumeEvent(EventBusAddresses.HUMAN_TASK_SCHEDULE)
    void onHumanTaskSchedule(HumanTaskScheduleEvent event) {
      events.add(event);
    }
  }

  /** CaseHub with a HumanTaskTarget binding. Fires when stage == "review". */
  @ApplicationScoped
  static class HumanTaskCaseBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.template("irb-review-template")
              .inputMapping("{ applicantId: .applicantId }")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("HumanTaskDispatchCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("review-binding")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }

  /** Case with candidateGroupsExpression binding. */
  @ApplicationScoped
  static class DynamicGroupsCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("IRB Review")
              .candidateGroupsExpression(".irb.candidateGroups")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("DynamicGroupsCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("review-binding")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }

  /** Case with candidateGroupsExpression that resolves to a non-array. */
  @ApplicationScoped
  static class BadGroupsCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("Bad Groups")
              .candidateGroupsExpression(".routing")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("BadGroupsCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("bad-binding")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }

  /** Case where groups expression fails and users expression succeeds — conjunction failure. */
  @ApplicationScoped
  static class ConjunctionFailCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("Conjunction")
              .candidateGroupsExpression(".groups")
              .candidateUsersExpression(".users")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("ConjunctionFailCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("conjunction-binding")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class DynamicFieldsCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("IRB Review")
              .titleExpression("\"IRB Review — \" + .protocol.id")
              .scopeExpression(".trial.site.code")
              .expiresInExpression(".trial.regulatoryDeadlineDuration")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("DynamicFieldsCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("dynamic-fields-binding")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }
}
