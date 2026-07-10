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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class SignalDedupExtendedTest {

  @Inject SignalDedupBean bean;

  @Inject SignalDedupWithGoalBean goalBean;

  @Inject CaseInstanceCache caseInstanceCache;

  @BeforeEach
  void reset() {
    SignalDedupBean.runCount.set(0);
    SignalDedupBean.runCountById.clear();
    SignalDedupWithGoalBean.runCount.set(0);
    SignalDedupWithGoalBean.runCountById.clear();
  }

  // ------------------------------------------------------------------ //

  /** Three rapid identical signals must produce exactly one worker execution. */
  @Test
  void tripleIdenticalSignalsRunWorkerOnce() {
    String id = "triple-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();

    bean.signal(caseId, "event", Map.of("type", "click", "source", "button"));
    bean.signal(caseId, "event", Map.of("type", "click", "source", "button"));
    bean.signal(caseId, "event", Map.of("type", "click", "source", "button"));

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once despite three identical signals"));
  }

  /** Five rapid identical signals must produce exactly one worker execution. */
  @Test
  void fiveIdenticalSignalsRunWorkerOnce() {
    String id = "five-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();
    Map<String, Object> value = Map.of("amount", 100, "currency", "USD");

    for (int i = 0; i < 5; i++) {
      bean.signal(caseId, "event", value);
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once despite five identical signals"));
  }

  /**
   * After the first worker run the signal value is already in the context. Re-sending the same
   * value produces an empty diff → no CONTEXT_CHANGED published → worker not re-triggered.
   */
  @Test
  void sameSignalValueResendProducesNoContextDiffAndDoesNotRetrigger() {
    String id = "resend-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();
    Map<String, Object> value = Map.of("type", "APPROVED");

    bean.signal(caseId, "event", value);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1, SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get()));

    // Re-send the same value — context unchanged, no CONTEXT_CHANGED, no new scheduling
    bean.signal(caseId, "event", value);

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must not re-run when the same value is re-signaled"));
  }

  /** Two identical primitive-integer signals must be deduplicated to a single worker execution. */
  @Test
  void identicalPrimitiveIntegerSignalsDeduplicated() {
    String id = "int-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();

    bean.signal(caseId, "event", 42);
    bean.signal(caseId, "event", 42);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once for duplicate primitive-integer signal"));
  }

  /** Two identical string signals must be deduplicated to a single worker execution. */
  @Test
  void identicalStringSignalsDeduplicated() {
    String id = "str-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();

    bean.signal(caseId, "event", "CONFIRMED");
    bean.signal(caseId, "event", "CONFIRMED");

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once for duplicate string signal"));
  }

  /**
   * Two independent cases both receive the same signal path and value. Each case's worker must run
   * exactly once and their run counts must be fully isolated.
   */
  @Test
  void twoCasesWithSameSignalRunIsolated() {
    String id1 = "iso-1-" + UUID.randomUUID();
    String id2 = "iso-2-" + UUID.randomUUID();

    UUID caseId1 = bean.startCase(Map.of("id", id1)).toCompletableFuture().join();
    UUID caseId2 = bean.startCase(Map.of("id", id2)).toCompletableFuture().join();

    Map<String, Object> value = Map.of("type", "SHARED_EVENT");

    bean.signal(caseId1, "event", value);
    bean.signal(caseId2, "event", value);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  SignalDedupBean.runCountById.getOrDefault(id1, new AtomicInteger()).get(),
                  "Case 1 worker must run exactly once");
              assertEquals(
                  1,
                  SignalDedupBean.runCountById.getOrDefault(id2, new AtomicInteger()).get(),
                  "Case 2 worker must run exactly once");
            });
  }

  /** Signaling a path that no binding watches must not trigger any worker. */
  @Test
  void signalToUnboundPathDoesNotTriggerWorker() {
    String id = "unbound-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();

    // `event` is the bound path; `unrelated` is referenced by no binding
    bean.signal(caseId, "unrelated", Map.of("type", "IGNORED"));
    bean.signal(caseId, "unrelated", Map.of("type", "STILL_IGNORED"));

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    0,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must not run when signaling an unbound path"));
  }

  /**
   * Duplicate signals carrying a deeply nested object must be deduplicated — only one worker
   * execution regardless of object complexity.
   */
  @Test
  void duplicateSignalWithComplexNestedObjectRunsOnce() {
    String id = "complex-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();

    Map<String, Object> complexValue =
        Map.of(
            "header", Map.of("version", 2, "encoding", "UTF-8"),
            "body", Map.of("items", List.of("a", "b", "c"), "count", 3),
            "meta", Map.of("source", "integration", "priority", "HIGH"));

    bean.signal(caseId, "event", complexValue);
    bean.signal(caseId, "event", complexValue);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once for duplicate complex-object signal"));
  }

  /**
   * After the case reaches COMPLETED state the CaseContextChangedEventHandler exits early for
   * non-RUNNING cases, so a subsequent signal (even with a different value) must not trigger an
   * additional worker run.
   */
  @Test
  void signalAfterCaseCompletionDoesNotTriggerWorker() {
    String id = "post-complete-" + UUID.randomUUID();
    UUID caseId = goalBean.startCase(Map.of("id", id)).toCompletableFuture().join();

    goalBean.signal(caseId, "event", Map.of("type", "TRIGGER"));

    // Wait for the case to complete
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(
                  1,
                  SignalDedupWithGoalBean.runCountById.getOrDefault(id, new AtomicInteger()).get());
              assertEquals(CaseStatus.COMPLETED, caseInstanceCache.get(caseId).getState());
            });

    // Signal a DIFFERENT value — context would change, but the case ignores it
    goalBean.signal(caseId, "event", Map.of("type", "POST_COMPLETION"));

    await()
        .during(2, TimeUnit.SECONDS)
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupWithGoalBean.runCountById
                        .getOrDefault(id, new AtomicInteger())
                        .get(),
                    "Worker must not run after case is COMPLETED"));
  }

  /**
   * Four concurrent identical signals must produce exactly one worker execution. Tests the Vert.x
   * lock contention path in SignalReceivedEventHandler under higher parallelism.
   */
  @Test
  void fourConcurrentIdenticalSignalsRunWorkerOnce() {
    String id = "four-" + UUID.randomUUID();
    UUID caseId = bean.startCase(Map.of("id", id)).toCompletableFuture().join();
    Map<String, Object> value = Map.of("level", "HIGH", "score", 99);

    bean.signal(caseId, "event", value);
    bean.signal(caseId, "event", value);
    bean.signal(caseId, "event", value);
    bean.signal(caseId, "event", value);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    1,
                    SignalDedupBean.runCountById.getOrDefault(id, new AtomicInteger()).get(),
                    "Worker must run exactly once despite four concurrent identical signals"));
  }

  // ================================================================== //
  //  Beans                                                              //
  // ================================================================== //

  /** Case without a completion goal — stays RUNNING; used for most dedup scenarios. */
  @ApplicationScoped
  public static class SignalDedupBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> runCountById = new ConcurrentHashMap<>();

    private final Capability eventCapability =
        Capability.builder()
            .name("processEventDedup")
            .inputSchema("{ event: .event, id: .id }")
            .outputSchema("{ eventResult: .eventResult }")
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal-dedup-ext")
          .name("Signal Dedup Extended Test")
          .version("1.0.0")
          .capabilities(eventCapability)
          .workers(
              Worker.builder()
                  .name("event-worker-dedup")
                  .capabilityName("processEventDedup")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          input -> {
                            runCount.incrementAndGet();
                            String id = (String) input.get("id");
                            if (id != null) {
                              runCountById
                                  .computeIfAbsent(id, k -> new AtomicInteger())
                                  .incrementAndGet();
                            }
                            return WorkerResult.of(Map.of("eventResult", "processed"));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-event-dedup")
                  .capability(eventCapability)
                  .on(new ContextChangeTrigger(".event != null"))
                  .build())
          .build();
    }
  }

  /** Case with a completion goal — transitions to COMPLETED after first worker run. */
  @ApplicationScoped
  public static class SignalDedupWithGoalBean extends CaseHub {

    static final AtomicInteger runCount = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> runCountById = new ConcurrentHashMap<>();

    private final Capability eventCapability =
        Capability.builder()
            .name("processEventDedupGoal")
            .inputSchema("{ event: .event, id: .id }")
            .outputSchema("{ eventResult: .eventResult }")
            .build();

    private final Goal doneGoal =
        Goal.builder()
            .name("eventProcessedGoal")
            .condition(".eventResult == \"processed\"")
            .kind(GoalKind.SUCCESS)
            .build();

    @Override
    public CaseDefinition getDefinition() {
      return CaseDefinition.builder()
          .namespace("test-signal-dedup-ext-goal")
          .name("Signal Dedup With Goal Test")
          .version("1.0.0")
          .capabilities(eventCapability)
          .workers(
              Worker.builder()
                  .name("event-worker-dedup-goal")
                  .capabilityName("processEventDedupGoal")
                  .function(
                      new WorkerFunction.Sync<>(
                          Map.class,
                          input -> {
                            runCount.incrementAndGet();
                            String id = (String) input.get("id");
                            if (id != null) {
                              runCountById
                                  .computeIfAbsent(id, k -> new AtomicInteger())
                                  .incrementAndGet();
                            }
                            return WorkerResult.of(Map.of("eventResult", "processed"));
                          }))
                  .build())
          .bindings(
              Binding.builder()
                  .name("on-event-dedup-goal")
                  .capability(eventCapability)
                  .on(new ContextChangeTrigger(".event != null"))
                  .build())
          .goals(doneGoal)
          .completion(GoalExpression.allOf(doneGoal))
          .build();
    }
  }
}
