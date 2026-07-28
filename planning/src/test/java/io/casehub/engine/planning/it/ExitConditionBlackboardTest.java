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
class ExitConditionBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject ExitConditionCaseBean exitConditionCase;

  @Test
  void compound_completed_when_worker_output_satisfies_exit_condition() {
    UUID caseId = exitConditionCase.startCase(Map.of("ready", true));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(registry.get(caseId)).isPresent());

    var compound =
        PlanItemDefinition.Compound.builder("active-compound")
            .entryCondition(ctx -> true)
            .exitCondition(ctx -> "exited".equals(ctx.getPath("phase")))
            .build();
    registry.get(caseId).get().registerDefinition(compound);

    exitConditionCase.signal(caseId, "phase", "active");

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(registry.get(caseId).get().getDefinitionStatus(compound.id()))
                    .as("compound must be COMPLETED when worker output satisfies exit condition")
                    .isEqualTo(TaskStatus.COMPLETED));
  }

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
                  .capabilityName("exit-writer")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("phase", "exited"))))
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
