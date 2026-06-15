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
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.CaseStatus;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.WorkerDecisionEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a slow or blocking WorkerDecisionEvent observer does NOT prevent CONTEXT_CHANGED
 * from being published and the case from completing.
 *
 * <p>WorkflowExecutionCompletedHandler fires workerDecisionEvents.fireAsync() and
 * lifecycleEvents.fireAsync(). If either awaits observer completion before publishing
 * CONTEXT_CHANGED, a blocking observer causes the case to stay RUNNING indefinitely. The engine
 * must publish CONTEXT_CHANGED without waiting for async observers.
 *
 * <p>Reproduces casehubio/engine#491: in consumer apps with multiple @ObservesAsync
 * WorkerDecisionEvent observers (e.g. WorkerDecisionEventCapture + app-specific observers),
 * concurrent DB writes from those observers can hang under H2 lock contention. The hang propagates
 * to the WorkflowExecutionCompletedHandler chain if it awaits fireAsync() completion, silently
 * blocking case state progression.
 *
 * <p>Fix: publish CONTEXT_CHANGED before or independently of firing async CDI events — observers
 * are optional side-effects and must not gate case state changes.
 *
 * <p>Refs casehubio/engine#491.
 */
@QuarkusTest
class WorkerDecisionObserverNonBlockingTest {

  /** Latch that the blocking observer waits on. Released in @AfterEach. */
  static volatile CountDownLatch observerLatch = new CountDownLatch(1);

  /** Set to true to activate the blocking observer during the test. */
  static volatile boolean blockObserver = false;

  @Inject BlockingCaseHubBean blockingCaseHubBean;
  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void setUp() {
    observerLatch = new CountDownLatch(1); // fresh latch each test
    blockObserver = false;
  }

  @AfterEach
  void tearDown() {
    observerLatch.countDown(); // always release so the observer thread is not permanently blocked
    blockObserver = false;
  }

  /**
   * Case must complete within 10 seconds even when a WorkerDecisionEvent observer blocks
   * indefinitely (simulating H2 lock contention in consumer apps).
   *
   * <p>Fails with the current implementation: WorkflowExecutionCompletedHandler awaits fireAsync()
   * completion, so the blocking observer prevents CONTEXT_CHANGED from being published and the case
   * state never advances to COMPLETED.
   *
   * <p>Passes after the fix: CONTEXT_CHANGED is published without awaiting observer completion.
   */
  @Test
  void caseCompletes_even_when_workerDecisionObserver_blocks_indefinitely() {
    blockObserver = true;

    UUID caseId =
        blockingCaseHubBean.startCase(Map.of("trigger", "go")).toCompletableFuture().join();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(caseInstanceCache.get(caseId).getState())
                    .as(
                        "Case must reach COMPLETED even when a WorkerDecisionEvent observer blocks. "
                            + "CONTEXT_CHANGED must be published before awaiting async observer "
                            + "completion — engine#491.")
                    .isEqualTo(CaseStatus.COMPLETED));
  }

  /**
   * Blocking WorkerDecisionEvent observer — simulates a consumer observer that hangs due to H2 lock
   * contention, slow external calls, or transaction timeouts. Only blocks when blockObserver is
   * true so other tests in this class are unaffected.
   */
  @ApplicationScoped
  static class BlockingWorkerDecisionObserver {

    void onWorkerDecision(@ObservesAsync WorkerDecisionEvent event) {
      if (!blockObserver) return;
      try {
        // Block until the latch is released in @AfterEach.
        // Simulates H2 lock wait, slow DB writes, or transaction timeout.
        observerLatch.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Simple case hub: fires worker on trigger, worker writes "done", goal checks done != null. */
  @ApplicationScoped
  static class BlockingCaseHubBean extends CaseHub {

    @Override
    public CaseDefinition getDefinition() {
      Capability cap =
          Capability.builder()
              .name("blocking-test-cap")
              .inputSchema("{ trigger: .trigger }")
              .outputSchema("{ done: true }")
              .build();

      Goal goal =
          Goal.builder().name("done").condition(".done != null").kind(GoalKind.SUCCESS).build();

      return CaseDefinition.builder()
          .namespace("test")
          .name("Blocking Observer Test")
          .version("1.0.0")
          .capabilities(cap)
          .workers(
              Worker.builder()
                  .name("blocking-test-worker")
                  .capabilities(cap)
                  .function(input -> WorkerResult.of(Map.of("done", true)))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger")
                  .capability(cap)
                  .on(new ContextChangeTrigger(".trigger != null"))
                  .build())
          .goals(goal)
          .completion(GoalExpression.allOf(goal))
          .build();
    }
  }
}
