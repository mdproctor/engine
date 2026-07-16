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

@QuarkusTest
class HumanTaskTypedContextTest {

  @Inject TypedPayloadCaseBean typedPayloadCaseBean;
  @Inject MismatchedPayloadCaseBean mismatchedPayloadCaseBean;

  @BeforeEach
  void reset() {
    TypedEventRecorder.events.clear();
  }

  @Test
  void typedPayload_typeNamesFlowThroughEvent() {
    CompletionStage<UUID> future =
        typedPayloadCaseBean.startCase(Map.of("stage", "review", "amount", 100, "currency", "USD"));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(TypedEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = TypedEventRecorder.events.get(0);
    assertThat(event.payloadTypeName()).isEqualTo(PayloadPojo.class.getName());
    assertThat(event.resolutionTypeName()).isEqualTo(ResolutionPojo.class.getName());
    assertThat(event.inputData()).containsEntry("amount", 100);
    assertThat(event.inputData()).containsEntry("currency", "USD");
  }

  @Test
  void typedPayload_nullTypesWhenNotDeclared() {
    CompletionStage<UUID> future =
        typedPayloadCaseBean.startCase(Map.of("stage", "review", "amount", 100, "currency", "USD"));
    future.toCompletableFuture().join();

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(TypedEventRecorder.events).isNotEmpty());

    HumanTaskScheduleEvent event = TypedEventRecorder.events.get(0);
    assertThat(event.payloadTypeName()).isNotNull();
    assertThat(event.resolutionTypeName()).isNotNull();
  }

  @Test
  void mismatchedPayload_bridgeValidationPreventsDispatch() {
    CompletionStage<UUID> future =
        mismatchedPayloadCaseBean.startCase(
            Map.of("stage", "review", "amount", "not-a-number", "currency", 12345));
    future.toCompletableFuture().join();

    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertThat(TypedEventRecorder.events).isEmpty();
  }

  public record PayloadPojo(int amount, String currency) {}

  public record ResolutionPojo(String decision, String reason) {}

  @ApplicationScoped
  static class TypedEventRecorder {
    static final CopyOnWriteArrayList<HumanTaskScheduleEvent> events = new CopyOnWriteArrayList<>();

    @ConsumeEvent(EventBusAddresses.HUMAN_TASK_SCHEDULE)
    void onHumanTaskSchedule(HumanTaskScheduleEvent event) {
      events.add(event);
    }
  }

  @ApplicationScoped
  static class TypedPayloadCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("Typed Review")
              .payloadType(PayloadPojo.class)
              .resolutionType(ResolutionPojo.class)
              .inputMapping("{ amount: .amount, currency: .currency }")
              .outputMapping(".decision")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("TypedHumanTaskCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("typed-review")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }

  @ApplicationScoped
  static class MismatchedPayloadCaseBean extends CaseHub {
    @Override
    public CaseDefinition getDefinition() {
      HumanTaskTarget target =
          HumanTaskTarget.inline()
              .title("Mismatched Review")
              .payloadType(PayloadPojo.class)
              .inputMapping("{ amount: .amount, currency: .currency }")
              .outputMapping(".x")
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("MismatchedHumanTaskCase")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("mismatched-review")
                  .humanTask(target)
                  .on(new ContextChangeTrigger(".stage == \"review\""))
                  .build())
          .build();
    }
  }
}
