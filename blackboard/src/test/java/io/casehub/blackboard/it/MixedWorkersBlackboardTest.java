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

import io.casehub.api.engine.CaseHub;
import io.casehub.api.model.Binding;
import io.casehub.api.model.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Worker;
import io.casehub.blackboard.event.PlanItemCompletedEvent;
import io.casehub.blackboard.registry.BlackboardRegistry;
import io.casehub.engine.common.internal.model.PlanItemStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * R4: Two bindings (different capabilities) fire on independent triggers. Both workers complete
 * exactly once. Verifies coexistence of multiple concurrent PlanItems. See casehubio/engine#76.
 *
 * <p>Design: each binding has its own disjoint trigger key ({@code phaseA} / {@code phaseB}) so the
 * workers self-falsify their own trigger condition. This prevents re-trigger loops — each worker
 * runs exactly once and reaches COMPLETED.
 *
 * <p>Test strategy: event-driven via {@link PlanItemCompletedEvent}. The observer captures the
 * exact {@code planItemId} from the event (not from a subsequent {@link
 * BlackboardRegistry#getPlanItemId} lookup which may have been overwritten by a re-triggered
 * PlanItem). By verifying the specific planItemId from the event, the assertion is correct even if
 * a sibling worker's context update triggers a second scheduling attempt.
 *
 * <p>Race-free: {@link WorkerCompletionObserver} uses {@code ConcurrentHashMap.computeIfAbsent} for
 * per-worker futures, so it is safe regardless of whether the event fires before or after the test
 * registers the future.
 */
@QuarkusTest
class MixedWorkersBlackboardTest {

  @Inject BlackboardRegistry registry;
  @Inject MixedCaseBean mixedCase;
  @Inject WorkerCompletionObserver observer;

  @Test
  void two_workers_with_different_capabilities_both_complete() throws Exception {
    UUID caseId =
        mixedCase
            .startCase(Map.of("phaseA", "start", "phaseB", "start"))
            .toCompletableFuture()
            .join();

    CompletableFuture<String> aFuture = observer.awaitWorker(caseId, "worker-a");
    CompletableFuture<String> bFuture = observer.awaitWorker(caseId, "worker-b");

    // Wait for both workers — futures carry the exact planItemId from the completion event.
    CompletableFuture.allOf(aFuture, bFuture).get(30, TimeUnit.SECONDS);

    // Verify the specific planItemId that completed (not getPlanItemId which may be overwritten).
    String aPlanItemId = aFuture.get();
    String bPlanItemId = bFuture.get();

    var plan = registry.get(caseId);
    assertThat(plan).isPresent();

    assertThat(plan.get().getPlanItem(aPlanItemId).map(i -> i.getStatus()))
        .as("worker-a PlanItem must be COMPLETED")
        .contains(PlanItemStatus.COMPLETED);
    assertThat(plan.get().getPlanItem(bPlanItemId).map(i -> i.getStatus()))
        .as("worker-b PlanItem must be COMPLETED")
        .contains(PlanItemStatus.COMPLETED);
  }

  /**
   * Observes {@link PlanItemCompletedEvent} and signals per-worker futures with the exact {@code
   * planItemId} from the event. Race-free: {@code computeIfAbsent} ensures the future exists
   * whether the event fires before or after the test registers via {@link #awaitWorker}.
   */
  @ApplicationScoped
  public static class WorkerCompletionObserver {

    private final ConcurrentHashMap<String, CompletableFuture<String>> futures =
        new ConcurrentHashMap<>();

    void onPlanItemCompleted(@ObservesAsync PlanItemCompletedEvent event) {
      futures
          .computeIfAbsent(key(event.caseId(), event.trackingKey()), k -> new CompletableFuture<>())
          .complete(event.planItemId());
    }

    /**
     * Returns a future that completes with the {@code planItemId} of the completed PlanItem. Safe
     * to call before or after the event fires.
     */
    public CompletableFuture<String> awaitWorker(UUID caseId, String workerName) {
      return futures.computeIfAbsent(key(caseId, workerName), k -> new CompletableFuture<>());
    }

    private static String key(UUID caseId, String workerName) {
      return caseId + ":" + workerName;
    }
  }

  /**
   * Case with two distinct capabilities and bindings. Each binding fires on its own disjoint
   * trigger key so each worker self-falsifies its own trigger condition (phaseA "start" → "done";
   * phaseB "start" → "done"). Workers complete exactly once with no re-trigger.
   */
  @ApplicationScoped
  public static class MixedCaseBean extends CaseHub {

    private final Capability capA =
        Capability.builder()
            .name("cap-a")
            .inputSchema("{ phaseA: .phaseA }")
            .outputSchema("{ phaseA: .phaseA }")
            .build();

    private final Capability capB =
        Capability.builder()
            .name("cap-b")
            .inputSchema("{ phaseB: .phaseB }")
            .outputSchema("{ phaseB: .phaseB }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("blackboard-it")
          .name("Mixed Workers Case")
          .version("1.0.0")
          .capabilities(capA, capB)
          .workers(
              Worker.builder()
                  .name("worker-a")
                  .capabilities(capA)
                  .function(input -> Map.of("phaseA", "done"))
                  .build(),
              Worker.builder()
                  .name("worker-b")
                  .capabilities(capB)
                  .function(input -> Map.of("phaseB", "done"))
                  .build())
          .bindings(
              Binding.builder()
                  .name("trigger-a")
                  .capability(capA)
                  .on(new ContextChangeTrigger(".phaseA == \"start\""))
                  .build(),
              Binding.builder()
                  .name("trigger-b")
                  .capability(capB)
                  .on(new ContextChangeTrigger(".phaseB == \"start\""))
                  .build())
          .build();
    }
  }
}
