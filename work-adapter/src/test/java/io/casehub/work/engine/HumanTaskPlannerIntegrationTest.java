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
package io.casehub.work.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.work.memory.InMemoryWorkItemStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test verifying the full planner → handler → WorkItem creation flow for humanTask
 * bindings. Specifically guards against the engine#312 regression where PlanningStrategyLoopControl
 * pre-marking a PlanItem RUNNING would cause HumanTaskScheduleHandler to skip WorkItem creation.
 */
@QuarkusTest
class HumanTaskPlannerIntegrationTest {

  @Inject HumanTaskApprovalCaseBean approvalCase;
  @Inject InMemoryWorkItemStore workItemStore;

  @BeforeEach
  void reset() {
    workItemStore.clear();
  }

  @Test
  void humanTask_binding_creates_workItem_via_full_planner_handler_flow() {
    // Planner selects the humanTask binding → publishes HumanTaskScheduleEvent →
    // handler must receive a PENDING PlanItem and create a WorkItem.
    // Before engine#312 fix: planner marked RUNNING → handler skipped → no WorkItem.
    approvalCase.startCase(Map.of("status", "pending")).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(workItemStore.scanAll())
                    .as(
                        "WorkItem must be created when humanTask binding fires via full engine flow")
                    .isNotEmpty());
  }

  @ApplicationScoped
  public static class HumanTaskApprovalCaseBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("work-adapter-it")
          .name("Human Task Approval")
          .version("1.0.0")
          .bindings(
              Binding.builder()
                  .name("approval-binding")
                  .humanTask(HumanTaskTarget.inline().title("Approval Required").build())
                  .on(new ContextChangeTrigger(".status == \"pending\""))
                  .build())
          .build();
    }
  }
}
