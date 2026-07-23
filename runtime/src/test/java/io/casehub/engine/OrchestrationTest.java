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
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.WorkRequest;
import io.casehub.api.model.WorkResult;
import io.casehub.api.model.WorkStatus;
import io.casehub.engine.common.spi.WorkOrchestrator;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for WorkOrchestrator — verifies end-to-end work submission and result delivery
 * through the real Quarkus event bus and Quartz scheduler.
 */
@QuarkusTest
class OrchestrationTest {

  @Inject WorkOrchestrator orchestrator;
  @Inject CaseInstanceCache cache;
  @Inject SimpleAnalysisCase simpleCase;

  @BeforeEach
  void clear() {
    cache.clear();
  }

  // ---- happy path -----------------------------------------------------------

  @Test
  void submit_workerCompletes_futureResolves() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    CompletableFuture<WorkResult> future =
        orchestrator
            .submit(instance, WorkRequest.of("analyse", Map.of("doc", "report")))
            .toCompletableFuture();

    await().atMost(15, TimeUnit.SECONDS).until(() -> future.isDone());

    WorkResult result = future.get();
    assertThat(result.status()).isEqualTo(WorkStatus.COMPLETED);
  }

  // ---- correctness ----------------------------------------------------------

  @Test
  void submit_workResultCarriesWorkerId() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    WorkResult result =
        orchestrator
            .submit(instance, WorkRequest.of("analyse", Map.of("doc", "x")))
            .toCompletableFuture()
            .get(15, TimeUnit.SECONDS);

    assertThat(result.workerId()).isEqualTo("analyse-worker");
  }

  // ---- robustness -----------------------------------------------------------

  @Test
  void submit_unknownCapability_futureFailsImmediately() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    var future = orchestrator.submit(instance, WorkRequest.of("nonexistent", Map.of()));

    assertThat(future.toCompletableFuture().isCompletedExceptionally()).isTrue();
  }

  // ---- helper ---------------------------------------------------------------

  /**
   * Starts the case with neutral context so no binding fires automatically. This ensures the only
   * submission to the orchestrator is the explicit submit() call in each test.
   */
  private UUID startCase() throws Exception {
    UUID caseId = simpleCase.startCase(Map.of("doc", "initial"));
    await().atMost(5, TimeUnit.SECONDS).until(() -> cache.get(caseId) != null);
    return caseId;
  }

  // ---- Case bean ------------------------------------------------------------

  @ApplicationScoped
  public static class SimpleAnalysisCase extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("analyse")
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ analysis: \"complete\" }")
            .build();

    private final Goal goal =
        Goal.builder()
            .name("done")
            .condition(".trigger == \"ready\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-orchestration")
          .name("Orchestrated Case")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("analyse-worker")
                  .capabilityName("analyse")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> WorkerResult.of(Map.of("analysis", "complete"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("start")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger == \"go\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
