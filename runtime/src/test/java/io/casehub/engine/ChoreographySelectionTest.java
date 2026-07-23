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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that WorkBroker selects exactly one worker when multiple workers share the same
 * capability. Previously, all capable workers were scheduled — this is the regression this test
 * guards against.
 *
 * <p>Each case run passes a unique {@code runId} in the context. Workers record their invocation
 * count per {@code runId}, so in-flight Quartz jobs from previous test cases cannot inflate the
 * count for the current case — they update a different key in the map.
 */
@QuarkusTest
class ChoreographySelectionTest {

  @Inject CaseInstanceCache cache;
  @Inject TwoWorkerSameCapabilityCase twoWorkerCase;

  @BeforeEach
  void clear() {
    cache.clear();
    TwoWorkerSameCapabilityCase.runCounts.clear();
  }

  /** Happy path: two workers with the same capability — only one is called. */
  @Test
  void twoWorkersSharedCapability_onlyOneIsSelected() throws Exception {
    String runId = UUID.randomUUID().toString();
    UUID caseId = twoWorkerCase.startCase(Map.of("trigger", "go", "runId", runId));

    await()
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(cache.get(caseId).getState()).isEqualTo(CaseStatus.COMPLETED));

    int calls =
        TwoWorkerSameCapabilityCase.runCounts.getOrDefault(runId, new AtomicInteger()).get();
    assertThat(calls).as("WorkBroker must select exactly one worker, not both").isEqualTo(1);
  }

  /** Correctness: two sequential cases each invoke exactly one worker. */
  @Test
  void twoSequentialCases_eachSelectsExactlyOneWorker() throws Exception {
    for (int i = 0; i < 2; i++) {
      // Each iteration has its own runId — stale jobs from the previous iteration can
      // only increment that iteration's counter, not this one.
      String runId = UUID.randomUUID().toString();
      UUID caseId = twoWorkerCase.startCase(Map.of("trigger", "go", "runId", runId));

      await()
          .atMost(15, TimeUnit.SECONDS)
          .untilAsserted(
              () -> assertThat(cache.get(caseId).getState()).isEqualTo(CaseStatus.COMPLETED));

      int calls =
          TwoWorkerSameCapabilityCase.runCounts.getOrDefault(runId, new AtomicInteger()).get();
      assertThat(calls).as("Case %d: exactly one worker must run", i).isEqualTo(1);
    }
  }

  @ApplicationScoped
  public static class TwoWorkerSameCapabilityCase extends CaseHub {

    /** Worker invocation count keyed by the unique runId passed in the case context. */
    static final ConcurrentHashMap<String, AtomicInteger> runCounts = new ConcurrentHashMap<>();

    private final Capability capability =
        Capability.builder()
            .name("do-work")
            // runId flows through so workers can record their invocation against the right key
            .inputSchema("{ trigger: .trigger, runId: .runId }")
            .outputSchema("{ result: \"done\" }")
            .build();

    private final Goal goal =
        Goal.builder().name("done").condition(".result == \"done\"").kind(GoalKind.SUCCESS).build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-selection")
          .name("Two Worker Selection Case")
          .version("1.0.0")
          .capabilities(capability)
          .workers(
              Worker.builder()
                  .name("worker-a")
                  .capabilityName("do-work")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            record(input);
                            return WorkerResult.of(Map.of("result", "done"));
                          }))
                  .build(),
              Worker.builder()
                  .name("worker-b")
                  .capabilityName("do-work")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          Map.class,
                          (input, scope) -> {
                            record(input);
                            return WorkerResult.of(Map.of("result", "done"));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(capability)
                  // Guard on .result == null: prevents re-firing after worker writes output.
                  // Without this, CONTEXT_CHANGED fires again post-completion and the binding
                  // re-evaluates while the case is still transitioning, scheduling a second worker.
                  .on(new ContextChangeTrigger(".trigger == \"go\" and .result == null"))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }

    private static void record(Map<String, Object> input) {
      Object runId = input.get("runId");
      if (runId != null) {
        runCounts.computeIfAbsent(runId.toString(), k -> new AtomicInteger()).incrementAndGet();
      }
    }
  }
}
