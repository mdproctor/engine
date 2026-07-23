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
import io.casehub.api.model.WorkRequest;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a case transitions to WAITING when submitAndWait() is called, then resumes to
 * RUNNING when the selected worker completes.
 */
@QuarkusTest
class CaseWaitingResumeTest {

  @Inject WorkOrchestrator orchestrator;
  @Inject CaseInstanceCache cache;
  @Inject WaitingResumptionCase waitingCase;

  @BeforeEach
  void clear() {
    cache.clear();
  }

  // ---- happy path -----------------------------------------------------------

  @Test
  void submitAndWait_caseTransitionsToWaiting() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    orchestrator.submitAndWait(instance, WorkRequest.of("analyse", Map.of("doc", "test")));

    assertThat(instance.getState()).isEqualTo(CaseStatus.WAITING);
    assertThat(instance.getWaitingForWorkId()).isNotNull();
  }

  @Test
  void submitAndWait_workerCompletes_caseResumes() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    orchestrator.submitAndWait(instance, WorkRequest.of("analyse", Map.of("doc", "test")));
    assertThat(instance.getState()).isEqualTo(CaseStatus.WAITING);

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(cache.get(caseId).getState())
                    .isIn(CaseStatus.RUNNING, CaseStatus.COMPLETED));
  }

  // ---- correctness ----------------------------------------------------------

  @Test
  void submitAndWait_waitingForWorkIdClearedOnResume() throws Exception {
    UUID caseId = startCase();
    var instance = cache.get(caseId);

    orchestrator.submitAndWait(instance, WorkRequest.of("analyse", Map.of("doc", "test")));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(cache.get(caseId).getWaitingForWorkId()).isNull());
  }

  // ---- helper ---------------------------------------------------------------

  /**
   * Starts the case with neutral context so no binding fires automatically. This ensures the only
   * submission to the orchestrator is the explicit submitAndWait() call in each test.
   */
  private UUID startCase() throws Exception {
    return waitingCase.startCase(Map.of("doc", "initial"));
  }

  // ---- Case bean ------------------------------------------------------------

  @ApplicationScoped
  public static class WaitingResumptionCase extends CaseHub {

    private final Capability cap =
        Capability.builder()
            .name("analyse")
            .inputSchema("{ doc: .doc }")
            .outputSchema("{ result: .result }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".result == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-waiting")
          .name("Waiting Resumption Case")
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
                          (input, scope) -> WorkerResult.of(Map.of("result", "done"))))
                  .build())
          .bindings(
              Binding.builder()
                  .name("start")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger == \"start\""))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
