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
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: a two-stage choreography pipeline using WorkBroker selection. Verifies that both
 * stages run in order with exactly one worker per stage.
 */
@QuarkusTest
class WorkBrokerEndToEndTest {

  @Inject CaseInstanceCache cache;
  @Inject TwoStagePipelineCase pipeline;

  @BeforeEach
  void clear() {
    cache.clear();
    TwoStagePipelineCase.stage1Count.set(0);
    TwoStagePipelineCase.stage2Count.set(0);
    TwoStagePipelineCase.stage1BeforeStage2 = false;
  }

  @Test
  void twoStagePipeline_completesSuccessfully() throws Exception {
    UUID caseId = pipeline.startCase(Map.of("stage", "raw"));
    AtomicReference<UUID> ref = new AtomicReference<>(caseId);

    await()
        .atMost(20, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(cache.get(ref.get()).getState()).isEqualTo(CaseStatus.COMPLETED));

    assertThat(TwoStagePipelineCase.stage1Count.get()).isEqualTo(1);
    assertThat(TwoStagePipelineCase.stage2Count.get()).isEqualTo(1);
  }

  @Test
  void twoStagePipeline_stage2OnlyRunsAfterStage1() throws Exception {
    UUID caseId = pipeline.startCase(Map.of("stage", "raw"));
    AtomicReference<UUID> ref = new AtomicReference<>(caseId);
    await().atMost(10, TimeUnit.SECONDS).until(() -> TwoStagePipelineCase.stage1Count.get() == 1);
    await().atMost(10, TimeUnit.SECONDS).until(() -> TwoStagePipelineCase.stage2Count.get() == 1);

    assertThat(TwoStagePipelineCase.stage1BeforeStage2).isTrue();
  }

  @ApplicationScoped
  public static class TwoStagePipelineCase extends CaseHub {

    static final AtomicInteger stage1Count = new AtomicInteger(0);
    static final AtomicInteger stage2Count = new AtomicInteger(0);
    static volatile boolean stage1BeforeStage2 = false;

    private final Capability stage1Cap =
        Capability.builder()
            .name("process")
            .inputSchema("{ stage: .stage }")
            .outputSchema("{ stage: \"processed\" }")
            .build();

    private final Capability stage2Cap =
        Capability.builder()
            .name("finalise")
            .inputSchema("{ stage: .stage }")
            .outputSchema("{ stage: \"final\" }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".stage == \"final\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-e2e")
          .name("Two Stage Pipeline")
          .version("1.0.0")
          .capabilities(stage1Cap, stage2Cap)
          .workers(
              Worker.builder()
                  .name("processor")
                  .capabilityName("process")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            stage1BeforeStage2 = stage2Count.get() == 0;
                            stage1Count.incrementAndGet();
                            return WorkerResult.of(Map.of("stage", "processed"));
                          }))
                  .build(),
              Worker.builder()
                  .name("finaliser")
                  .capabilityName("finalise")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            stage2Count.incrementAndGet();
                            return WorkerResult.of(Map.of("stage", "final"));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("start-process")
                  .capability(stage1Cap)
                  .on(new ContextChangeTrigger(".stage == \"raw\""))
                  .build(),
              Binding.builder()
                  .name("start-finalise")
                  .capability(stage2Cap)
                  .on(new ContextChangeTrigger(".stage == \"processed\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
