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
 * R3: Stage with explicit exit condition is terminated when worker output satisfies it. See
 * casehubio/engine#76.
 *
 * <p>Design: starts with idle context ({@code ready=true}) so the worker binding does not fire at
 * case creation. Stage is pre-activated and added before the signal. Signal writes {@code
 * phase=active}, triggering the worker which writes {@code phase=exited}. The next evaluation cycle
 * finds the ACTIVE stage's exit condition satisfied and terminates it.
 */
@QuarkusTest
class ExitConditionBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject ExitConditionCaseBean exitConditionCase;

  @Test
  void active_stage_terminated_when_worker_output_satisfies_exit_condition() {
    // Start with idle context — binding does not fire yet (no phase=active).
    UUID caseId = exitConditionCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Plan model is created on the first select() call from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // Pre-activate the stage — StageLifecycleEvaluator only checks ACTIVE stages for exit
    Stage stage =
        Stage.builder("active-stage")
            .entryCondition(ctx -> true)
            .exitCondition(ctx -> "exited".equals(ctx.getPath("phase")))
            .build();
    stage.activate();
    registry.get(caseId).get().addStage(stage);

    // Signal writes phase=active → triggers worker binding → worker writes phase=exited →
    // CONTEXT_CHANGED → exit condition satisfied → stage TERMINATED.
    exitConditionCase.signal(caseId, "phase", "active");

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(stage.getStatus())
                    .as(
                        "stage must be TERMINATED when worker writes phase=exited satisfying exit"
                            + " condition")
                    .isEqualTo(StageStatus.TERMINATED));
  }

  /**
   * Worker writes {@code phase=exited} when triggered by {@code .phase == "active"}. Starts with
   * idle context ({@code ready=true}) so the worker does not fire on case creation — only when the
   * signal writes {@code phase=active}.
   */
  @ApplicationScoped
  public static class ExitConditionCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("exit-writer")
            .inputSchema("{ phase: .phase }")
            .outputSchema("{ phase: .phase }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-it")
          .name("Exit Condition Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("exit-writer-worker")
                  .capabilities(cap)
                  .function(
                      new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("phase", "exited"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-active")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".phase == \"active\""))
                  .build())
          .build();
    }
  }
}
