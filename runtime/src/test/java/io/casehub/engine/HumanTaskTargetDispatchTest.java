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
}
