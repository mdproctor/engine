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
package io.casehub.engine.planning.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.TaskStatus;
import io.casehub.engine.planning.plan.PlanItemDefinition;
import io.casehub.engine.planning.registry.BlackboardRegistry;
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

@QuarkusTest
class SequentialStagesBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject TwoStagesCaseBean twoStagesCase;

  @Test
  void two_sequential_compounds_activate_in_order() {
    UUID caseId = twoStagesCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound1 =
        PlanItemDefinition.Compound.builder("compound-one")
            .entryCondition(ctx -> "start".equals(ctx.getPath("phase")))
            .build();
    var compound2 =
        PlanItemDefinition.Compound.builder("compound-two")
            .entryCondition(ctx -> "two".equals(ctx.getPath("phase")))
            .build();

    registry.get(caseId).get().registerDefinition(compound1);
    registry.get(caseId).get().registerDefinition(compound2);

    twoStagesCase.signal(caseId, "phase", "start");

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(registry.get(caseId).get().getDefinitionStatus(compound1.id()))
                  .as("compound-one must activate: .phase == 'start' is met by signal")
                  .isEqualTo(TaskStatus.RUNNING);
              assertThat(registry.get(caseId).get().getDefinitionStatus(compound2.id()))
                  .as("compound-two must activate after worker writes phase=two")
                  .isEqualTo(TaskStatus.RUNNING);
            });
  }

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
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("phase", "two"))))
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
