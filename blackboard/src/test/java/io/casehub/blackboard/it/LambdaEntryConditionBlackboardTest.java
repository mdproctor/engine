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
package io.casehub.blackboard.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.blackboard.stage.Stage;
import io.casehub.blackboard.stage.StageStatus;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * R5: Stage with Java lambda entry condition (not JQ) activates end-to-end. Verifies
 * LambdaExpressionEvaluator dispatch in StageLifecycleEvaluator works correctly through the full
 * engine. See casehubio/engine#76.
 */
@QuarkusTest
class LambdaEntryConditionBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject LambdaCaseBean lambdaCase;

  @Test
  void stage_with_lambda_entry_condition_activates_when_predicate_true() {
    UUID caseId = lambdaCase.startCase(Map.of("value", 42)).toCompletableFuture().join();

    // Plan model is created on the first select() call from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage =
        Stage.builder("lambda-stage")
            .entryCondition(ctx -> Integer.valueOf(42).equals(ctx.getPath("value")))
            .build();
    registry.get(caseId).get().addStage(stage);

    lambdaCase.signal(caseId, "probe", "tick");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage with lambda predicate (value == 42) must activate")
                    .isEqualTo(StageStatus.ACTIVE));
  }

  @Test
  void stage_with_lambda_stays_pending_when_predicate_false() {
    UUID caseId = lambdaCase.startCase(Map.of("value", 99)).toCompletableFuture().join();

    // Plan model is created on the first select() call from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    Stage stage =
        Stage.builder("lambda-stage")
            .entryCondition(ctx -> Integer.valueOf(42).equals(ctx.getPath("value")))
            .build();
    registry.get(caseId).get().addStage(stage);

    lambdaCase.signal(caseId, "probe", "tick");

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as("stage must stay PENDING when lambda predicate is false (value=99 != 42)")
                    .isEqualTo(StageStatus.PENDING));
  }

  /**
   * Case whose binding fires only when {@code .value != null}. The worker writes {@code done=true}
   * — a key disjoint from {@code value} — preventing re-trigger. Both test methods share this bean
   * but start independent cases via different initial context.
   */
  @ApplicationScoped
  public static class LambdaCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("lambda-cap")
            .inputSchema("{ value: .value }")
            .outputSchema("{ done: .done }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-it")
          .name("Lambda Entry Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("lambda-worker")
                  .capabilityName("lambda-cap")
                  .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("done", true))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-value")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".value != null"))
                  .build())
          .build();
    }
  }
}
