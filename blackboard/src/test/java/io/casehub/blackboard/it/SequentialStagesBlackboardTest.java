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
 * R1: Two sequential stages activate in order. First activates when signal writes phase=start;
 * second activates after worker writes phase=two in response. See casehubio/engine#76.
 *
 * <p>Design: starts with idle context ({@code ready=true}) so the worker binding does not fire at
 * case creation. The signal writes {@code phase=start}, satisfying stage-one's entry condition and
 * triggering the worker. The worker writes {@code phase=two}, satisfying stage-two's entry
 * condition on the subsequent evaluation cycle.
 */
@QuarkusTest
class SequentialStagesBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject TwoStagesCaseBean twoStagesCase;

  @Test
  void two_sequential_stages_activate_in_order() {
    // Start with idle context — binding does not fire yet (no phase=start).
    UUID caseId = twoStagesCase.startCase(Map.of("ready", true)).toCompletableFuture().join();

    // Plan model is created on the first select() call from CaseStartedEvent
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    // stage-one: entry condition satisfied when phase=start (written by signal below)
    // stage-two: entry condition satisfied when phase=two (written by worker)
    Stage stage1 =
        Stage.builder("stage-one")
            .entryCondition(ctx -> "start".equals(ctx.getPath("phase")))
            .build();
    Stage stage2 =
        Stage.builder("stage-two")
            .entryCondition(ctx -> "two".equals(ctx.getPath("phase")))
            .build();

    registry.get(caseId).get().addStage(stage1);
    registry.get(caseId).get().addStage(stage2);

    // Signal writes phase=start → CONTEXT_CHANGED → stage-one entry condition met (ACTIVE) AND
    // worker binding fires (phase == "start") → worker writes phase=two → CONTEXT_CHANGED →
    // stage-two entry condition met (ACTIVE).
    twoStagesCase.signal(caseId, "phase", "start");

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(stage1.getStatus())
                  .as("stage-one must activate: .phase == 'start' is met by signal")
                  .isEqualTo(StageStatus.ACTIVE);
              assertThat(stage2.getStatus())
                  .as("stage-two must activate after worker writes phase=two")
                  .isEqualTo(StageStatus.ACTIVE);
            });
  }

  /**
   * Worker writes {@code phase=two} when triggered by {@code .phase == "start"}. Starts with idle
   * context ({@code ready=true}) so the worker does not fire on case creation — only when the
   * signal writes {@code phase=start}.
   */
  @ApplicationScoped
  public static class TwoStagesCaseBean extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("phase-writer")
            .inputSchema("{ phase: .phase }")
            .outputSchema("{ phase: .phase }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-it")
          .name("Two Stages Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("phase-writer-worker")
                  .capabilityName("phase-writer")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class, input -> WorkerResult.of(Map.of("phase", "two"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-on-start")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".phase == \"start\""))
                  .build())
          .build();
    }
  }
}
