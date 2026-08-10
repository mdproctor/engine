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
package io.casehub.engine.agentic;

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
import io.casehub.blocks.agentic.AgentRef;
import io.casehub.blocks.agentic.AgentResult;
import io.casehub.blocks.agentic.model.ExecutionModel;
import io.casehub.blocks.agentic.model.PatternType;
import io.casehub.blocks.agentic.pattern.Patterns;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PatternExecutionIntegrationTest {

  @Inject PatternIntegrationCaseHub caseHub;
  @Inject CaseInstanceCache caseInstanceCache;

  @Test
  void patternWorkerExecutesThroughEnginePipeline() {
    UUID caseId = caseHub.startCase(Map.of("trigger", true));
    assertThat(caseId).isNotNull();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              var instance = caseInstanceCache.get(caseId);
              assertThat(instance).isNotNull();
              assertThat(instance.getState())
                  .as("case should reach COMPLETED (was: %s)", instance.getState())
                  .isEqualTo(CaseStatus.COMPLETED);
            });
  }

  @ApplicationScoped
  public static class PatternIntegrationCaseHub extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      var agent1 =
          AgentRef.external(
              "step-1",
              ctx ->
                  CompletableFuture.completedFuture(
                      AgentResult.success(null, Map.of("step1", "done"))));
      var agent2 =
          AgentRef.external(
              "step-2",
              ctx ->
                  CompletableFuture.completedFuture(
                      AgentResult.success(null, Map.of("analysisResult", "complete"))));

      ExecutionModel<Map<String, Object>> model =
          Patterns.<Map<String, Object>>sequence().agents(agent1, agent2).build();

      Capability capability =
          Capability.builder()
              .name("analysis")
              .description("Run analysis pattern")
              .inputSchema(".")
              .outputSchema(".")
              .build();

      Worker worker =
          Worker.builder()
              .name("pattern-worker")
              .capabilityName("analysis")
              .function(new PatternWorkerFunction(model, PatternType.SEQUENCE, false))
              .build();

      Binding binding =
          Binding.builder()
              .name("run-analysis")
              .capability(capability)
              .on(new ContextChangeTrigger(".trigger == true"))
              .build();

      Goal goal =
          Goal.builder()
              .name("done")
              .condition(".analysisResult != null")
              .kind(GoalKind.SUCCESS)
              .build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("pattern-integration")
          .version("1.0.0")
          .title("Pattern integration test")
          .capabilities(capability)
          .workers(worker)
          .bindings(binding)
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
